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

import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.compat.Bridge1_13;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.StringUtil;


/**
 * A simple check to prevent players from interacting with their inventory if they shouldn't be allowed to.
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
    *
    */
    public boolean check(final Player player, final InventoryData data, final IPlayerData pData, final InventoryConfig cc, final SlotType type) {
        
        // Shortcuts
        final MovingData mData = pData.getGenericInstance(MovingData.class);
        final PlayerMoveData thisMove = mData.playerMoves.getCurrentMove();
        final PlayerMoveData lastMove = mData.playerMoves.getFirstPastMove();
        final boolean isOnGroundForAllStates = thisMove.touchedGround || thisMove.from.onGround || thisMove.to.onGround;
        final boolean creative = player.getGameMode() == GameMode.CREATIVE && ((type == SlotType.QUICKBAR) || cc.invMoveDisableCreative);
        final boolean isInvMerchant = (player.getOpenInventory().getTopInventory().getType() == InventoryType.MERCHANT); 
        final boolean inVehicle = player.isInsideVehicle();
        final boolean isOnIce = (mData.sfOnIce > 0); 
        final boolean isSprintLost = (mData.lostSprintCount > 0);
        List<String> tags = new LinkedList<String>();
        boolean cancel = false;
        boolean violation = false;
        double hDistDiff = Math.abs(thisMove.hDistance - lastMove.hDistance);
    

        // Clicking while using/consuming an item
        // TODO: extend the cc.survivalFlyResetActiveItem workaround here in case of a violation?
        if (mData.isusingitem && !isInvMerchant) { 
            tags.add("usingitem");
            violation = true;
        }
        // ... while sneaking
        // TODO: Bring in survivalFly.reallySneaking
        //else if (player.isSneaking()) {
        //    tags.add("isSneaking");
        //    violation = true;
        //}
        // ... while swimming
        else if (Bridge1_13.isSwimming(player)) { 
            if (!isOnGroundForAllStates){   
                tags.add("isSwimming");
                violation = true; 
            }  
        }
        // ...  while sprinting
        // TODO: Ensure that the player is actually sprinting (Use the same sprinting mechanic in survivalFly?)
        else if (player.isSprinting() && !tags.contains("isSwimming") 
                && thisMove.hDistance > thisMove.walkSpeed && !isSprintLost) {
            tags.add("isSprinting");
            violation = true;
        }
        // ... while being dead or sleeping (-> Is it even possible?)
        else if (player.isDead() || player.isSleeping()) {
            tags.add(player.isDead() ? "isDead" : "isSleeping");
            violation = true;
        }
        // TODO: Add click+chatting check.
        // Last resort, check if the player is actively moving while clicking in their inventory
        else {
            
            final long currentEventTime = System.currentTimeMillis();
            if (isOnGroundForAllStates && ((currentEventTime - data.lastMoveEvent) < 65)
                && !inVehicle
                && !isOnIce 
                && !thisMove.from.inLiquid) {
         
                if (hDistDiff < cc.invMoveHdistLeniency && thisMove.hDistance > cc.invMoveHdistMin) {
                    violation = true;
                    tags.add("moving");
                }
            }
        }
    

        // Handle violations 
        if (violation && !creative) {
            data.invMoveVL += tags.contains("moving") ? ((thisMove.hDistance - cc.invMoveHdistMin) * 100D) : 1D;
            final ViolationData vd = new ViolationData(this, player, data.invMoveVL, 1, pData.getGenericInstance(InventoryConfig.class).invMoveActionList);
            if (vd.needsParameters()) vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
            cancel = executeActions(vd).willCancel();
        }
        
        // Cooldown
        if (!cancel) {
            data.invMoveVL *= 0.96D;
        }

        return cancel;
    }
}
