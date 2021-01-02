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
        // TODO: Model fly+inv click?
        // Issue: if SurvivalFly is disabled, the friction variable won't be updated... 
        // TODO: Further confine and sharpen conditions... 


        // Important: MC allows players to "swim" on the ground bu the status is not reflected back to the server while still allowing them to move at swimming speed
        // (sprinting and sneaking are reflected)
        
        // Shortcuts
        final MovingData mData = pData.getGenericInstance(MovingData.class);
        final PlayerMoveData thisMove = mData.playerMoves.getCurrentMove();
        final PlayerMoveData lastMove = mData.playerMoves.getFirstPastMove();
        final FightData fData = pData.getGenericInstance(FightData.class);
        final PlayerMoveInfo moveInfo = aux.usePlayerMoveInfo();
        final PlayerLocation from = moveInfo.from;
        final PlayerLocation to = moveInfo.to;
        final boolean isSamePos = from.isSamePos(to); // The player is standing still, no XYZ distances.
        final boolean thisMoveOnGround = thisMove.touchedGround || thisMove.from.onGround || thisMove.to.onGround;
        final boolean fromLiquid = thisMove.from.inLiquid;
        final boolean toLiquid = thisMove.to.inLiquid;

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
                && !(thisMove.downStream & mData.isdownstream) // Fix thisMove#downStream, rather. Both have to be true
                && !isCollidingWithEntities
                ){
            tags.add("isSneaking");

            if (hDistDiff < cc.invMoveHdistLeniency 
                && thisMove.hDistance > (fromLiquid ? 0.009 : cc.invMoveHdistMin) // Sneaking in water demands less speed.
                && thisMoveOnGround){
                
                // Issue when swimming on the ground. The swimming state of the client is not reported. Use the margin as a workaround.
                if (toLiquid && fromLiquid && hDiffFrict > mData.invSlowDownMarginH){
                    violation = true;
                }
                // on ground movement
                else if (!toLiquid || !fromLiquid) violation = true;
            }
        }

        // ... while swimming
        else if (Bridge1_13.isSwimming(player) 
                // If hDiff > margin we assume the player to be intentionally moving and not being moved by the liquid friction after a stop
                && hDiffFrict > mData.invSlowDownMarginH 
                && !isSamePos){ 
            tags.add("isSwimming");
            violation = true;   
        }

        // ...  while sprinting
        else if (player.isSprinting() && thisMove.hDistance > thisMove.walkSpeed
                && thisMoveOnGround // Fix a false positive with players in survival flying (friction after stop fly)
                && !isSprintLost){
            tags.add("isSprinting");

            if (toLiquid && fromLiquid && hDiffFrict > mData.invSlowDownMarginH){
                violation = true;
            }
            else if (!toLiquid || !fromLiquid) violation = true;
        }

        // ... while being dead or sleeping (-> Is it even possible?)
        else if (player.isDead() || player.isSleeping()) {
            tags.add(player.isDead() ? "isDead" : "isSleeping");
            violation = true;
        }
        
        // Last resort, check if the player is actively moving while clicking in their inventory
        else {
            
            if (((currentEvent - data.lastMoveEvent) < 65)
                &&  noIceTick 
                && !isSamePos 
                && !isCollidingWithEntities
                && !(thisMove.downStream & mData.isdownstream) 
                && !inVehicle 
                && !mData.isVelocityJumpPhase()
                ){ 
                
                tags.add("moving");
                // On ground 
                if (hDistDiff < cc.invMoveHdistLeniency && thisMove.hDistance > cc.invMoveHdistMin
                    && thisMoveOnGround) {

                    if (toLiquid && fromLiquid && hDiffFrict > mData.invSlowDownMarginH){
                        violation = true;
                    }
                    else if (!toLiquid || !fromLiquid) violation = true;
                }
                // In liquid (Only checks for actively moving inside a liquid (not on the ground)
                else if (fromLiquid && toLiquid 
                        && mData.liqtick > 2 // Grace period, let at least 2 ticks pass by before checking after having entered a liquid
                        // Having a positive y delta means that the player is swimming upwards (not possible, as that would mean a click+jump bar pressed...)
                        && (hDiffFrict > mData.invSlowDownMarginH || thisMove.yDistance > 0.0) 
                        ){
                    violation = true;
                } 
                // Above surface
                else if (((fromLiquid && !toLiquid) || mData.watermovect == 1) 
                        && mData.liftOffEnvelope.name().startsWith("LIMIT")
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
