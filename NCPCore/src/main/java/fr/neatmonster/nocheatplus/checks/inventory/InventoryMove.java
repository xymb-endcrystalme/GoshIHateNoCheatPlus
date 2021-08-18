/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.checks.inventory;

import java.util.LinkedList;
import java.util.List;

import org.bukkit.GameMode;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.block.Block;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveInfo;
import fr.neatmonster.nocheatplus.checks.moving.magic.Magic;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.utilities.collision.CollisionUtil;
import fr.neatmonster.nocheatplus.compat.Bridge1_13;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.compat.BridgeEnchant;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.InventoryUtil;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;


/**
 * 
 * A check to prevent players from interacting with their inventory if they shouldn't be allowed to.
 */
public class InventoryMove extends Check {


   /**
    * Instanties a new InventoryMove check
    *
    */
    public InventoryMove() {
        super(CheckType.INVENTORY_INVENTORYMOVE);
    }
    

   /**
    * Checks a player
    * @param player
    * @param data
    * @param pData
    * @param cc
    * @param type
    * @return true if successful
    *
    */
    public boolean check(final Player player, final InventoryData data, final IPlayerData pData, final InventoryConfig cc, final SlotType type) {
        
        boolean cancel = false;
        boolean violation = false;
        List<String> tags = new LinkedList<String>();

       /* 
        * Important: MC allows players to swim (and keep the status) when on ground, but this is not *consistently* reflected back to the server 
        * (while still allowing them to move at swimming speed) instead, isSprinting() will return. Observed in both Spigot and PaperMC around MC 1.13/14
        * -> Seems fixed in latest versions (opening an inventory will end the swimming phase, if on ground)
        * TODO: Keep the workaround for server <1.17? ... 
        *
        */
        // TODO: Ascending in liquid (friction envelope)
        // TODO: Click and jump. Not sure.
        // TODO: Improbable check, frequency of unlikely inventory clicks(...)
        // F.p.: Friction after stopping to move and immediately opening the inv and clicking in it:
        //       the current implementation is better than before but it still is afflicted. Too tedious to fix now...
        
        // Shortcuts:
        final MovingData mData = pData.getGenericInstance(MovingData.class);
        final PlayerMoveData thisMove = mData.playerMoves.getCurrentMove();
        final PlayerMoveData lastMove = mData.playerMoves.getFirstPastMove();
        final PlayerMoveData pastMove2 = mData.playerMoves.getSecondPastMove();
        final PlayerMoveData pastMove3 = mData.playerMoves.getThirdPastMove();
        final PlayerMoveData pastMove4 = mData.playerMoves.getPastMove(3);
        final boolean fullLiquidMove = thisMove.from.inLiquid && thisMove.to.inLiquid;
        final long currentEvent = System.currentTimeMillis();
        final boolean isCollidingWithEntities = CollisionUtil.isCollidingWithEntities(player, true) && ServerVersion.compareMinecraftVersion("1.9") >= 0;
        final boolean swimming = (Bridge1_13.isSwimming(player) || mData.timeSwimming + 1000 > currentEvent) || player.isSprinting(); 
        final double minHDistance = thisMove.hAllowedDistanceBase / cc.invMoveHdistDivisor;
        final boolean creative = player.getGameMode() == GameMode.CREATIVE && ((type == SlotType.QUICKBAR) || cc.invMoveDisableCreative);
        final boolean isMerchant = (player.getOpenInventory().getTopInventory().getType() == InventoryType.MERCHANT); 
        final boolean movingOnSurface = (thisMove.from.inLiquid && !thisMove.to.inLiquid || mData.watermovect == 1) && mData.liftOffEnvelope.name().startsWith("LIMIT");

        // Debug first.
        if (pData.isDebugActive(CheckType.INVENTORY_INVENTORYMOVE)) {
               player.sendMessage("\nyDistance= " + StringUtil.fdec3.format(thisMove.yDistance)
                + "\nhDistance= " + StringUtil.fdec3.format(thisMove.hDistance)
                + "\nhDistMin("+cc.invMoveHdistDivisor+")="  + StringUtil.fdec3.format(minHDistance) 
                + "\nhAllowedDistance= " + StringUtil.fdec3.format(thisMove.hAllowedDistance)
                + "\nhAllowedDistanceBase= " + StringUtil.fdec3.format(thisMove.hAllowedDistanceBase)
                + "\ntouchedGround= " + thisMove.touchedGround + "(" + (thisMove.from.onGround ? "ground -> " : "---- -> ") + (thisMove.to.onGround ? "ground" : "----") +")"
                + "\nmovingOnSurface=" + movingOnSurface + " fullLiquidMove= " + fullLiquidMove
            );
        }
        
        // Clicking while using/consuming an item
        if (mData.isusingitem && !isMerchant) { 
            tags.add("usingitem");
            violation = true;
        }

        // ... Clicking in inv open during an attack
        // TODO: feed Improbable here (context: killauras but also auto-soups that quickly swap bowls from
        // hotbar to inventory)
        else if (data.inventoryAttack && currentEvent - data.lastAttackEvent < 500) {
            violation = true;
            tags.add("clickattack");
            // Reset once used, important as then subsequent clicks will still be flagged.
            data.inventoryAttack = false;
        }

        // ... while swimming (in water, not on ground. Players are forced to stop swimming if they open an inventory)
        else if (Bridge1_13.isSwimming(player) && !thisMove.touchedGround) {
            violation = true;
            tags.add("isSwimming");
        }

        // ... while being dead or sleeping (-> Is it even possible?)
        else if (player.isDead() || player.isSleeping()) {
            tags.add(player.isDead() ? "isDead" : "isSleeping");
            violation = true;
        }

        // ...  while sprinting
        else if (player.isSprinting() && !player.isFlying() && !(fullLiquidMove || movingOnSurface)) { // In case the player is bugged and instead of isSwimming, isSprinting returns
            tags.add("isSprinting");
            violation = true;
        }
        
        // ... while bunnyhopping, only pick up hopping and clicking at the same time
        else if (thisMove.bunnyHop) {
            tags.add("bunnyclick");
            violation = true;
        }
        
        // ... while sneaking (legacy handling, players cannot use accessability settings to sneak while having an open inv)
        else if (player.isSneaking() && !Bridge1_13.hasIsSwimming()) {
            violation = true;
            tags.add("isSneaking(<1.13)");
        }
        
        // ...while climbing a block (one would need to click and press space bar at the same time to ascend)
        else if (thisMove.from.onClimbable && thisMove.yDistance >= 0.117
                // If hit on a climbable, skip. 
                && mData.getOrUseVerticalVelocity(thisMove.yDistance) == null) {
            violation = true;
            tags.add("climbclick");
        }
        
        // Last resort, check if the player is actively moving while clicking in their inventory
        else {
            
            if (thisMove.hDistance > minHDistance * (player.isSneaking() && Bridge1_13.hasIsSwimming() ? Magic.modSneak : 1.0)
                && ((currentEvent - data.lastMoveEvent) < 65)
                // Skipping conditions 
                && !mData.isVelocityJumpPhase()
                && !isCollidingWithEntities
                && !player.isInsideVehicle() 
                && !thisMove.downStream
                && Double.isInfinite(Bridge1_13.getDolphinGraceAmplifier(player))
                ){ 
                tags.add("moving");
                
                // Walking on ground or on ground in a liquid 
                if (thisMove.touchedGround 
                    // No changes in speed during the 2 last movements
                    && thisMove.hAllowedDistanceBase == lastMove.hAllowedDistance) {
                    violation = true; 
                }
                // Moving above liquid surface
                else if (movingOnSurface && !thisMove.touchedGround
                        // No changes in speed during the 5 last movements
                        && thisMove.hAllowedDistanceBase == lastMove.hAllowedDistance
                        && pastMove2.hAllowedDistanceBase == lastMove.hAllowedDistance
                        && pastMove3.hAllowedDistanceBase == pastMove2.hAllowedDistance) { 
                    violation = true;
                }
                // Moving inside liquid (but not on ground)
                else if (fullLiquidMove && !thisMove.touchedGround
                        // No changes in speed during the 4 last movements
                        && thisMove.hAllowedDistanceBase == lastMove.hAllowedDistance
                        && pastMove2.hAllowedDistanceBase == lastMove.hAllowedDistance
                        && pastMove3.hAllowedDistanceBase == pastMove2.hAllowedDistance) {
                    violation = true;
                } 
            }
        }
    
        // Handle violations 
        if (violation && !creative) {
            data.invMoveVL += 1D;
            final ViolationData vd = new ViolationData(this, player, data.invMoveVL, 1D, cc.invMoveActionList);
            if (vd.needsParameters()) vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
            cancel = executeActions(vd).willCancel();
        }
        // Cooldown
        else {
            data.invMoveVL *= 0.96D;
        }
        return cancel;
    }
}
