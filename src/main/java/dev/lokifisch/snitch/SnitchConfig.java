package dev.lokifisch.snitch;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable snapshot of config.yml, rebuilt on every reload.
 */
public final class SnitchConfig {

    public enum FlagAction { LOG, KICK, BAN }

    /** Per-mod grouping (used by list/add/remove commands). */
    public record Mod(String name, List<String> keys) {}

    /** Flat (mod, key) pair — used by ProbeService to fill sign lines. */
    public record Key(String mod, String translationKey) {}

    public final String serverName;

    public final int delayTicks;
    public final int rescanIntervalTicks;
    public final int probeYOffset;
    public final int closeDelayTicks;
    public final int revertDelayTicks;
    public final int sessionTimeoutTicks;

    /** flag count → action. Any count not present defaults to LOG. */
    public final Map<Integer, FlagAction> flagActions;

    public final String kickMessage;
    public final String banMessage;
    public final List<String> commands;

    public final boolean logConsole;
    public final boolean logCleanPlayers;

    public final List<Mod> mods;
    public final List<Key> keys;

    public SnitchConfig(FileConfiguration c) {
        this.serverName = c.getString("server-name", "My Server");

        this.delayTicks = Math.max(0, c.getInt("detection.delay-ticks", 40));
        this.rescanIntervalTicks = Math.max(0, c.getInt("detection.rescan-interval-ticks", 0));
        this.probeYOffset = c.getInt("detection.probe-y-offset", -3);
        this.closeDelayTicks = Math.max(1, c.getInt("detection.close-delay-ticks", 2));
        this.revertDelayTicks = Math.max(1, c.getInt("detection.revert-delay-ticks", 4));
        this.sessionTimeoutTicks = Math.max(20, c.getInt("detection.session-timeout-ticks", 100));

        this.flagActions = new TreeMap<>();
        ConfigurationSection flags = c.getConfigurationSection("action.flags");
        if (flags != null) {
            for (String key : flags.getKeys(false)) {
                try {
                    int count = Integer.parseInt(key);
                    String raw = flags.getString(key, "LOG").toUpperCase(Locale.ROOT);
                    this.flagActions.put(count, FlagAction.valueOf(raw));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        if (this.flagActions.isEmpty()) {
            this.flagActions.put(1, FlagAction.KICK);
            this.flagActions.put(2, FlagAction.BAN);
        }

        this.kickMessage = c.getString("action.kick-message",
                "&cYou have been kicked from &e%server%&c: &f%mod% &cis not allowed.");
        this.banMessage = c.getString("action.ban-message",
                "&cYou have been permanently banned from &e%server%&c for using &f%mod%&c.");
        this.commands = c.getStringList("action.commands");

        this.logConsole = c.getBoolean("logging.console", true);
        this.logCleanPlayers = c.getBoolean("logging.log-clean-players", false);

        this.mods = new ArrayList<>();
        this.keys = new ArrayList<>();
        ConfigurationSection modsSection = c.getConfigurationSection("mods");
        if (modsSection != null) {
            for (String modName : modsSection.getKeys(false)) {
                List<String> modKeys = modsSection.getStringList(modName);
                List<String> valid = new ArrayList<>();
                for (String k : modKeys) {
                    if (k != null && !k.isBlank()) {
                        valid.add(k);
                        this.keys.add(new Key(modName, k));
                    }
                }
                if (!valid.isEmpty()) {
                    this.mods.add(new Mod(modName, valid));
                }
            }
        }
    }

    /** Returns the action for a given flag count, defaulting to LOG. */
    public FlagAction actionFor(int flagCount) {
        return flagActions.getOrDefault(flagCount, FlagAction.LOG);
    }
}
