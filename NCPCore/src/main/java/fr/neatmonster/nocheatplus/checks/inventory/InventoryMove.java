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

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.fight.FightData;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.compat.Bridge1_13;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveInfo;
import fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving;
import fr.neatmonster.nocheatplus.utilities.location.TrigUtil;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.collision.CollisionUtil;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.compat.Bridge1_13;


/**
 * A check to prevent players from interacting with their inventory if they shouldn't be allowed to.
 */
public class InventoryMove extends Check {
    

   private final AuxMoving aux = NCPAPIProvider.getNoCheatPlusAPI().getGenericInstance(AuxMoving.class);


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
    *
    */
    public boolean check(final Player player, final InventoryData data, final IPlayerData pData, final InventoryConfig cc, final SlotType type){
        
        // TODO: Inventory click + swing packet/attack ? Would be a way to detect hitting+attack (which would catch careless players
        // who do not set their killaura to stop attacking if the inv is opened, although it should probably be done in the fight section (Fight.ImpossibleHit?))

        // TODO: in liquid checks: Detect intentional upstream movement during an inv click (legit players cannot move during a click and will have to
        // follow the liquid's flowing direction). For now, downstream movements are ignored. 

        // TODO: Ice -> Make use of friction to check if the player is intentionally moving or is carried forward by momentum due to the slipperiness (In which case, skip).

        // TODO: Model fly+inv click

        // TODO: Issue: if SurvivalFly is disabled, the friction variable won't be updated... 

        // TODO: Further confine and sharpen conditions... (Lava in particular should get its own model)
        
        // Shortcuts
        final MovingData mData = pData.getGenericInstance(MovingData.class);
        final PlayerMoveData thisMove = mData.playerMoves.getCurrentMove();
        final PlayerMoveData lastMove = mData.playerMoves.getFirstPastMove();
        final FightData fData = pData.getGenericInstance(FightData.class);
        final PlayerMoveInfo moveInfo = aux.usePlayerMoveInfo();
        final PlayerLocation from = moveInfo.from;
        final PlayerLocation to = moveInfo.to;
        final boolean isSamePos = from.isSamePos(to); // The player is standing still, no XYZ distances.
        final boolean isOnGroundForAllStates = thisMove.touchedGround || thisMove.from.onGround || thisMove.to.onGround;

        final boolean creative = player.getGameMode() == GameMode.CREATIVE && ((type == SlotType.QUICKBAR) || cc.invMoveDisableCreative);
        final boolean isInvMerchant = (player.getOpenInventory().getTopInventory().getType() == InventoryType.MERCHANT); 
        final long currentEvent = System.currentTimeMillis();
        final boolean inVehicle = player.isInsideVehicle();
        final boolean noIceTick = (mData.sfOnIce == 0); 
        final boolean isSprintLost = (mData.lostSprintCount > 0);
        final boolean isCollidingWithEntities = CollisionUtil.isCollidingWithEntities(player, true) && Bridge1_9.hasElytra(); // Dirty way to check if the server is 1.9+
        List<String> tags = new LinkedList<String>();
        boolean cancel = false;
        boolean violation = false;
        double hLiqFrict = lastMove.hDistance * mData.lastFrictionHorizontal;
        double hDistDiff = Math.abs(thisMove.hDistance - lastMove.hDistance);
        double hDiffFrict = Math.abs(hLiqFrict - hDistDiff);
    

        // Clicking while using/consuming an item
        if (mData.isusingitem && !isInvMerchant) { 
            tags.add("usingitem");
            violation = true;
        }
        // ... while attacking
        // TODO: Doesn't seem to work...
        //else if (fData.noSwingPacket){
        //    tags.add("armswing");
        //    violation = true;
        //}
        // ... while sneaking
        else if (player.isSneaking() && ((currentEvent - data.lastMoveEvent) < 65)
                &&  noIceTick
                && !isSamePos // Need to ensure that players can toggle sneak with their inv open. (around mc 1.13)
                && !thisMove.downStream
                && !isCollidingWithEntities
                ){
            tags.add("isSneaking");
            // Ordinary grounded move
            if (hDistDiff < cc.invMoveHdistLeniency && thisMove.hDistance > cc.invMoveHdistMin
                && thisMove.hDistance > cc.invMoveHdistMin 
                && isOnGroundForAllStates){
                violation = true;
            }
            // Sneaking in liquid: players are forced to descend if they are sneaking (Moving downstream is disregarded)
            // TODO: Actually, players can sneak above lava surface with toggle sneak+space bar...
            else if (thisMove.from.inLiquid && thisMove.to.inLiquid && mData.liqtick > 2 // Grace period, prevents false positives when the player first enters a liquid
                    && (hDiffFrict > mData.invSlowDownMarginH || thisMove.yDistance == 0.0 || thisMove.yDistance > 0.0) // Moving on ground (y0.0)/up/not enough slow down
                    && thisMove.hDistance > 0.009){  // Enough hDistance. Sneaking in liquid demands even less speed. TODO: Configurable?
                violation = true;

            }
        }
        // ... while swimming
        else if (Bridge1_13.isSwimming(player) && hDiffFrict > mData.invSlowDownMarginH // Not enough slow down to assume a stop. 
                && !isSamePos){ 
            tags.add("isSwimming");
            violation = true;   
        }
        // ...  while sprinting
        else if (player.isSprinting() && !tags.contains("isSwimming") 
                && thisMove.hDistance > thisMove.walkSpeed && !isSprintLost
                && isOnGroundForAllStates // Fix a false positive with players in survival flying (friction after stop fly)
                ){
            tags.add("isSprinting");
            violation = true;
        }
        // ... while being dead or sleeping (-> Is it even possible?)
        else if (player.isDead() || player.isSleeping()) {
            tags.add(player.isDead() ? "isDead" : "isSleeping");
            violation = true;
        }
        // Last resort, check if the player is actively moving while clicking in their inventory
        else {
            
            if (thisMove.hDistance > cc.invMoveHdistMin && ((currentEvent - data.lastMoveEvent) < 65)
                &&  noIceTick 
                && !isSamePos 
                && !isCollidingWithEntities
                && !thisMove.downStream 
                && !inVehicle 
                ){ 
                
                tags.add("moving");
                // Ordinary grounded move
                if (hDistDiff < cc.invMoveHdistLeniency && isOnGroundForAllStates) {
                    violation = true;
                }
                // Moving inside liquid: players are forced to slow down if they open an inventory (Moving downstream is disregarded)
                else if (thisMove.from.inLiquid && thisMove.to.inLiquid 
                        && (hDiffFrict > mData.invSlowDownMarginH || thisMove.yDistance == 0.0 || thisMove.yDistance > 0.0) 
                        && mData.liqtick > 2){
                    violation = true;
                } 
                // Above surface
                else if (((thisMove.from.inLiquid && !thisMove.to.inLiquid) || mData.watermovect == 1) && mData.liftOffEnvelope.name().startsWith("LIMIT")
                        && hDiffFrict > mData.invSlowDownMarginH){ 
                    violation = true;
                }
            }
        }
    
        // Handle violations 
        if (violation && !creative) {
            data.invMoveVL += 1D;
            final ViolationData vd = new ViolationData(this, player, data.invMoveVL, 1D, pData.getGenericInstance(InventoryConfig.class).invMoveActionList);
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