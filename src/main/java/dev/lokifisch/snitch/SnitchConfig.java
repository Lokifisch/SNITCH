package dev.lokifisch.snitch;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable snapshot of config.yml, rebuilt on every reload.
 */
public final class SnitchConfig {

    /**
     * One probe entry: a friendly mod name plus ALL translation keys that
     * identify it. The player is flagged as soon as any key resolves.
     */
    public record Mod(String name, List<String> keys) {}

    /**
     * Flat view used by ProbeService: one (mod, key) pair per sign line.
     */
    public record Key(String mod, String translationKey) {}

    public final int delayTicks;
    public final int rescanIntervalTicks;
    public final int probeYOffset;
    public final int closeDelayTicks;
    public final int revertDelayTicks;
    public final int batchSpacingTicks;
    public final int sessionTimeoutTicks;

    public final boolean actionLog;
    public final boolean actionKick;
    public final boolean actionCommand;
    public final int strikesBeforeAction;
    public final String kickMessage;
    public final List<String> commands;

    public final boolean logConsole;
    public final boolean logCleanPlayers;

    /** Per-mod groupings (used for list/add/remove commands). */
    public final List<Mod> mods;

    /** Flat list of all (mod, key) pairs in probe order (used by ProbeService). */
    public final List<Key> keys;

    public SnitchConfig(FileConfiguration c) {
        this.delayTicks = Math.max(0, c.getInt("detection.delay-ticks", 40));
        this.rescanIntervalTicks = Math.max(0, c.getInt("detection.rescan-interval-ticks", 0));
        this.probeYOffset = c.getInt("detection.probe-y-offset", -3);
        this.closeDelayTicks = Math.max(1, c.getInt("detection.close-delay-ticks", 2));
        this.revertDelayTicks = Math.max(1, c.getInt("detection.revert-delay-ticks", 4));
        this.batchSpacingTicks = Math.max(1, c.getInt("detection.batch-spacing-ticks", 8));
        this.sessionTimeoutTicks = Math.max(20, c.getInt("detection.session-timeout-ticks", 100));

        String mode = c.getString("action.mode", "LOG").toUpperCase(Locale.ROOT);
        this.actionLog = mode.contains("LOG");
        this.actionKick = mode.contains("KICK");
        this.actionCommand = mode.contains("COMMAND");
        this.strikesBeforeAction = Math.max(1, c.getInt("action.strikes-before-action", 1));
        this.kickMessage = c.getString("action.kick-message",
                "&cDisconnected: the mod &e%mod%&c is not allowed on this server.");
        this.commands = c.getStringList("action.commands");

        this.logConsole = c.getBoolean("logging.console", true);
        this.logCleanPlayers = c.getBoolean("logging.log-clean-players", false);

        this.mods = new ArrayList<>();
        this.keys = new ArrayList<>();

        ConfigurationSection modsSection = c.getConfigurationSection("mods");
        if (modsSection != null) {
            for (String modName : modsSection.getKeys(false)) {
                List<String> modKeys = modsSection.getStringList(modName);
                List<String> validKeys = new ArrayList<>();
                for (String k : modKeys) {
                    if (k != null && !k.isBlank()) {
                        validKeys.add(k);
                        this.keys.add(new Key(modName, k));
                    }
                }
                if (!validKeys.isEmpty()) {
                    this.mods.add(new Mod(modName, validKeys));
                }
            }
        }
    }
}
