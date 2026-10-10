package dev.lokifisch.snitch;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * /snitch gui - chest menus covering every config.yml option. Booleans toggle,
 * numbers step with left/right click (shift = x10), enums cycle, text and new
 * mods/keys are typed into chat. Every change is saved and applied immediately.
 */
public final class SnitchGui implements Listener {

    private static final String PERM = "snitch.admin";
    private static final int MAX_ITEMS = 52; // ponytail: no paging, one 54-slot page; add paging past ~50 mods/keys

    private final SnitchPlugin plugin;
    private record Prompt(Consumer<String> then, long expires) {}

    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public SnitchGui(SnitchPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Menu implements InventoryHolder {
        final Inventory inv;
        final Map<Integer, Consumer<ClickType>> on = new HashMap<>();
        int next = 0;
        int overflow = 0;

        Menu(String title) {
            inv = Bukkit.createInventory(this, 54, txt(title));
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }

        void add(ItemStack it, Consumer<ClickType> h) {
            if (next < MAX_ITEMS) put(next++, it, h);
            else overflow++;
        }

        void put(int slot, ItemStack it, Consumer<ClickType> h) {
            inv.setItem(slot, it);
            on.put(slot, h);
        }
    }

    // ---------------------------------------------------------------- menus

    public void open(Player p) {
        Menu m = new Menu("§8SNITCH");
        m.add(item(Material.NAME_TAG, "§eSettings", "§7Prefix and server name"), c -> settings(p));
        m.add(item(Material.CLOCK, "§eDetection", "§7Timing and probe options"), c -> detection(p));
        m.add(item(Material.IRON_SWORD, "§eActions", "§7What happens on each flag"), c -> actions(p));
        m.add(item(Material.WRITABLE_BOOK, "§eLogging & updates"), c -> logging(p));
        m.add(item(Material.BOOKSHELF, "§eMods", "§7" + plugin.config().mods.size() + " mods, "
                + plugin.config().keys.size() + " keys"), c -> mods(p));
        show(p, m);
    }

    private void settings(Player p) {
        Menu m = sub(p, "§8SNITCH » Settings");
        Runnable re = () -> settings(p);
        text(m, p, re, "prefix", "§ePrefix", Material.NAME_TAG, "&e[SNITCH] ",
                "Shown before every SNITCH message. Supports & colour codes.");
        text(m, p, re, "server-name", "§eServer name", Material.GRASS_BLOCK, "My Server", "Used for %server% in messages.");
        show(p, m);
    }

    private void detection(Player p) {
        Menu m = sub(p, "§8SNITCH » Detection");
        Runnable re = () -> detection(p);
        num(m, p, re, "detection.delay-ticks", "§eScan delay (ticks)", Material.CLOCK, 40, 0, 1);
        num(m, p, re, "detection.rescan-interval-ticks", "§eRescan interval (ticks, 0=off)", Material.REPEATER, 0, 0, 20);
        num(m, p, re, "detection.probe-y-offset", "§eProbe Y offset", Material.LADDER, -3, -64, 1);
        num(m, p, re, "detection.close-delay-ticks", "§eClose delay (ticks)", Material.IRON_DOOR, 2, 1, 1);
        num(m, p, re, "detection.revert-delay-ticks", "§eRevert delay (ticks)", Material.OAK_SIGN, 4, 1, 1);
        num(m, p, re, "detection.session-timeout-ticks", "§eSession timeout (ticks)", Material.HOPPER, 100, 20, 20);
        show(p, m);
    }

    private void actions(Player p) {
        Menu m = sub(p, "§8SNITCH » Actions");
        Runnable re = () -> actions(p);
        for (int n = 1; n <= 3; n++) {
            final int flag = n;
            SnitchConfig.FlagAction cur = plugin.config().actionFor(flag);
            m.add(item(Material.IRON_SWORD, "§eFlag " + flag + ": §f" + cur,
                    "§7Action on a player's flag number " + flag, "§7Click to cycle LOG / KICK / BAN"), c -> {
                SnitchConfig.FlagAction[] all = SnitchConfig.FlagAction.values();
                int now = plugin.config().actionFor(flag).ordinal();
                apply(p, "action.flags." + flag, all[(now + 1) % all.length].name(), re);
            });
        }
        text(m, p, re, "action.kick-message", "§eKick message", Material.LEATHER_BOOTS,
                plugin.config().kickMessage, "Placeholders: %mod% %server%");
        text(m, p, re, "action.ban-message", "§eBan message", Material.BARRIER,
                plugin.config().banMessage, "Placeholders: %mod% %server%");
        show(p, m);
    }

    private void logging(Player p) {
        Menu m = sub(p, "§8SNITCH » Logging & updates");
        Runnable re = () -> logging(p);
        bool(m, p, re, "logging.console", "§eLog detections to console", Material.PAPER, true);
        bool(m, p, re, "logging.log-clean-players", "§eLog clean players", Material.WHITE_WOOL, false);
        bool(m, p, re, "update-checker.enabled", "§eUpdate checker", Material.COMPASS, true);
        bool(m, p, re, "update-checker.auto-update", "§eAuto-update", Material.HOPPER, true);
        num(m, p, re, "update-checker.check-interval-ticks", "§eUpdate check interval (ticks)", Material.CLOCK, 432000, 0, 1200);
        show(p, m);
    }

    private void mods(Player p) {
        Menu m = sub(p, "§8SNITCH » Mods");
        for (SnitchConfig.Mod mod : plugin.config().mods) {
            m.add(item(Material.BOOK, "§f" + mod.name(), "§7" + mod.keys().size() + " key(s)",
                    "§7Click: edit keys", "§7Shift+right-click: delete mod"), c -> {
                if (c == ClickType.SHIFT_RIGHT) {
                    plugin.removeMod(mod.name());
                    mods(p);
                } else {
                    keys(p, mod.name());
                }
            });
        }
        m.put(52, item(Material.LIME_DYE, "§aAdd mod", "§7Type: <ModName> <translation.key>"), c ->
                prompt(p, "Type §f<ModName> <translation.key>§e (no spaces in the name), or 'cancel':", s -> {
                    String[] a = s.trim().split("\\s+");
                    if (a.length != 2 || !plugin.addKey(a[0], a[1])) {
                        p.sendMessage(plugin.prefix() + "§cExpected: <ModName> <translation.key> (letters, digits, _ . : - only)");
                    }
                    mods(p);
                }));
        show(p, m);
    }

    private void keys(Player p, String mod) {
        Menu m = new Menu("§8SNITCH » " + mod);
        List<String> keys = plugin.config().mods.stream().filter(x -> x.name().equals(mod))
                .findFirst().map(SnitchConfig.Mod::keys).orElse(List.of());
        for (String key : keys) {
            m.add(item(Material.PAPER, "§f" + key, "§7Shift+right-click: remove"), c -> {
                if (c == ClickType.SHIFT_RIGHT) {
                    plugin.removeKey(mod, key);
                    keys(p, mod);
                }
            });
        }
        m.put(52, item(Material.LIME_DYE, "§aAdd key"), c ->
                prompt(p, "Type the translation key to add to §f" + mod + "§e, or 'cancel':", s -> {
                    if (!plugin.addKey(mod, s.trim())) {
                        p.sendMessage(plugin.prefix() + "§cInvalid key (letters, digits, _ . : - only).");
                    }
                    keys(p, mod);
                }));
        m.put(53, item(Material.ARROW, "§7Back"), c -> mods(p));
        show(p, m);
    }

    private Menu sub(Player p, String title) {
        Menu m = new Menu(title);
        m.put(53, item(Material.ARROW, "§7Back"), c -> open(p));
        return m;
    }

    // ------------------------------------------------------ setting widgets

    private void bool(Menu m, Player p, Runnable re, String path, String name, Material icon, boolean def) {
        boolean v = plugin.getConfig().getBoolean(path, def);
        m.add(item(icon, name + ": " + (v ? "§aON" : "§cOFF"), "§7Click to toggle"),
                c -> apply(p, path, !plugin.getConfig().getBoolean(path, def), re));
    }

    private void num(Menu m, Player p, Runnable re, String path, String name, Material icon,
                     int def, int min, int step) {
        int v = plugin.getConfig().getInt(path, def);
        m.add(item(icon, name + ": §f" + v, "§7Left: +" + step + "  Right: -" + step,
                "§7Shift: x10"), c -> {
            int d = switch (c) {
                case LEFT -> step;
                case SHIFT_LEFT -> step * 10;
                case RIGHT -> -step;
                case SHIFT_RIGHT -> -step * 10;
                default -> 0;
            };
            if (d != 0) apply(p, path, Math.max(min, plugin.getConfig().getInt(path, def) + d), re);
        });
    }

    private void text(Menu m, Player p, Runnable re, String path, String name, Material icon,
                      String def, String help) {
        String v = plugin.getConfig().getString(path, def);
        String shown = v.length() > 60 ? v.substring(0, 60) + "…" : v;
        m.add(item(icon, name, "§7Current: §f" + shown, "§7Preview: §r" + v.replaceAll("&([0-9a-fk-orA-F])", "§$1"),
                "§7" + help, "§7Click, then type the new value in chat"),
                c -> prompt(p, "Type the new value for §f" + path + "§e (or 'cancel'):", s -> apply(p, path, s, re)));
    }

    // ------------------------------------------------------------- plumbing

    private void apply(Player p, String path, Object value, Runnable reopen) {
        // server-name is substituted into console commands: keep it to inert characters.
        if (path.equals("server-name") && value instanceof String s) {
            value = s.replaceAll("[^A-Za-z0-9 _.-]", "");
            if (((String) value).length() > 32) value = ((String) value).substring(0, 32);
        }
        plugin.getConfig().set(path, value);
        plugin.saveConfig();
        plugin.refreshConfig(path.startsWith("update-checker"));
        reopen.run();
    }

    private void prompt(Player p, String ask, Consumer<String> then) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            p.closeInventory();
            p.sendMessage(plugin.prefix() + "§e" + ask);
            prompts.put(p.getUniqueId(), new Prompt(s -> {
                if (s.equalsIgnoreCase("cancel")) open(p);
                else then.accept(s);
            }, System.currentTimeMillis() + 30_000));
        });
    }

    private void show(Player p, Menu m) {
        prompts.remove(p.getUniqueId());
        if (m.overflow > 0) {
            m.put(51, item(Material.BARRIER, "§c+" + m.overflow + " more not shown", "§7Use /snitch commands"), c -> {});
        }
        p.openInventory(m.inv);
    }

    private static ItemStack item(Material mat, String name, String... lore) {
        ItemStack i = new ItemStack(mat);
        ItemMeta meta = i.getItemMeta();
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.displayName(txt(name));
        meta.lore(Arrays.stream(lore).map(SnitchGui::txt).toList());
        i.setItemMeta(meta);
        return i;
    }

    private static Component txt(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    // --------------------------------------------------------------- events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission(PERM)) return;
        if (e.getClickedInventory() != m.inv) return;
        ClickType ct = e.getClick();
        if (ct != ClickType.LEFT && ct != ClickType.RIGHT
                && ct != ClickType.SHIFT_LEFT && ct != ClickType.SHIFT_RIGHT) return;
        Consumer<ClickType> h = m.on.get(e.getSlot());
        if (h != null) h.accept(ct);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Prompt pr = prompts.remove(e.getPlayer().getUniqueId());
        if (pr == null) return;
        if (pr.expires() < System.currentTimeMillis() || !e.getPlayer().hasPermission(PERM)) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> pr.then().accept(text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prompts.remove(e.getPlayer().getUniqueId());
    }
}
