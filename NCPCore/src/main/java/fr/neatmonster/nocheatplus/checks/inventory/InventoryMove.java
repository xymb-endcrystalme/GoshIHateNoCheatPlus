package fr.neatmonster.nocheatplus.checks.inventory;

import java.util.LinkedList;
import java.util.List;
import java.util.ArrayList;

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
    // If the player is interacting with their hotbar, then skip the check
    if (type == SlotType.QUICKBAR) {
		return false;
    }
    else {
    	// Inv. interaction whilst blocking
		if (player.isBlocking()) {
		    // Is the player in Creative?
		   if (player.getGameMode() == GameMode.CREATIVE) {
		        // Is the DisableCreative config option not enabled?
				if (!cc.invMoveDisableCreative) {
			         // Then trigger a violation and add a tag displaying what kind of interaction the player tried to perform
					tags.add("isBlocking");
					violation = true;
			    }
			    // If it's enabled, skip
			    else {
			      return false;

			    }
		  }
		    // Player is not in creative, trigger a violation + add tag
		    else {
			  tags.add("isBlocking");
			  violation = true;
			}
	   }
    	// Inv. interaction whilst swimming
		else if (player.isSwimming()) {
		    // Is the player in Creative?
			if (player.getGameMode() == GameMode.CREATIVE) {
		        // Is the DisableCreative config option not enabled?
				if (!cc.invMoveDisableCreative) {
			         // Then trigger a violation and add a tag displaying what kind of interaction the player tried to perform
			        tags.add("isSwimming");
			        violation = true;
			    }
				// If it's enabled, skip 
			    else {
			      return false;

			    }
		  }
			// Player is not in creative, trigger a violation + add tag
		    else {
			  tags.add("isSwimming");
			  violation = true;
			}
	   }
    	// Inv. interaction whilst sprinting
		else if (player.isSprinting()) {
		    // Is the player in Creative?
			if (player.getGameMode() == GameMode.CREATIVE) {
		        // Is the DisableCreative config option not enabled?
				if (!cc.invMoveDisableCreative) {
			         // Then trigger a violation and add a tag displaying what kind of interaction the player tried to perform
			        tags.add("isSprinting");
			        violation = true;
			    }
				// If it's enabled, skip
			    else {
			      return false;

			    }
		  }
		    // Player is not in creative, trigger a violation + add tag
		    else {
			  tags.add("isSprinting");
			  violation = true;
			}
	   }
    	// Inv. interaction whilst sneaking
		else if (player.isSneaking()) {
		    // Is the player in Creative?
			if (player.getGameMode() == GameMode.CREATIVE) {
		        // Is the DisableCreative config option not enabled?
				if (!cc.invMoveDisableCreative) {
			         // Then trigger a violation and add a tag displaying what kind of interaction the player tried to perform
			        tags.add("isSneaking");
			        violation = true;
			    }
				// If it's enabled, skip
			    else {
			      return false;

			    }
		  }
		    // Player is not in creative, trigger a violation + add tag
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
  }
