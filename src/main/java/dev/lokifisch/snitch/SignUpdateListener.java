package dev.lokifisch.snitch;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Intercepts the client's Update Sign packet -- the response to our probe.
 * Each line that comes back differing from the fallback we injected proves the
 * matching translation key resolved, i.e. the mod/resource pack is installed.
 */
public final class SignUpdateListener extends PacketAdapter {

    private final SnitchPlugin plugin;

    public SignUpdateListener(SnitchPlugin plugin) {
        super(plugin, ListenerPriority.HIGHEST, PacketType.Play.Client.UPDATE_SIGN);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        ProbeSession session = plugin.probes().getSession(uuid);
        if (session == null) {
            return; // not one of ours
        }

        BlockPosition pos = event.getPacket().getBlockPositionModifier().read(0);
        if (pos == null || !pos.equals(session.position)) {
            return; // a real sign edit elsewhere -- leave it alone
        }

        String[] lines = event.getPacket().getStringArrays().read(0);
        // This was a fabricated sign; never let the server process it as a real edit.
        event.setCancelled(true);
        // Advance to the next queued batch (or clear if none remain).
        plugin.getServer().getScheduler().runTask(plugin,
                () -> plugin.probes().onBatchComplete(uuid));

        if (lines == null) {
            return;
        }

        boolean clean = true;
        for (int i = 0; i < 4 && i < lines.length; i++) {
            if (session.modNames[i] == null) {
                continue; // unused line
            }
            String returned = lines[i] == null ? "" : lines[i].trim();
            String fallback = session.fallbacks[i] == null ? "" : session.fallbacks[i].trim();
            if (!returned.isEmpty() && !returned.equals(fallback)) {
                clean = false;
                final String mod = session.modNames[i];
                final String key = session.keys[i];
                final String evidence = returned;
                // Detection handling touches the Bukkit API -> hop to main thread.
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> plugin.detections().flag(player, mod, key, evidence));
            }
        }

        if (clean && plugin.config().logCleanPlayers) {
            plugin.getLogger().info("[SNITCH] " + player.getName()
                    + " passed a batch clean (no banned keys resolved).");
        }
    }
}
