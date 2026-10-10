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
 * /snitch gui
 */
public final class SnitchCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            Arrays.asList("list", "add", "remove", "removemod", "scan", "reload", "info", "update", "gui");

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
            case "gui"       -> gui(sender);
            default          -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(plugin.prefix() + "§ecommands:");
        s.sendMessage("§7  /snitch list");
        s.sendMessage("§7  /snitch add <mod> <translation.key>");
        s.sendMessage("§7  /snitch remove <mod> <translation.key>");
        s.sendMessage("§7  /snitch removemod <mod>");
        s.sendMessage("§7  /snitch scan [player]");
        s.sendMessage("§7  /snitch reload");
        s.sendMessage("§7  /snitch info");
        s.sendMessage("§7  /snitch update [check]");
        s.sendMessage("§7  /snitch gui");
    }

    private void gui(CommandSender s) {
        if (s instanceof Player p) plugin.gui().open(p);
        else s.sendMessage(plugin.prefix() + "§cThe GUI is only available in game.");
    }

    private void reload(CommandSender s) {
        plugin.reloadSnitchConfig();
        s.sendMessage(plugin.prefix() + "§aConfig reloaded. "
                + plugin.config().mods.size() + " mods, "
                + plugin.config().keys.size() + " keys.");
    }

    private void list(CommandSender s) {
        List<SnitchConfig.Mod> mods = plugin.config().mods;
        if (mods.isEmpty()) {
            s.sendMessage(plugin.prefix() + "§eNo mods configured.");
            return;
        }
        s.sendMessage(plugin.prefix() + "§e" + mods.size() + " mod(s), "
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
            s.sendMessage(plugin.prefix() + "§cUsage: /snitch add <mod> <translation.key>");
            return;
        }
        String mod = args[1];
        String key = args[2];
        if (!plugin.addKey(mod, key)) {
            s.sendMessage(plugin.prefix() + "§cInvalid mod name or key (letters, digits, _ . : - only).");
            return;
        }
        s.sendMessage(plugin.prefix() + "§aAdded key §7" + key + " §ato §f" + mod
                + " §a(" + plugin.config().keys.size() + " keys total).");
    }

    private void remove(CommandSender s, String[] args) {
        if (args.length < 3) {
            s.sendMessage(plugin.prefix() + "§cUsage: /snitch remove <mod> <translation.key>");
            return;
        }
        String mod = args[1];
        String key = args[2];
        if (!plugin.removeKey(mod, key)) {
            s.sendMessage(plugin.prefix() + "§cKey not found: " + key + " under mod " + mod);
            return;
        }
        s.sendMessage(plugin.prefix() + "§aRemoved key §7" + key + " §afrom §f" + mod + "§a.");
    }

    private void removeMod(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(plugin.prefix() + "§cUsage: /snitch removemod <mod>");
            return;
        }
        String mod = args[1];
        if (!plugin.removeMod(mod)) {
            s.sendMessage(plugin.prefix() + "§cMod not found: " + mod);
            return;
        }
        s.sendMessage(plugin.prefix() + "§aRemoved mod §f" + mod
                + " §a(" + plugin.config().keys.size() + " keys remaining).");
    }

    private void scan(CommandSender s, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) { s.sendMessage(plugin.prefix() + "§cPlayer not found: " + args[1]); return; }
        } else if (s instanceof Player p) {
            target = p;
        } else {
            s.sendMessage(plugin.prefix() + "§cConsole must specify a player: /snitch scan <player>");
            return;
        }
        plugin.probes().scan(target);
        s.sendMessage(plugin.prefix() + "§aScanning " + target.getName() + ".");
    }

    private void info(CommandSender s) {
        SnitchConfig cfg = plugin.config();
        s.sendMessage(plugin.prefix() + "§e§7server=" + cfg.serverName
                + " mods=" + cfg.mods.size()
                + " keys=" + cfg.keys.size()
                + " delay=" + cfg.delayTicks + "t");
        cfg.flagActions.forEach((flag, action) ->
                s.sendMessage("§7  flag " + flag + " → " + action));
    }

    private void update(CommandSender s, String[] args) {
        UpdateChecker checker = plugin.updateChecker();
        if (args.length >= 2 && args[1].equalsIgnoreCase("check")) {
            s.sendMessage(plugin.prefix() + "§eChecking for updates...");
            checker.checkAsync(plugin.config().updateAutoUpdate, () -> reportUpdateStatus(s, checker));
            return;
        }
        reportUpdateStatus(s, checker);
    }

    private void reportUpdateStatus(CommandSender s, UpdateChecker checker) {
        if (!checker.isUpdateAvailable()) {
            s.sendMessage(plugin.prefix() + "§aRunning the latest version (v" + checker.getCurrentVersion() + ").");
            return;
        }
        s.sendMessage(plugin.prefix() + "§eUpdate available: §fv" + checker.getLatestVersion()
                + " §7(currently v" + checker.getCurrentVersion() + ")");
        s.sendMessage("§7  " + checker.getReleaseUrl());
        if (checker.isStaged()) {
            s.sendMessage(plugin.prefix() + "§aAlready downloaded - restart the server to apply it.");
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
