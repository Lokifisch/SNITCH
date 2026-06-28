package dev.lokifisch.snitch;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides what happens when a banned key resolves on a client: counts strikes
 * (the video notes Donut only bans on the second connect) and applies the
 * configured action(s) -- log, kick, and/or run console commands.
 */
public final class DetectionHandler {

    private final SnitchPlugin plugin;
    // player -> (mod -> strike count). Strikes persist for the server session.
    private final Map<UUID, Map<String, Integer>> strikes = new ConcurrentHashMap<>();

    public DetectionHandler(SnitchPlugin plugin) {
        this.plugin = plugin;
    }

    public void flag(Player player, String mod, String key, String evidence) {
        if (!player.isOnline()) {
            return;
        }
        SnitchConfig cfg = plugin.config();

        int count = strikes.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .merge(mod, 1, Integer::sum);

        if (cfg.logConsole) {
            plugin.getLogger().warning("[SNITCH] " + player.getName() + " flagged for " + mod
                    + " (key=" + key + ", reported=\"" + evidence + "\", strike " + count + "/"
                    + cfg.strikesBeforeAction + ")");
        }

        if (count < cfg.strikesBeforeAction) {
            return;
        }

        if (cfg.actionCommand) {
            for (String raw : cfg.commands) {
                String cmd = raw
                        .replace("%player%", player.getName())
                        .replace("%mod%", mod)
                        .replace("%key%", key)
                        .replace("%evidence%", evidence);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        }

        if (cfg.actionKick) {
            String msg = cfg.kickMessage.replace("%mod%", mod).replace("%key%", key);
            Component kick = LegacyComponentSerializer.legacyAmpersand().deserialize(msg);
            player.kick(kick);
        }
    }

    public void reset(UUID player) {
        strikes.remove(player);
    }

    public void resetAll() {
        strikes.clear();
    }
}
