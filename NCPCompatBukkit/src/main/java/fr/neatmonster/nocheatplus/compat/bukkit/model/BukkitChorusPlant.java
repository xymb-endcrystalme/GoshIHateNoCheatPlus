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

import java.util.Set;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;

import fr.neatmonster.nocheatplus.utilities.map.BlockCache;

public class BukkitChorusPlant implements BukkitShapeModel {

    @Override
    public double[] getShape(final BlockCache blockCache, 
            final World world, final int x, final int y, final int z) {

        final Block block = world.getBlockAt(x, y, z);
        
        double minX = Math.abs(block.getBoundingBox().getMinX()) - Math.abs((int) block.getBoundingBox().getMinX());
        double minY = Math.abs(block.getBoundingBox().getMinY()) - Math.abs((int) block.getBoundingBox().getMinY());
        double minZ = Math.abs(block.getBoundingBox().getMinZ()) - Math.abs((int) block.getBoundingBox().getMinZ());
        
        double maxX = Math.abs(block.getBoundingBox().getMaxX()) - Math.abs((int) block.getBoundingBox().getMaxX());
        double maxY = Math.abs(block.getBoundingBox().getMaxY()) - Math.abs((int) block.getBoundingBox().getMaxY());
        double maxZ = Math.abs(block.getBoundingBox().getMaxZ()) - Math.abs((int) block.getBoundingBox().getMaxZ());
        
        return new double[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    @Override
    public int getFakeData(final BlockCache blockCache, 
            final World world, final int x, final int y, final int z) {
        return 0;
    }

}
