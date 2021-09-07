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
package fr.neatmonster.nocheatplus.checks.net.protocollib;

import java.util.Arrays;
import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.events.PacketContainer;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.net.NetConfig;
import fr.neatmonster.nocheatplus.checks.net.NetData;
import fr.neatmonster.nocheatplus.checks.net.WrongPacket;
import fr.neatmonster.nocheatplus.checks.net.model.DataPacketFlying;
import fr.neatmonster.nocheatplus.config.ConfPaths;
import fr.neatmonster.nocheatplus.config.ConfigManager;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.TickTask;

public class WrongPacketAdapter extends BaseAdapter {

    private final WrongPacket wrongPacket = new WrongPacket(); 

    public WrongPacketAdapter(Plugin plugin) {
        super(plugin, ListenerPriority.LOW, PacketType.Play.Client.ABILITIES);
        if (ConfigManager.isTrueForAnyConfig(ConfPaths.NET_WRONGPACKET_ACTIVE)) {
            NCPAPIProvider.getNoCheatPlusAPI().addFeatureTags("checks", Arrays.asList(WrongPacket.class.getSimpleName()));
        }
    }

    @Override
    public void onPacketReceiving(final PacketEvent event) {
        handleAbilityPacket(event);
    }

    private void handleAbilityPacket(PacketEvent event) {

        final Player player = event.getPlayer();
        try {
            if (event.isPlayerTemporary()) return;
        } 
        catch(NoSuchMethodError e) {
            if (player == null) return;
            if (DataManager.getPlayerDataSafe(event.getPlayer()) == null) return;
        }

        if (player == null) {
            counters.add(ProtocolLibComponent.idNullPlayer, 1);
            return;
        }

        final IPlayerData pData = DataManager.getPlayerData(player);
        final NetData data = pData.getGenericInstance(NetData.class);
        final NetConfig cc = pData.getGenericInstance(NetConfig.class);
        // Only interested in Abilities packet for now
        data.clientSentAbilityPacket = false;
        if (event.getPacketType() == PacketType.Play.Client.ABILITIES) {
            data.clientSentAbilityPacket = true;
            if (pData.isCheckActive(CheckType.NET_WRONGPACKET, player) 
                && wrongPacket.check(player, data, cc, data.clientSentAbilityPacket, pData)) {
                event.setCancelled(true); 
                // TODO: Force set false?
            }
        }
    }
}