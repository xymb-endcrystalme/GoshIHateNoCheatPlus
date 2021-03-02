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

import java.text.DecimalFormat;
import java.util.Collection;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import fr.neatmonster.nocheatplus.command.BaseCommand;
import fr.neatmonster.nocheatplus.compat.BridgeHealth;
import fr.neatmonster.nocheatplus.permissions.Permissions;
import fr.neatmonster.nocheatplus.players.DataManager;

public class InspectCommand extends BaseCommand {
    private static final DecimalFormat f1 = new DecimalFormat("#.#");

    public InspectCommand(JavaPlugin plugin) {
        super(plugin, "inspect", Permissions.COMMAND_INSPECT);
    }

    /* (non-Javadoc)
     * @see fr.neatmonster.nocheatplus.command.AbstractCommand#onCommand(org.bukkit.command.CommandSender, org.bukkit.command.Command, java.lang.String, java.lang.String[])
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            if (sender instanceof Player) {
                args = new String[]{args[0], sender.getName()};
            } else {
                sender.sendMessage(TAG + "Please specify a player to inspect.");
                return true;
            }
        }

        final ChatColor c1, c2, c3;
        if (sender instanceof Player) {
            c1 = ChatColor.GRAY;
            c2 = ChatColor.BOLD;
            c3 = ChatColor.RED;
        } else {
            c1 = c2 = c3 = null;
        }
        
        for (int i = 1; i < args.length; i++) {
            final Player player = DataManager.getPlayer(args[i].trim().toLowerCase());
            if (player == null) {
                sender.sendMessage(TAG + "Not online: " + c3 +""+ args[i]);
            } else {
                sender.sendMessage(getInspectMessage(player, c1, c2, c3));
            }
        }
        return true;
    }

    public static String getInspectMessage(final Player player, final ChatColor c1, final ChatColor c2, final ChatColor c3) {

        final StringBuilder builder = new StringBuilder(256);

        // More spaghetti.
        builder.append(TAG + c1 + "Status information for player: " + c3 + player.getName());

        if (player.isOnline()) {
            builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Is currently online.");
        }
        else builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Is offline.");

        if (player.isValid()) {
            builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Player is valid");
        }
        else builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Player is invalid");

        builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Current health: " + f1.format(BridgeHealth.getHealth(player)) + "/" + f1.format(BridgeHealth.getMaxHealth(player)));
        builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Current food level: " + player.getFoodLevel());
        builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Is in " + player.getGameMode() + " gamemode.");

        if (player.getExp() > 0f) {
            builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Experience Lvl: " + f1.format(player.getExpToLevel()) + "(exp=" + f1.format(player.getExp()) + ")");
        }

        if (player.isInsideVehicle()) {
            builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Is riding a vehicle (" + player.getVehicle().getType() +") at " + locString(player.getVehicle().getLocation()));
        }

        if (player.isDead()) {
            builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Currently dead.");
        }

        if (player.isOp()){
            builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Is Op!");
        }

      
        if (player.isFlying()) {
            builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Currently flying.");
        }

        if (player.getAllowFlight()) {
            builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Is allowed to fly.");
        }
        builder.append("\n "+ c1 + "" + c2 + "•" + c1 +" FlySpeed: " + player.getFlySpeed());
        builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " WalkSpeed: " + player.getWalkSpeed());

        // Potion effects.
        final Collection<PotionEffect> effects = player.getActivePotionEffects();
        if (!effects.isEmpty()) {
            builder.append("\n "+ c1 + "" + c2 + "•" +c1+ "Effects: ");
            for (final PotionEffect effect : effects) {
                builder.append(effect.getType() + " at " + effect.getAmplifier() +",");
            }
        }
        // TODO: is..sneaking,sprinting,blocking,
        // Finally the block location.
        final Location loc = player.getLocation();
        builder.append("\n "+ c1 + "" + c2 + "•" + c1 + " Position: " + locString(loc));
        return builder.toString();
    }

    private static final String locString(Location loc) {
        return loc.getWorld().getName() + "/" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    /* (non-Javadoc)
     * @see fr.neatmonster.nocheatplus.command.AbstractCommand#onTabComplete(org.bukkit.command.CommandSender, org.bukkit.command.Command, java.lang.String, java.lang.String[])
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
            String alias, String[] args) {
        // Complete players.
        return null;
    }



}
