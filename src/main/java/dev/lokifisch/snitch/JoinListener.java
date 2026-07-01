package dev.lokifisch.snitch;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Schedules a scan shortly after join (during the terrain-loading screen, so the
 * sign editor is never seen) and cleans up per-player state on quit.
 */
public final class JoinListener implements Listener {

    private final SnitchPlugin plugin;

    public JoinListener(SnitchPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null && p.isOnline()) {
                plugin.probes().scan(p);
            }
        }, plugin.config().delayTicks);

        Player player = event.getPlayer();
        if (player.hasPermission("snitch.admin") && plugin.updateChecker().isUpdateAvailable()) {
            UpdateChecker checker = plugin.updateChecker();
            player.sendMessage("§e[SNITCH] Update available: §fv" + checker.getLatestVersion()
                    + " §7(currently v" + checker.getCurrentVersion() + ") - §7" + checker.getReleaseUrl());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.probes().clearSession(uuid);
        // Strikes intentionally persist for the session; reset them here if you
        // prefer per-connection counting instead.
    }
}
