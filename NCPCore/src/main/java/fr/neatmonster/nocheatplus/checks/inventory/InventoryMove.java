package fr.neatmonster.nocheatplus.checks.inventory;

import java.util.LinkedList;
import java.util.List;

import org.bukkit.GameMode;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.entity.Player;


import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.StringUtil;

/**
 * A simple check to prevent players from interacting with their inventory if they shouldn't be allowed to.
 */

public class InventoryMove extends Check {
	
	private final List<String> tags = new LinkedList<String>();
	
	public InventoryMove() {
        super(CheckType.INVENTORY_MOVE);
    }
	
  public boolean check(final Player player, final InventoryData data, final IPlayerData pData, final InventoryConfig cc, final SlotType type) {
		
    boolean cancel = false;
    boolean violation = false;
    
    tags.clear();
 	// Inv. interaction whilst blocking
	if (player.isBlocking()) {
	  // Check if the player is in creative
	  if (player.getGameMode() == GameMode.CREATIVE) {
		  // (Skip hotbar interactions)
		  if(type == SlotType.QUICKBAR) {
		    	return false; 
		  }
		  // Check if the Disable_Creative option is not enabled
		  else if (!cc.invMoveDisableCreative) {
			   // Trigger a violation + tags
		           tags.add("isBlocking");
		           violation = true;
		  }
		  // Disable_Creative is enabled, skip
		  else {
		    return false;
		  }
	  }
	  // Player is not in creative 
	  else {
	    tags.add("isBlocking");
	    // Trigger a violation + tags
	    violation = true;
          }
        }
     	// Inv. interaction whilst swimming
	else if (player.isSwimming()) {
		  if (player.getGameMode() == GameMode.CREATIVE) {
			 if(type == SlotType.QUICKBAR) {
			    	return false; 
			 }
			 else if (!cc.invMoveDisableCreative) {
			           tags.add("isSwimming");
			           violation = true;
			 }
			 else {
			    return false;
			 }
		  }
	         else {
	          tags.add("isBlocking");
	          violation = true;
                 }
        }
     	// Inv. interaction whilst sprinting
	else if (player.isSprinting()) {
		  if (player.getGameMode() == GameMode.CREATIVE) {
			 if(type == SlotType.QUICKBAR) {
			    	return false; 
			 }
			 else if (!cc.invMoveDisableCreative) {
			           tags.add("isSprinting");
			           violation = true;
			 }
			 else {
			    return false;
			 }
		  }
		  else {
		    tags.add("isSprinting");
		    violation = true;
	          }
	}
     	// Inv. interaction whilst Sneaking
	else if (player.isSneaking()) {
		  if (player.getGameMode() == GameMode.CREATIVE) {
			 if(type == SlotType.QUICKBAR) {
			    	return false; 
			 }
			 else if (!cc.invMoveDisableCreative) {
			           tags.add("isSneaking");
			           violation = true;
			 }
			 else {
			    return false;
			 }
		  }
		  else {
		    tags.add("isSneaking");
		    violation = true;
	           }
	}
	    if (violation) {
	       data.invMoveVL += 1.0D;
	       final ViolationData vd = new ViolationData(this, player, data.invMoveVL, 1, pData.getGenericInstance(InventoryConfig.class).invMoveActionList);
	    if (vd.needsParameters()){
	        vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
	    }
	    return executeActions(vd).willCancel();
	  } 
	return cancel;
       }
     }
  

