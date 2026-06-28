package dev.lokifisch.snitch;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player, per-mod flag counts and fires the configured action for
 * each flag number:
 *
 *   flag 1 → action from config (default KICK)
 *   flag 2 → action from config (default BAN)
 *   any other count → LOG (unless configured)
 */
public final class DetectionHandler {

    private final SnitchPlugin plugin;
    // player -> (mod -> flag count)
    private final Map<UUID, Map<String, Integer>> flags = new ConcurrentHashMap<>();

    public DetectionHandler(SnitchPlugin plugin) {
        this.plugin = plugin;
    }

    public void flag(Player player, String mod, String key, String evidence) {
        if (!player.isOnline()) return;
        if (player.hasPermission("snitch.bypass.action")) return;

        SnitchConfig cfg = plugin.config();

        int count = flags.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .merge(mod, 1, Integer::sum);

        if (cfg.logConsole) {
            plugin.getLogger().warning("[SNITCH] " + player.getName()
                    + " flagged for " + mod
                    + " (key=" + key + ", reported=\"" + evidence + "\""
                    + ", flag " + count + ")");
        }

        // Run any configured console commands regardless of the flag action.
        if (!cfg.commands.isEmpty()) {
            for (String raw : cfg.commands) {
                String cmd = fill(raw, player.getName(), mod, key, evidence, count, cfg.serverName);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        }

        SnitchConfig.FlagAction action = cfg.actionFor(count);

        switch (action) {
            case KICK -> kick(player, mod, cfg);
            case BAN  -> ban(player, mod, cfg);
            case LOG  -> {} // already logged above
        }
    }

    private void kick(Player player, String mod, SnitchConfig cfg) {
        if (!player.isOnline()) return;
        String raw = cfg.kickMessage
                .replace("%mod%", mod)
                .replace("%server%", cfg.serverName);
        Component msg = LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
        player.kick(msg);
    }

    private void ban(Player player, String mod, SnitchConfig cfg) {
        if (!player.isOnline()) return;
        // Add to the server ban list before kicking so the kick message shows.
        String reason = cfg.banMessage
                .replace("%mod%", mod)
                .replace("%server%", cfg.serverName);
        // Strip colour codes for the ban reason (stored as plain text).
        String plainReason = reason.replaceAll("(?i)&[0-9a-fk-or]", "");
        Bukkit.getBanList(BanList.Type.NAME)
                .addBan(player.getName(), plainReason, (java.util.Date) null, "SNITCH");

        Component msg = LegacyComponentSerializer.legacyAmpersand().deserialize(reason);
        player.kick(msg);
    }

    private static String fill(String template, String player, String mod,
                                String key, String evidence, int flag, String server) {
        return template
                .replace("%player%", player)
                .replace("%mod%", mod)
                .replace("%key%", key)
                .replace("%evidence%", evidence)
                .replace("%flag%", String.valueOf(flag))
                .replace("%server%", server);
    }

    public void reset(UUID player) {
        flags.remove(player);
    }

    public void resetAll() {
        flags.clear();
    }
}
