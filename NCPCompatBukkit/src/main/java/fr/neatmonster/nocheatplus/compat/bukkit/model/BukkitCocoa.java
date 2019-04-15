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
package fr.neatmonster.nocheatplus.compat.bukkit.model;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Cocoa;

import fr.neatmonster.nocheatplus.utilities.map.BlockCache;

public class BukkitCocoa implements BukkitShapeModel {

    @Override
    public double[] getShape(final BlockCache blockCache, 
            final World world, final int x, final int y, final int z) {
        final Block block = world.getBlockAt(x, y, z);
        final BlockState state = block.getState();
        final BlockData blockData = state.getBlockData();

        if (blockData instanceof Cocoa) {
            final Cocoa cocoa = (Cocoa) blockData;
            switch (cocoa.getAge()) {
                case 0: // .625 .4375 .0625 max: .375 .75 .3125
                    return new double[] {0.625, 0.4375, 0.0625, 0.375, 0.75, 0.3125};
                case 1: // .6875 .3125 .0625 max: .3125 .75 .4375
                    return new double[] {0.6875, 0.3125, 0.0625, 0.3125, 0.75, 0.4375};
                case 2: // .75 .1875 .0625 max: .25 .75 .5625
                	return new double[] {0.75, 0.1875, 0.0625, 0.25, 0.75, 0.5625};
                default:
                    break;
            }
        }
        return new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
    }

    @Override
    public int getFakeData(final BlockCache blockCache, 
            final World world, final int x, final int y, final int z) {
        return 0;
    }

}
