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
package fr.neatmonster.nocheatplus.checks.net;

import org.bukkit.entity.Player;
import org.bukkit.GameMode;

import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.players.IPlayerData;

/**
 * A check to see if clients send (accidentally or on purpose) a fly ability packet if 
 * they aren't allowed to fly. 
 * In the future, this can be the central location for any kind of invalid packet sending/alteration (Similarly to SurvivalFly)
 */
public class WrongPacket extends Check {

    
    public WrongPacket() {
        super(CheckType.NET_WRONGPACKET);
    }


    /**
     * Checks a player
     * 
     * @param player
     * @param data
     * @param sentFlyPacket
     * @param cc
     * @return if to cancel
     */
    public boolean check(final Player player, final NetData data, final NetConfig cc, boolean sentFlyPacket, final IPlayerData pData) {

        boolean cancel = false;
        final boolean creativeOrSpect = player.getGameMode() == GameMode.CREATIVE 
                                        || player.getGameMode() == GameMode.SPECTATOR;

        if (sentFlyPacket && !player.getAllowFlight() || data.isIllegalPacket) { 
            
            // Skip should be obvious :)
            if (!creativeOrSpect) {
                
                data.wrongPacketVL++ ;
                if (executeActions(player, data.wrongPacketVL, 1, cc.wrongPacketActions).willCancel()) {
                    cancel = true;
                }
            }
        }
        return cancel;
    }
}