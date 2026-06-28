package dev.lokifisch.snitch;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.BlockPosition;
import net.kyori.adventure.text.Component;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds and fires the MC-265322 sign-translation probe.
 *
 * Packet sequence per batch (mirrors the video):
 *   1. Block Change        -> place a throwaway sign at the probe position.
 *   2. Block Entity Data   -> set the sign's lines to translatable components,
 *                             each with a unique "fallback" word. (Paper's
 *                             sendSignChange builds this packet for us.)
 *   3. Open Sign Editor    -> client opens the editor and resolves the keys to
 *                             plain text.
 *   4. Container Close      -> client closes the editor and reports the text.
 *   5. (inbound) Update Sign-> handled by {@link SignUpdateListener}.
 *   6. Block Change        -> revert the fake sign to the real block.
 */
public final class ProbeService {

    private static final BlockData SIGN_DATA = Material.OAK_SIGN.createBlockData();

    private final SnitchPlugin plugin;
    private final ProtocolManager protocol;
    private final ConcurrentHashMap<UUID, ProbeSession> active = new ConcurrentHashMap<>();

    public ProbeService(SnitchPlugin plugin, ProtocolManager protocol) {
        this.plugin = plugin;
        this.protocol = protocol;
    }

    public ProbeSession getSession(UUID player) {
        return active.get(player);
    }

    public void clearSession(UUID player) {
        active.remove(player);
    }

    /** Drop sessions that never got a response, so the map cannot leak. */
    public void sweepExpired() {
        long now = currentTick();
        int timeout = plugin.config().sessionTimeoutTicks;
        active.values().removeIf(s -> now - s.createdTick > timeout);
    }

    public void clearAll() {
        active.clear();
    }

    /** Kick off a full scan of a player: every configured key, four per sign. */
    public void scan(Player player) {
        if (player.hasPermission("snitch.bypass")) {
            return;
        }
        SnitchConfig cfg = plugin.config();
        List<SnitchConfig.Key> keys = cfg.keys;
        if (keys.isEmpty()) {
            return;
        }

        // Chunk into batches of four (one sign carries four lines).
        List<List<SnitchConfig.Key>> batches = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += 4) {
            batches.add(keys.subList(i, Math.min(i + 4, keys.size())));
        }

        UUID uuid = player.getUniqueId();
        for (int b = 0; b < batches.size(); b++) {
            List<SnitchConfig.Key> batch = batches.get(b);
            long delay = (long) b * cfg.batchSpacingTicks;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    sendBatch(p, batch);
                }
            }, delay);
        }
    }

    private void sendBatch(Player player, List<SnitchConfig.Key> batch) {
        World world = player.getWorld();
        Location loc = player.getLocation();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int y = clamp(loc.getBlockY() + plugin.config().probeYOffset,
                world.getMinHeight(), world.getMaxHeight() - 1);

        BlockPosition pos = new BlockPosition(x, y, z);
        Location signLoc = new Location(world, x, y, z);
        BlockData original = world.getBlockAt(x, y, z).getBlockData();

        // Build the session + the four translatable lines (padded to four).
        ProbeSession session = new ProbeSession(player.getUniqueId(), pos, currentTick());
        List<Component> lines = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            if (i < batch.size()) {
                SnitchConfig.Key key = batch.get(i);
                String fallback = randomFallback();
                session.fallbacks[i] = fallback;
                session.modNames[i] = key.mod();
                session.keys[i] = key.translationKey();
                lines.add(Component.translatable()
                        .key(key.translationKey())
                        .fallback(fallback)
                        .build());
            } else {
                session.fallbacks[i] = "";
                lines.add(Component.empty());
            }
        }
        active.put(player.getUniqueId(), session);

        // 1 + 2: fake sign block, then its translatable contents.
        player.sendBlockChange(signLoc, SIGN_DATA);
        player.sendSignChange(signLoc, lines, DyeColor.BLACK, false);

        // 3: open the editor (front side).
        sendOpenSignEditor(player, pos);

        // 4: force the editor closed so the client reports the resolved text.
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> sendContainerClose(player), plugin.config().closeDelayTicks);

        // 6: revert the fake block back to whatever was really there.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.sendBlockChange(signLoc, original);
            }
        }, plugin.config().revertDelayTicks);
    }

    private void sendOpenSignEditor(Player player, BlockPosition pos) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.OPEN_SIGN_EDITOR);
        packet.getBlockPositionModifier().write(0, pos);
        // 1.20+ carries a boolean for which side of the sign is being edited.
        if (!packet.getBooleans().getFields().isEmpty()) {
            packet.getBooleans().write(0, true); // front text
        }
        protocol.sendServerPacket(player, packet);
    }

    private void sendContainerClose(Player player) {
        if (!player.isOnline()) {
            return;
        }
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.CLOSE_WINDOW);
        packet.getIntegers().write(0, 0); // window id 0
        protocol.sendServerPacket(player, packet);
    }

    private static String randomFallback() {
        // Short, lowercase, collision-proof token. Never a real translation.
        return "sn" + Long.toString(ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE), 36);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private long currentTick() {
        return plugin.getServer().getCurrentTick();
    }
}
