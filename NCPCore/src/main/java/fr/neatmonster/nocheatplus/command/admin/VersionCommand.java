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
package fr.neatmonster.nocheatplus.command.admin;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.command.BaseCommand;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.hooks.NCPHook;
import fr.neatmonster.nocheatplus.hooks.NCPHookManager;
import fr.neatmonster.nocheatplus.permissions.Permissions;
import fr.neatmonster.nocheatplus.utilities.StringUtil;

public class VersionCommand extends BaseCommand{

    public VersionCommand(JavaPlugin plugin) {
        super(plugin, "version", Permissions.COMMAND_VERSION, new String[]{"versions", "ver"});
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> lines = getVersionInfo();
        sender.sendMessage(lines.toArray(new String[lines.size()]));
        return true;
    }

    public static List<String> getVersionInfo() {
        final List<String> lines = new LinkedList<String>();
        final MCAccess mcAccess = NCPAPIProvider.getNoCheatPlusAPI().getGenericInstance(MCAccess.class);
        lines.addAll(Arrays.asList(new String[]{
                ChatColor.RED +""+ ChatColor.BOLD + "»Version information«" + ChatColor.GRAY,
                ChatColor.GOLD +""+ ChatColor.BOLD + "Server:" + ChatColor.GRAY,
                ChatColor.GRAY + alt(Bukkit.getServer().getVersion()),
                ChatColor.GRAY +""+ ChatColor.ITALIC + "Detected: " + ChatColor.GRAY + alt(ServerVersion.getMinecraftVersion()),
                ChatColor.GOLD +""+ ChatColor.BOLD + "NoCheatPlus:" + ChatColor.GRAY,
                ChatColor.GRAY +""+ ChatColor.ITALIC + "Plugin: "+ ChatColor.GRAY + alt(Bukkit.getPluginManager().getPlugin("NoCheatPlus").getDescription().getVersion()),
                ChatColor.GRAY +""+ ChatColor.ITALIC +  "MCAccess: " + ChatColor.GRAY + alt(mcAccess.getMCVersion() + " / " + mcAccess.getServerVersionTag()),
        }));
        final Map<String, Set<String>> featureTags = NCPAPIProvider.getNoCheatPlusAPI().getAllFeatureTags();
        if (!featureTags.isEmpty()) {
            final List<String> features = new LinkedList<String>();
            // Add present features.
            for (final Entry<String, Set<String>> entry : featureTags.entrySet()) {
                features.add(alt(ChatColor.GRAY + "  " + entry.getKey() + ": " + StringUtil.join(entry.getValue(), ChatColor.WHITE + " | " + ChatColor.GRAY)));
            }
            // Sort and add.
            Collections.sort(features, String.CASE_INSENSITIVE_ORDER);
            features.add(0, ChatColor.GOLD +""+ ChatColor.BOLD +"Features:");
            lines.addAll(features);
        }
        final Collection<NCPHook> hooks = NCPHookManager.getAllHooks();
        if (!hooks.isEmpty()){
            final List<String> fullNames = new LinkedList<String>();
            for (final NCPHook hook : hooks){
                fullNames.add(alt(hook.getHookName() + " " + hook.getHookVersion()));
            }
            Collections.sort(fullNames, String.CASE_INSENSITIVE_ORDER);
            lines.add(ChatColor.GOLD +""+ ChatColor.BOLD + "Hooks: " + ChatColor.GRAY + StringUtil.join(fullNames, ChatColor.WHITE + " | " + ChatColor.GRAY));
        }
        final List<String> relatedPlugins = new LinkedList<String>();
        for (final String name : new String[]{"CompatNoCheatPlus", "ProtocolLib", "ViaVersion", "ProtocolSupport", "PNCP", "NTAC"}) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            if (plugin != null) {
                relatedPlugins.add(alt(plugin.getDescription().getFullName()));
            }
        }
        if (!relatedPlugins.isEmpty()) {
            lines.add(ChatColor.RED +""+ ChatColor.BOLD + "»Related Plugins«" + ChatColor.GRAY);
            lines.add(ChatColor.GRAY +""+ StringUtil.join(relatedPlugins, ChatColor.WHITE + " | " + ChatColor.GRAY));
        }
        return lines;
    }

    private static String alt(String x) {
        return x.replace('(', '~').replace(')', '~');
    }

}
