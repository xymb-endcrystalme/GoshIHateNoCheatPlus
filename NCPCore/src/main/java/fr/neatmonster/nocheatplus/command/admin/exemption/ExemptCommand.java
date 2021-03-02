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
package fr.neatmonster.nocheatplus.command.admin.exemption;

import java.util.Arrays;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;

import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.command.BaseCommand;
import fr.neatmonster.nocheatplus.command.CommandUtil;
import fr.neatmonster.nocheatplus.hooks.NCPExemptionManager;
import fr.neatmonster.nocheatplus.permissions.Permissions;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.utilities.StringUtil;

public class ExemptCommand extends BaseCommand {

    public ExemptCommand(JavaPlugin plugin) {
        super(plugin, "exempt", Permissions.COMMAND_EXEMPT);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        final ChatColor c1, c2, c3, c4, c5, c6, c7;
        if (sender instanceof Player) {
            c1 = ChatColor.GRAY;
            c2 = ChatColor.BOLD;
            c3 = ChatColor.RED;
            c4 = ChatColor.ITALIC;
            c5 = ChatColor.GOLD;
            c6 = ChatColor.WHITE;
            c7 = ChatColor.YELLOW;
        } else {
            c1 = c2 = c3 = c4 = c5 = c6 = c7 = null;
        }

        // TODO: Reduce copy and paste by introducing some super class.
        if (args.length < 2 || args.length > 3) {
            return false;
        }
        String playerName = args[1];
        final CheckType checkType;
        if (args.length == 3){
            try{
                checkType = CheckType.valueOf(args[2].toUpperCase().replace('-', '_').replace('.', '_'));
            } catch (Exception e){
                sender.sendMessage(TAG + "Could not interpret: " + c3 +""+ args[2]);
                sender.sendMessage(TAG + "Check type should be one of: "  + c3 +""+ StringUtil.join(Arrays.asList(CheckType.values()), c6 + " | " + c3));
                return true;
            }
        }
        else {
            checkType = CheckType.ALL;
        }

        final Player player = DataManager.getPlayer(playerName);
        if (player == null){
            sender.sendMessage(TAG + "Player not online: " + c3+""+ playerName);
            return true;
        }
        else {
            playerName = player.getName();
        }
        NCPExemptionManager.exemptPermanently(player, checkType);
        sender.sendMessage(TAG + "Player " + c3 +""+ playerName + c1 + " is now exempted from: " + c3 +""+ checkType); 
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args)
    {
        // At least complete CheckType
        if (args.length == 3) {
            return CommandUtil.getCheckTypeTabMatches(args[2]);
        }
        return null;
    }

    /* (non-Javadoc)
     * @see fr.neatmonster.nocheatplus.command.AbstractCommand#testPermission(org.bukkit.command.CommandSender, org.bukkit.command.Command, java.lang.String, java.lang.String[])
     */
    @Override
    public boolean testPermission(CommandSender sender, Command command, String alias, String[] args) {
        return super.testPermission(sender, command, alias, args) 
                || args.length >= 2 && args[1].trim().equalsIgnoreCase(sender.getName()) 
                && sender.hasPermission(Permissions.COMMAND_EXEMPT_SELF.getBukkitPermission());
    }

}
