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
package fr.neatmonster.nocheatplus.checks.fight;

import java.util.LinkedList;
import java.util.List;

import org.bukkit.entity.Player;

import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.inventory.InventoryData;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.TickTask;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.utilities.InventoryUtil;

/**
 * A check to see if players try to attack entities while performing something else
 * (i.e.: while blocking)
 */
public class ImpossibleHit extends Check {


    /**
     * Instantiates a new impossiblehit check.
     */
    public ImpossibleHit() {
        super(CheckType.FIGHT_IMPOSSIBLEHIT);
    }


    /**
     * Checks a player.
     * 
     * @param player
     * @param data
     * @param cc
     * @param pData
     * @param resetActiveItem Whether the resetActiveitem option in Sf is enabled
     * @return true, if successful
     */
    public boolean check(final Player player, final FightData data, final FightConfig cc, final IPlayerData pData, final boolean resetActiveItem) {

        boolean cancel = false;
        boolean violation = false;
        final long currentEventTime = System.currentTimeMillis();
        List<String> tags = new LinkedList<String>();
        final MovingData mData = pData.getGenericInstance(MovingData.class);
        final InventoryData iData = pData.getGenericInstance(InventoryData.class);

        
        // Attacking and interacting, at the same time
        if (data.interactAttack && currentEventTime - data.lastInteractTime < 65L) {
            violation = true;
            data.interactAttack = false;
            tags.add("atk+interact");
            // This should cover blockplacing and blockbreaking as well.
            // Observed: Killauras tend to also trigger Block_interact_direction a lot here, might want to exploit that... :p
        }
        // Clicking and attacking at the same time
        else if (InventoryUtil.couldHaveInventoryOpen(player)) {
            violation = true;
            tags.add("atk+invclick");
        }
        // Blocking/Using item and attacking
        // TODO: If reset-active item is enabled, actually reset the item (see survivalFly.hDistanceAfterFailure())
        else if (mData.isusingitem || player.isBlocking()) {
            violation = true;
            tags.add("atk+using/blocking");
        }
        // (While dead is canceled silentely, while sleeping shouldn't be possible...)
        // TODO: Is there more to prevent?
        // TODO: Might also want to prevent on packet-level

        // Handle violations 
        if (violation) {
            data.impossibleHitVL += 1D;
            final ViolationData vd = new ViolationData(this, player, data.impossibleHitVL, 1D, cc.impossibleHitActions);
            if (vd.needsParameters()) vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
            cancel = executeActions(vd).willCancel();
        }
        // Cooldown
        else {
            data.impossibleHitVL *= 0.96D;
        }
        return cancel;
    }
}
