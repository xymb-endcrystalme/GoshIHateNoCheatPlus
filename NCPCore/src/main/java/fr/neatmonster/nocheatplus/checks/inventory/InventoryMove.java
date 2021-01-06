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
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.compat.BridgeEnchant;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveInfo;
import fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving;
import fr.neatmonster.nocheatplus.utilities.location.TrigUtil;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.collision.CollisionUtil;
import fr.neatmonster.nocheatplus.checks.moving.magic.Magic;
import fr.neatmonster.nocheatplus.checks.moving.model.ModelFlying;


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
    * @return true if successful
    *
    */
    public boolean check(final Player player, final InventoryData data, final IPlayerData pData, final InventoryConfig cc, final SlotType type){
        
        // TODO: Inventory click + swing packet/attack ? Would be a way to detect hitting+attack (which would catch careless players
        // who do not set their killaura to stop attacking if the inv is opened, although it should probably be done in the fight section (Fight.ImpossibleHit?))

        // TODO: in liquid checks: Detect intentional upstream movement during an inv click (legit players cannot move during a click and will have to
        // follow the liquid's flowing direction). For now, downstream movements are ignored. 


        // TODO: Further confine and sharpen conditions... 

       /* 
        * Important: MC allows players to swim on the ground but the status is not *consistently* reflected back to the server 
        * (while still allowing them to move at swimming speed) instead, isSprinting() will return. Observed in both Spigot and PaperMC.
        *
        */
        
        // Shortcuts:
        // Movement
        final MovingData mData = pData.getGenericInstance(MovingData.class);
        final PlayerMoveData thisMove = mData.playerMoves.getCurrentMove();
        final PlayerMoveData lastMove = mData.playerMoves.getFirstPastMove();
        final PlayerMoveInfo moveInfo = aux.usePlayerMoveInfo();
        final PlayerLocation from = moveInfo.from;
        final PlayerLocation to = moveInfo.to;
        final boolean isSamePos = from.isSamePos(to); // The player is standing still, no XYZ distances.
        final boolean thisMoveOnGround = thisMove.touchedGround || thisMove.from.onGround || thisMove.to.onGround;
        final boolean fromLiquid = thisMove.from.inLiquid;
        final boolean toLiquid = thisMove.to.inLiquid;
        
        // Others
        final FightData fData = pData.getGenericInstance(FightData.class);
        final boolean creative = player.getGameMode() == GameMode.CREATIVE && ((type == SlotType.QUICKBAR) || cc.invMoveDisableCreative);
        final boolean isMerchant = (player.getOpenInventory().getTopInventory().getType() == InventoryType.MERCHANT); 
        final long currentEvent = System.currentTimeMillis();
        final boolean noIceTick = (mData.sfOnIce == 0); 
        final boolean isSprintLost = (mData.lostSprintCount > 0);
        final boolean isCollidingWithEntities = CollisionUtil.isCollidingWithEntities(player, true) && Bridge1_9.hasElytra(); // Dirty way to check if the server is 1.9+
        List<String> tags = new LinkedList<String>();

        boolean cancel = false;
        boolean violation = false;
        double friction = lastMove.hDistance * mData.lastFrictionHorizontal;
        double hDistDiff = Math.abs(thisMove.hDistance - lastMove.hDistance);
        double deltaFrict = Math.abs(friction - hDistDiff);
        double margin = setAllowedFrictionMargin(player, pData, mData, thisMoveOnGround, thisMove, lastMove);



        // Debug first.
        if (pData.isDebugActive(CheckType.INVENTORY_INVENTORYMOVE)) {
            debug(player, "friction= " + friction + " hDistDiff= " + hDistDiff
                + " deltaFrict= " + deltaFrict + " margin= " + margin
                + " thisMoveOnGround= " + thisMoveOnGround);
        }
        


        // Clicking while using/consuming an item
        if (mData.isusingitem && !isMerchant) { 
            tags.add("usingitem");
            violation = true;
        }

        // ... while attacking
        // TODO: Doesn't seem to work...
        // else if (fData.noSwingPacket){
        //     tags.add("armswing");
        //     violation = true;
        // }

        // ...while flying
        // (not consistent with the delta outputs, especially if flying backwards)
        // else if (player.isFlying() && (thisMove.yDistance > 0.0 || deltaFrict >= margin)){ 
        //     violation = true;
        //     tags.add("isFlying");
        // }

        // ... while swimming
        // Having a positive y delta means that the player is swimming upwards (not possible, as that would mean a click+jump bar pressed...).
        // if delta>margin, we assume the player to be intentionally moving and not being moved by friction.
        else if (Bridge1_13.isSwimming(player) && (deltaFrict >= margin || thisMove.yDistance > 0.7)
                && !isSamePos){ 
            tags.add("isSwimming");
            violation = true;   
        }
        
        // ... while bunnyhopping
        // ground -> (begin delay countdown 10-5=ascend) bunnyhop (5-0= descend) -> hit ground.
        else if ((thisMove.bunnyHop || mData.bunnyhopDelay > 5) // player can interact with their inventory during a descend phase.
                && !mData.isVelocityJumpPhase()){
            violation = true;
            tags.add("hopClick");
        }


        // ... while being dead or sleeping (-> Is it even possible?)
        else if (player.isDead() || player.isSleeping()) {
            tags.add(player.isDead() ? "isDead" : "isSleeping");
            violation = true;
        }

        // ...  while sprinting
        else if (player.isSprinting() && thisMove.hDistance > thisMove.walkSpeed
                && thisMoveOnGround
                && !isSprintLost){
            tags.add("isSprinting");
            
            // Use the margin as a workaround to the MC bug described above (The player is swimming on the ground but isSprinting() is returned instead)
            if (toLiquid && fromLiquid && deltaFrict >= margin){
                violation = true;
            }
            // (no else as the delta could be < invSlowDown (stop). Explicitly require the player to not be in a liquid
            else if (!toLiquid && !fromLiquid) violation = true;
        }

        // ... while sneaking
        else if (player.isSneaking() && ((currentEvent - data.lastMoveEvent) < 65)
                && !(thisMove.downStream & mData.isdownstream) // Fix thisMove#downStream, rather. Both have to be true
                && !isCollidingWithEntities
                &&  noIceTick
                && !isSamePos // Need to ensure that players can toggle sneak with their inv open. (around mc 1.13)
                ){
            tags.add("isSneaking");

            if (hDistDiff < cc.invMoveHdistLeniency 
                && thisMove.hDistance > (fromLiquid ? 0.009 : cc.invMoveHdistMin) // Sneaking in water demands less speed. (Lava might demand even less)
                && thisMoveOnGround){
                
                // Use the margin as a workaround to the MC bug described above (The player is swimming on the ground but isSneaking() is returned instead)
                if (toLiquid && fromLiquid && deltaFrict >= margin){
                    violation = true;
                }
                else if (!toLiquid && !fromLiquid) violation = true;
            }
        }
        
        // Last resort, check if the player is actively moving while clicking in their inventory
        else {
            
            if (((currentEvent - data.lastMoveEvent) < 65)
                && !(thisMove.downStream & mData.isdownstream) 
                && !mData.isVelocityJumpPhase()
                && !isCollidingWithEntities
                && !player.isInsideVehicle() 
                &&  noIceTick 
                && !isSamePos 
                ){ 
                
                tags.add("moving");
                // On ground 
                if (hDistDiff < cc.invMoveHdistLeniency && thisMove.hDistance > cc.invMoveHdistMin
                    && thisMoveOnGround) {
                    
                    if (toLiquid && fromLiquid && deltaFrict >= margin){
                        violation = true;
                    }
                    else if (!toLiquid && !fromLiquid) violation = true; 
                }
                // Moving inside liquid (but not on the ground)
                else if (fromLiquid && toLiquid && mData.liqtick > 2 // Grace period, let at least 2 ticks pass by before checking after having entered a liquid
                        && (deltaFrict > margin && thisMove.hDistance > cc.invMoveHdistMin || thisMove.yDistance > 0.7)){
                    violation = true;
                } 
                // Moving above liquid surface
                else if (((fromLiquid && !toLiquid) || mData.watermovect == 1) 
                        && mData.liftOffEnvelope.name().startsWith("LIMIT")
                        && deltaFrict > margin && thisMove.hDistance > cc.invMoveHdistMin){ 
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
        else data.invMoveVL *= 0.96D;
    
        return cancel;
    }



  /**
    * Set the allowed friction according to medium and enchant.
    * @param player
    * @param pData
    * @param mData
    * @param thisMoveOnGround from/to/or touched the ground due to a lostGround workaround being applied.
    * @param thisMove
    * @param lastMove
    *
    * @return invSlowDownMarginH The horizontal friction for which the player will be considered as slowing down.
    */
    private double setAllowedFrictionMargin(final Player player, final IPlayerData pData, final MovingData mData,
                                            final boolean thisMoveOnGround, final PlayerMoveData thisMove, final PlayerMoveData lastMove){
    
        double invSlowDownMarginH = 0.0;
    
        // Liquid friction
        if ((thisMove.from.inLiquid && thisMove.to.inLiquid)
            // Surface level
            ||((thisMove.from.inLiquid && !thisMove.to.inLiquid) || mData.watermovect == 1 && mData.liftOffEnvelope.name().startsWith("LIMIT"))
            && !player.isFlying() // handled below
            ){

            invSlowDownMarginH = Bridge1_13.isSwimming(player) ? (thisMoveOnGround ? 0.110 : 0.100) : 0.080; // Empirical/magic constants.
        
            // If in water, we need to account for all related enchants.
            if (thisMove.from.inWater || thisMove.to.inWater){ // From testing (lava): in-air -> splash move (lava) with delta 0.04834
  
                final int depthStriderLevel = BridgeEnchant.getDepthStriderLevel(player);
                if (depthStriderLevel > 0) {
                    invSlowDownMarginH *= Magic.modDepthStrider[depthStriderLevel];
                }

                if (!Double.isInfinite(Bridge1_13.getDolphinGraceAmplifier(player))) {
                    invSlowDownMarginH *= Magic.modDolphinsGrace;
                    if (depthStriderLevel > 0) {
                        invSlowDownMarginH *= 1.65 + 0.07 * depthStriderLevel; // 1.0 -> 1.7. Should fix modDolphinsGrace...
                    }
                }
            }
            // Lava
            else {
                // Walking on ground in lava
                if (thisMoveOnGround) invSlowDownMarginH = 0.038; // From testings: 0.0399...

            }
        }
        // todo... 
        else if (player.isFlying()){

            final ModelFlying model = thisMove.modelFlying;
            invSlowDownMarginH = 0.90;
            if (player.isSprinting()) invSlowDownMarginH *= model.getHorizontalModSprint();
        }
        // TODO: Ice friction

        return invSlowDownMarginH;
    }
}
