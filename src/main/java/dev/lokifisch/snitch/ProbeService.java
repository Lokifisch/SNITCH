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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds and fires the MC-265322 sign-translation probe.
 *
 * Batches are sent SEQUENTIALLY per player — the next batch only fires after
 * the previous one receives its Update Sign response (or times out). This
 * prevents the session map from being overwritten mid-flight, which caused the
 * wrong keys to be matched to the wrong sign lines.
 *
 * Packet sequence per batch:
 *   1. Block Change        – place a throwaway sign at the probe position.
 *   2. sendSignChange      – set translatable lines with unique fallback words.
 *   3. Open Sign Editor    – client resolves keys to plain text.
 *   4. Container Close     – client closes the editor and reports resolved text.
 *   5. (inbound) UpdateSign– handled by SignUpdateListener; triggers next batch.
 *   6. Block Change        – revert the fake sign.
 */
public final class ProbeService {

    private static final BlockData SIGN_DATA = Material.OAK_SIGN.createBlockData();

    private final SnitchPlugin plugin;
    private final ProtocolManager protocol;

    /** One active session per player (the batch currently in flight). */
    private final ConcurrentHashMap<UUID, ProbeSession> active = new ConcurrentHashMap<>();

    /** Pending batches queued behind the active one, per player. */
    private final ConcurrentHashMap<UUID, Deque<List<SnitchConfig.Key>>> pending =
            new ConcurrentHashMap<>();

    public ProbeService(SnitchPlugin plugin, ProtocolManager protocol) {
        this.plugin = plugin;
        this.protocol = protocol;
    }

    public ProbeSession getSession(UUID player) {
        return active.get(player);
    }

    /** Called by SignUpdateListener after a batch response is processed. */
    public void onBatchComplete(UUID playerUuid) {
        active.remove(playerUuid);
        Deque<List<SnitchConfig.Key>> queue = pending.get(playerUuid);
        if (queue == null || queue.isEmpty()) {
            pending.remove(playerUuid);
            return;
        }
        List<SnitchConfig.Key> next = queue.poll();
        Player player = plugin.getServer().getPlayer(playerUuid);
        if (player != null && player.isOnline()) {
            sendBatch(player, next);
        }
    }

    public void clearSession(UUID player) {
        active.remove(player);
        pending.remove(player);
    }

    public void sweepExpired() {
        long now = currentTick();
        int timeout = plugin.config().sessionTimeoutTicks;
        active.entrySet().removeIf(e -> {
            if (now - e.getValue().createdTick > timeout) {
                // Timed-out session: try to advance to next queued batch.
                UUID uuid = e.getKey();
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> onBatchComplete(uuid));
                return true;
            }
            return false;
        });
    }

    public void clearAll() {
        active.clear();
        pending.clear();
    }

    /** Kick off a full scan of a player: every configured key, four per sign. */
    public void scan(Player player) {
        if (player.hasPermission("snitch.bypass")) return;

        List<SnitchConfig.Key> keys = plugin.config().keys;
        if (keys.isEmpty()) return;

        UUID uuid = player.getUniqueId();

        // Build all batches of four.
        List<List<SnitchConfig.Key>> batches = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += 4) {
            batches.add(new ArrayList<>(keys.subList(i, Math.min(i + 4, keys.size()))));
        }

        // If there is already an active session, queue everything behind it.
        Deque<List<SnitchConfig.Key>> queue =
                pending.computeIfAbsent(uuid, k -> new ArrayDeque<>());

        if (active.containsKey(uuid)) {
            // A batch is already in-flight; queue all new batches.
            queue.addAll(batches);
        } else {
            // Nothing in flight: send the first batch now, queue the rest.
            List<SnitchConfig.Key> first = batches.get(0);
            for (int i = 1; i < batches.size(); i++) {
                queue.add(batches.get(i));
            }
            sendBatch(player, first);
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

        // Build session and translatable lines.
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

        // 1 + 2: fake sign block, then translatable sign contents.
        player.sendBlockChange(signLoc, SIGN_DATA);
        player.sendSignChange(signLoc, lines, DyeColor.BLACK, false);

        // 3: open the sign editor.
        sendOpenSignEditor(player, pos);

        // 4: force the editor closed.
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> sendContainerClose(player), plugin.config().closeDelayTicks);

        // 6: revert the fake block.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) player.sendBlockChange(signLoc, original);
        }, plugin.config().revertDelayTicks);
    }

    private void sendOpenSignEditor(Player player, BlockPosition pos) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.OPEN_SIGN_EDITOR);
        packet.getBlockPositionModifier().write(0, pos);
        if (!packet.getBooleans().getFields().isEmpty()) {
            packet.getBooleans().write(0, true);
        }
        protocol.sendServerPacket(player, packet);
    }

    private void sendContainerClose(Player player) {
        if (!player.isOnline()) return;
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.CLOSE_WINDOW);
        packet.getIntegers().write(0, 0);
        protocol.sendServerPacket(player, packet);
    }

    private static String randomFallback() {
        return "sn" + Long.toString(ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE), 36);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private long currentTick() {
        return plugin.getServer().getCurrentTick();
    }
}
