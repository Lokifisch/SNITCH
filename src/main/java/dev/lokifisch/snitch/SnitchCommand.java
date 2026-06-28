package dev.lokifisch.snitch;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** /snitch reload | list | add <mod> <key> | remove <mod> | scan [player] | info */
public final class SnitchCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            Arrays.asList("reload", "list", "add", "remove", "scan", "info");

    private final SnitchPlugin plugin;

    public SnitchCommand(SnitchPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> reload(sender);
            case "list" -> list(sender);
            case "add" -> add(sender, args);
            case "remove" -> remove(sender, args);
            case "scan" -> scan(sender, args);
            case "info" -> info(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage("§e[SNITCH] commands:");
        sender.sendMessage("§7  /snitch list §8- §7show all probe keys");
        sender.sendMessage("§7  /snitch add <mod> <translation.key> §8- §7add a key");
        sender.sendMessage("§7  /snitch remove <mod> §8- §7remove a key");
        sender.sendMessage("§7  /snitch scan [player] §8- §7scan now");
        sender.sendMessage("§7  /snitch reload §8- §7reload config");
        sender.sendMessage("§7  /snitch info §8- §7show settings");
    }

    private void reload(CommandSender sender) {
        plugin.reloadSnitchConfig();
        sender.sendMessage("§a[SNITCH] Config reloaded. "
                + plugin.config().keys.size() + " keys loaded.");
    }

    private void list(CommandSender sender) {
        List<SnitchConfig.Key> keys = plugin.config().keys;
        if (keys.isEmpty()) {
            sender.sendMessage("§e[SNITCH] No probe keys configured.");
            return;
        }
        sender.sendMessage("§e[SNITCH] " + keys.size() + " probe key(s):");
        for (SnitchConfig.Key k : keys) {
            sender.sendMessage("§7  - §f" + k.mod() + " §8= §7" + k.translationKey());
        }
    }

    private void add(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c[SNITCH] Usage: /snitch add <mod> <translation.key>");
            return;
        }
        String mod = args[1];
        String key = args[2];
        if (!plugin.addKey(mod, key)) {
            sender.sendMessage("§c[SNITCH] Invalid mod name (no dots or spaces): " + mod);
            return;
        }
        sender.sendMessage("§a[SNITCH] Added §f" + mod + " §a= §7" + key
                + " §a(" + plugin.config().keys.size() + " total).");
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[SNITCH] Usage: /snitch remove <mod>");
            return;
        }
        String mod = args[1];
        if (!plugin.removeKey(mod)) {
            sender.sendMessage("§c[SNITCH] No such key: " + mod);
            return;
        }
        sender.sendMessage("§a[SNITCH] Removed §f" + mod
                + " §a(" + plugin.config().keys.size() + " left).");
    }

    private void scan(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§c[SNITCH] Player not found: " + args[1]);
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("§c[SNITCH] Console must specify a player: /snitch scan <player>");
            return;
        }
        plugin.probes().scan(target);
        sender.sendMessage("§a[SNITCH] Scanning " + target.getName()
                + " (an in-game scan may briefly flash a sign editor).");
    }

    private void info(CommandSender sender) {
        SnitchConfig cfg = plugin.config();
        sender.sendMessage("§e[SNITCH] §7keys=" + cfg.keys.size()
                + " delay=" + cfg.delayTicks + "t"
                + " mode=" + actionString(cfg)
                + " strikes=" + cfg.strikesBeforeAction);
    }

    private static String actionString(SnitchConfig cfg) {
        List<String> parts = new ArrayList<>();
        if (cfg.actionLog) parts.add("LOG");
        if (cfg.actionKick) parts.add("KICK");
        if (cfg.actionCommand) parts.add("COMMAND");
        return String.join("+", parts);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], SUBS, out);
            return out;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("scan")) {
                List<String> names = new ArrayList<>();
                plugin.getServer().getOnlinePlayers().forEach(p -> names.add(p.getName()));
                StringUtil.copyPartialMatches(args[1], names, out);
            } else if (sub.equals("remove")) {
                List<String> mods = new ArrayList<>();
                plugin.config().keys.forEach(k -> mods.add(k.mod()));
                StringUtil.copyPartialMatches(args[1], mods, out);
            }
            return out;
        }
        return List.of();
    }
}
