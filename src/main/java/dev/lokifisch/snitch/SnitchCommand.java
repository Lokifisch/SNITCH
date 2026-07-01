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
import java.util.Map;

/**
 * /snitch list
 * /snitch add    <mod> <translation.key>
 * /snitch remove <mod> <translation.key>
 * /snitch removemod <mod>
 * /snitch scan   [player]
 * /snitch reload
 * /snitch info
 * /snitch update [check]
 */
public final class SnitchCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            Arrays.asList("list", "add", "remove", "removemod", "scan", "reload", "info", "update");

    private final SnitchPlugin plugin;

    public SnitchCommand(SnitchPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "reload"    -> reload(sender);
            case "list"      -> list(sender);
            case "add"       -> add(sender, args);
            case "remove"    -> remove(sender, args);
            case "removemod" -> removeMod(sender, args);
            case "scan"      -> scan(sender, args);
            case "info"      -> info(sender);
            case "update"    -> update(sender, args);
            default          -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage("§e[SNITCH] commands:");
        s.sendMessage("§7  /snitch list");
        s.sendMessage("§7  /snitch add <mod> <translation.key>");
        s.sendMessage("§7  /snitch remove <mod> <translation.key>");
        s.sendMessage("§7  /snitch removemod <mod>");
        s.sendMessage("§7  /snitch scan [player]");
        s.sendMessage("§7  /snitch reload");
        s.sendMessage("§7  /snitch info");
        s.sendMessage("§7  /snitch update [check]");
    }

    private void reload(CommandSender s) {
        plugin.reloadSnitchConfig();
        s.sendMessage("§a[SNITCH] Config reloaded. "
                + plugin.config().mods.size() + " mods, "
                + plugin.config().keys.size() + " keys.");
    }

    private void list(CommandSender s) {
        List<SnitchConfig.Mod> mods = plugin.config().mods;
        if (mods.isEmpty()) {
            s.sendMessage("§e[SNITCH] No mods configured.");
            return;
        }
        s.sendMessage("§e[SNITCH] " + mods.size() + " mod(s), "
                + plugin.config().keys.size() + " key(s) total:");
        for (SnitchConfig.Mod m : mods) {
            s.sendMessage("§f  " + m.name() + "§8:");
            for (String k : m.keys()) {
                s.sendMessage("§7    - " + k);
            }
        }
    }

    private void add(CommandSender s, String[] args) {
        if (args.length < 3) {
            s.sendMessage("§c[SNITCH] Usage: /snitch add <mod> <translation.key>");
            return;
        }
        String mod = args[1];
        String key = args[2];
        if (!plugin.addKey(mod, key)) {
            s.sendMessage("§c[SNITCH] Invalid mod name (no dots or spaces): " + mod);
            return;
        }
        s.sendMessage("§a[SNITCH] Added key §7" + key + " §ato §f" + mod
                + " §a(" + plugin.config().keys.size() + " keys total).");
    }

    private void remove(CommandSender s, String[] args) {
        if (args.length < 3) {
            s.sendMessage("§c[SNITCH] Usage: /snitch remove <mod> <translation.key>");
            return;
        }
        String mod = args[1];
        String key = args[2];
        if (!plugin.removeKey(mod, key)) {
            s.sendMessage("§c[SNITCH] Key not found: " + key + " under mod " + mod);
            return;
        }
        s.sendMessage("§a[SNITCH] Removed key §7" + key + " §afrom §f" + mod + "§a.");
    }

    private void removeMod(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage("§c[SNITCH] Usage: /snitch removemod <mod>");
            return;
        }
        String mod = args[1];
        if (!plugin.removeMod(mod)) {
            s.sendMessage("§c[SNITCH] Mod not found: " + mod);
            return;
        }
        s.sendMessage("§a[SNITCH] Removed mod §f" + mod
                + " §a(" + plugin.config().keys.size() + " keys remaining).");
    }

    private void scan(CommandSender s, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) { s.sendMessage("§c[SNITCH] Player not found: " + args[1]); return; }
        } else if (s instanceof Player p) {
            target = p;
        } else {
            s.sendMessage("§c[SNITCH] Console must specify a player: /snitch scan <player>");
            return;
        }
        plugin.probes().scan(target);
        s.sendMessage("§a[SNITCH] Scanning " + target.getName() + ".");
    }

    private void info(CommandSender s) {
        SnitchConfig cfg = plugin.config();
        s.sendMessage("§e[SNITCH] §7server=" + cfg.serverName
                + " mods=" + cfg.mods.size()
                + " keys=" + cfg.keys.size()
                + " delay=" + cfg.delayTicks + "t");
        cfg.flagActions.forEach((flag, action) ->
                s.sendMessage("§7  flag " + flag + " → " + action));
    }

    private void update(CommandSender s, String[] args) {
        UpdateChecker checker = plugin.updateChecker();
        if (args.length >= 2 && args[1].equalsIgnoreCase("check")) {
            s.sendMessage("§e[SNITCH] Checking for updates...");
            checker.checkAsync(plugin.config().updateAutoUpdate, () -> reportUpdateStatus(s, checker));
            return;
        }
        reportUpdateStatus(s, checker);
    }

    private void reportUpdateStatus(CommandSender s, UpdateChecker checker) {
        if (!checker.isUpdateAvailable()) {
            s.sendMessage("§a[SNITCH] Running the latest version (v" + checker.getCurrentVersion() + ").");
            return;
        }
        s.sendMessage("§e[SNITCH] Update available: §fv" + checker.getLatestVersion()
                + " §7(currently v" + checker.getCurrentVersion() + ")");
        s.sendMessage("§7  " + checker.getReleaseUrl());
        if (checker.isStaged()) {
            s.sendMessage("§a[SNITCH] Already downloaded - restart the server to apply it.");
        } else {
            s.sendMessage("§7Enable §fupdate-checker.auto-update §7in config.yml to stage it automatically.");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], SUBS, out);
            return out;
        }
        String sub = args[0].toLowerCase();
        List<String> modNames = plugin.config().mods.stream()
                .map(SnitchConfig.Mod::name).toList();

        if (args.length == 2) {
            if (sub.equals("scan")) {
                List<String> names = new ArrayList<>();
                plugin.getServer().getOnlinePlayers().forEach(p -> names.add(p.getName()));
                StringUtil.copyPartialMatches(args[1], names, out);
            } else if (sub.equals("remove") || sub.equals("removemod") || sub.equals("add")) {
                StringUtil.copyPartialMatches(args[1], modNames, out);
            } else if (sub.equals("update")) {
                StringUtil.copyPartialMatches(args[1], List.of("check"), out);
            }
            return out;
        }

        // arg 3: for "remove <mod> <key>" — complete existing keys for that mod
        if (args.length == 3 && sub.equals("remove")) {
            String modName = args[1];
            plugin.config().mods.stream()
                    .filter(m -> m.name().equals(modName))
                    .findFirst()
                    .ifPresent(m -> StringUtil.copyPartialMatches(args[2], m.keys(), out));
        }
        return out;
    }
}
