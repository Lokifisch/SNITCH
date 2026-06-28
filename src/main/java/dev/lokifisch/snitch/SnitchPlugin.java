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

    private volatile SnitchConfig config;
    private ProbeService probes;
    private DetectionHandler detections;
    private BukkitTask sweeper;
    private BukkitTask rescanner;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new SnitchConfig(getConfig());

        ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
        this.probes = new ProbeService(this, protocol);
        this.detections = new DetectionHandler(this);

        protocol.addPacketListener(new SignUpdateListener(this));
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);

        SnitchCommand cmd = new SnitchCommand(this);
        getCommand("snitch").setExecutor(cmd);
        getCommand("snitch").setTabCompleter(cmd);

        // Periodically discard probe sessions that never got a response.
        this.sweeper = getServer().getScheduler().runTaskTimer(this,
                () -> probes.sweepExpired(), 100L, 100L);

        startRescanner();

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

    public void reloadSnitchConfig() {
        reloadConfig();
        this.config = new SnitchConfig(getConfig());
        startRescanner();
    }

    /**
     * Add or overwrite a probe key, persist it, and reload. Returns false if the
     * mod name is unusable as a YAML path (dots/spaces would nest or break it).
     */
    public boolean addKey(String mod, String translationKey) {
        if (mod.contains(".") || mod.contains(" ")) {
            return false;
        }
        getConfig().set("keys." + mod, translationKey);
        saveConfig();
        reloadSnitchConfig();
        return true;
    }

    /** Remove a probe key by mod name. Returns false if it wasn't present. */
    public boolean removeKey(String mod) {
        if (!getConfig().contains("keys." + mod)) {
            return false;
        }
        getConfig().set("keys." + mod, null);
        saveConfig();
        reloadSnitchConfig();
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
}
