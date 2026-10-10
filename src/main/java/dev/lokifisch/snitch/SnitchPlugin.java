package dev.lokifisch.snitch;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * SNITCH - Server-side Notifier Inspecting Translation To Catch Hackers.
 *
 * Detects client mods/resource packs via MC-265322 (the sign-translation
 * exploit): fabricate a sign carrying a mod's translation key, open + close its
 * editor, and read back the resolved text the client reports.
 */
public final class SnitchPlugin extends JavaPlugin {

    private static final String UPDATE_REPO = "Lokifisch/SNITCH";

    private volatile SnitchConfig config;
    private ProbeService probes;
    private DetectionHandler detections;
    private UpdateChecker updateChecker;
    private SnitchGui gui;
    private BukkitTask sweeper;
    private BukkitTask rescanner;
    private BukkitTask updateTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new SnitchConfig(getConfig());

        ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
        this.probes = new ProbeService(this, protocol);
        this.detections = new DetectionHandler(this);
        this.updateChecker = new UpdateChecker(this, UPDATE_REPO, getFile());

        protocol.addPacketListener(new SignUpdateListener(this));
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        this.gui = new SnitchGui(this);
        getServer().getPluginManager().registerEvents(gui, this);

        SnitchCommand cmd = new SnitchCommand(this);
        getCommand("snitch").setExecutor(cmd);
        getCommand("snitch").setTabCompleter(cmd);

        // Periodically discard probe sessions that never got a response.
        this.sweeper = getServer().getScheduler().runTaskTimer(this,
                () -> probes.sweepExpired(), 100L, 100L);

        startRescanner();
        startUpdateChecker();

        getLogger().info("SNITCH enabled. Probing " + config.keys.size()
                + " translation keys via MC-265322.");
    }

    @Override
    public void onDisable() {
        if (probes != null) {
            probes.clearAll();
        }
    }

    private void startRescanner() {
        if (rescanner != null) {
            rescanner.cancel();
            rescanner = null;
        }
        int interval = config.rescanIntervalTicks;
        if (interval > 0) {
            this.rescanner = getServer().getScheduler().runTaskTimer(this,
                    () -> getServer().getOnlinePlayers().forEach(p -> probes.scan(p)),
                    interval, interval);
        }
    }

    private void startUpdateChecker() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        if (!config.updateCheckEnabled) {
            return;
        }
        updateChecker.checkAsync(config.updateAutoUpdate, null);
        int interval = config.updateCheckIntervalTicks;
        if (interval > 0) {
            this.updateTask = getServer().getScheduler().runTaskTimer(this,
                    () -> updateChecker.checkAsync(config.updateAutoUpdate, null),
                    interval, interval);
        }
    }

    /** Re-applies getConfig() after an in-memory edit (GUI); only re-checks updates when asked. */
    public void refreshConfig(boolean restartUpdates) {
        this.config = new SnitchConfig(getConfig());
        startRescanner();
        if (restartUpdates) startUpdateChecker();
    }

    /** Colour-translated message prefix from config. */
    public String prefix() {
        return config.prefix.replaceAll("&([0-9a-fk-orA-F])", "§$1");
    }

    public void reloadSnitchConfig() {
        reloadConfig();
        this.config = new SnitchConfig(getConfig());
        startRescanner();
        startUpdateChecker();
    }

    /**
     * Add a translation key to a mod entry (creates the mod if it doesn't exist).
     * Returns false if the mod name contains dots or spaces (would break the YAML path).
     */
    public boolean addKey(String mod, String translationKey) {
        // Names/keys end up in config paths and console-command placeholders: keep them inert.
        if (!mod.matches("[A-Za-z0-9_-]{1,32}") || !translationKey.matches("[A-Za-z0-9_.:-]{1,128}")) {
            return false;
        }
        String path = "mods." + mod;
        java.util.List<String> current = getConfig().getStringList(path);
        if (!current.contains(translationKey)) {
            current.add(translationKey);
            getConfig().set(path, current);
            saveConfig();
            refreshConfig(false);
        }
        return true;
    }

    /**
     * Remove one specific translation key from a mod.
     * If the mod has no keys left after removal, the mod entry is also removed.
     * Returns false if neither the mod nor the key was found.
     */
    public boolean removeKey(String mod, String translationKey) {
        String path = "mods." + mod;
        if (!getConfig().contains(path)) {
            return false;
        }
        java.util.List<String> current = new java.util.ArrayList<>(getConfig().getStringList(path));
        if (!current.remove(translationKey)) {
            return false;
        }
        if (current.isEmpty()) {
            getConfig().set(path, null);
        } else {
            getConfig().set(path, current);
        }
        saveConfig();
        refreshConfig(false);
        return true;
    }

    /**
     * Remove an entire mod entry (all its keys).
     * Returns false if the mod wasn't present.
     */
    public boolean removeMod(String mod) {
        String path = "mods." + mod;
        if (!getConfig().contains(path)) {
            return false;
        }
        getConfig().set(path, null);
        saveConfig();
        refreshConfig(false);
        return true;
    }

    public SnitchConfig config() {
        return config;
    }

    public ProbeService probes() {
        return probes;
    }

    public DetectionHandler detections() {
        return detections;
    }

    public SnitchGui gui() {
        return gui;
    }

    public UpdateChecker updateChecker() {
        return updateChecker;
    }
}
