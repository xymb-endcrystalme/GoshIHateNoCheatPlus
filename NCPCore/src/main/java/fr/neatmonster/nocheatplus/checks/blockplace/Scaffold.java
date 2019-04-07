package fr.neatmonster.nocheatplus.checks.blockplace;

import java.util.List;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.players.IPlayerData;

public class Scaffold extends Check {
	
	public Scaffold() {
        super(CheckType.BLOCKPLACE_SCAFFOLD);
    }

	public boolean check(Player player, Block targetBlock, List<Block> lastTarget, BlockFace blockFace, final IPlayerData pData, final BlockPlaceData data) {
		BlockFace face = null;
		boolean cancel = false;
		
		if (lastTarget.size() > 1) {
			face = lastTarget.get(1).getFace(lastTarget.get(0));
		}

		if (blockFace != face) {
			cancel = executeActions(player, data.scaffoldVL, 1D, pData.getGenericInstance(BlockPlaceConfig.class).scaffoldActions).willCancel();
			data.scaffoldVL += 1D;
		}
		return cancel;
	}

}
