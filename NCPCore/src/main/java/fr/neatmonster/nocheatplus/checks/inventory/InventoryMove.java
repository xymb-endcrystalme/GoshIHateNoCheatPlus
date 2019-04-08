package fr.neatmonster.nocheatplus.checks.inventory;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType.SlotType;

import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.players.IPlayerData;

/**
 * A simple check to prevent players from interacting with their inventory if they shouldn't be allowed to.
 */
public class InventoryMove extends Check {
	
	public InventoryMove() {
        super(CheckType.INVENTORY_MOVE);
    }
	
	public boolean check(final Player player, final InventoryData data, final IPlayerData pData, final InventoryConfig cc, final SlotType type) {
		
		boolean cancel = false;
		if (player.isBlocking() || player.isSprinting() || player.isDead() || player.isSneaking() || player.isSwimming()) {
			if (player.getGameMode() == GameMode.CREATIVE) {
			if (!cc.invMoveDisableCreative) {
				if (type == SlotType.QUICKBAR) {
					return false;
				} else {
				  data.invMoveVL += 1D;
				  cancel = executeActions(player, data.invMoveVL, 1D, pData.getGenericInstance(InventoryConfig.class).invMoveActionList).willCancel();
				}
			} else {
			  return false;
			}
			} else {
			data.invMoveVL += 1D;
			cancel = executeActions(player, data.invMoveVL, 1D, pData.getGenericInstance(InventoryConfig.class).invMoveActionList).willCancel();	
			}
		}
		return cancel;
	}
	
}
