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

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import fr.neatmonster.nocheatplus.checks.ViolationHistory;
import fr.neatmonster.nocheatplus.checks.ViolationHistory.ViolationLevel;
import fr.neatmonster.nocheatplus.command.BaseCommand;
import fr.neatmonster.nocheatplus.permissions.Permissions;
import fr.neatmonster.nocheatplus.players.DataManager;

public class InfoCommand extends BaseCommand {

	public InfoCommand(JavaPlugin plugin) {
		super(plugin, "info", Permissions.COMMAND_INFO);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length != 2 ) return false;
		handleInfoCommand(sender, args[1]);
		return true;
	}
	
    /**
     * Handle the '/nocheatplus info' command.
     * 
     * @param sender
     *            the sender
     * @param playerName
     *            the player name
     * @return true, if successful
     */
    private void handleInfoCommand(final CommandSender sender, String playerName) {

    	final String cG, cR, cGO, bold, italicbold; 
        if (sender instanceof Player) {
            cG = ChatColor.GRAY.toString(); 
            cR = ChatColor.RED.toString();
            cGO = ChatColor.GOLD.toString();
            bold = ChatColor.BOLD.toString();
            italicbold = ChatColor.BOLD + "" + ChatColor.ITALIC;
        }
        else cG = cR = cGO = bold = italicbold = "";

    	final Player player = DataManager.getPlayer(playerName);
    	if (player != null) playerName = player.getName();
    	
    	final ViolationHistory history = ViolationHistory.getHistory(playerName, false);
    	final boolean known = player != null || history != null;
    	if (history == null){
    		sender.sendMessage((sender instanceof Player ? TAG : "") + "No entries for " + cR + playerName + cG + "'s violations " + ( known? "" : "(exact spelling?)") + ".");
    		return;
    	}
    	
        final DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
        final ViolationLevel[] violations = history.getViolationLevels();
        if (violations.length > 0) {
            sender.sendMessage((sender instanceof Player ? TAG : "") + "Displaying " + cR + playerName + cG + "'s violations: ");
            for (final ViolationLevel violationLevel : violations) {

                final long time = violationLevel.time;
                final String[] parts = violationLevel.check.split("\\.");
                final String check = parts[parts.length - 1].toLowerCase();
                final String parent = parts[parts.length - 2].toLowerCase();
                final long sumVL = Math.round(violationLevel.sumVL);
                final long maxVL = Math.round(violationLevel.maxVL);
                final long avVl  = Math.round(violationLevel.sumVL / (double) violationLevel.nVL);
                sender.sendMessage(
                    cG + bold +"[" + cG + dateFormat.format(new Date(time)) + bold + "] " + cGO + italicbold + parent + "." + check  
                    +cG+bold + "\n• "+ cG + "VLs Sum: " + cR + sumVL  
                    +cG+bold + "\n• "+ cG + "VLs amount: " + cR + violationLevel.nVL 
                    +cG+bold + "\n• "+ cG + "Average VL: " + cR + avVl 
                    +cG+bold + "\n• "+ cG + "Max VL: " + cR + maxVL);
            }
        } 
        else sender.sendMessage(TAG + "No violations to display for player " + cR + playerName);
        
    }

	/* (non-Javadoc)
	 * @see fr.neatmonster.nocheatplus.command.AbstractCommand#onTabComplete(org.bukkit.command.CommandSender, org.bukkit.command.Command, java.lang.String, java.lang.String[])
	 */
	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		// Fill in players.
		return null;
	}
	
}
