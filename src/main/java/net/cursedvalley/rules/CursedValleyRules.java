package net.cursedvalley.rules;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import net.kyori.adventure.text.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CursedValleyRules — Cursed Valley dunyasinda elytra ve ender incisi kullanimini kapatir.
 *
 * CursedValleyCore'a HIC dokunmaz; yanina kurulur. Bowl/drop mantigi oldugu gibi kalir.
 * Sadece config'te yazan dunyada calisir, diger dunyalarda hicbir olaya karismaz.
 */
public final class CursedValleyRules extends JavaPlugin implements Listener {

    private String worldName;
    private boolean blockElytra;
    private boolean blockPearl;
    private String msgElytra;
    private String msgPearl;
    private String msgBowl;

    private boolean bowlLock;
    private double bowlX, bowlZ, bowlRadius;
    private int combatSeconds;

    /** Mesaj spam'ini onlemek icin oyuncu basina son uyari zamani (ms). */
    private final Map<UUID, Long> lastWarn = new HashMap<>();
    private static final long WARN_GAP_MS = 3000L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::combatTick, 10L, 10L);
        getLogger().info("CursedValleyRules etkin — dünya: " + worldName
                + " | elytra: " + (blockElytra ? "kapalı" : "serbest")
                + " | ender incisi: " + (blockPearl ? "kapalı" : "serbest")
                + " | bowl kilidi: " + (bowlLock ? "açık (r=" + bowlRadius + ")" : "kapalı"));
    }

    private void load() {
        reloadConfig();
        var c = getConfig();
        worldName   = c.getString("world", "cursedvalley");
        blockElytra = c.getBoolean("block-elytra", true);
        blockPearl  = c.getBoolean("block-ender-pearl", true);
        msgElytra   = color(c.getString("message-elytra",
                "&c&lCURSED VALLEY &7- &fBurada elytra ile uçamazsın."));
        msgPearl    = color(c.getString("message-ender-pearl",
                "&c&lCURSED VALLEY &7- &fBurada ender incisi kullanamazsın."));

        bowlLock      = c.getBoolean("bowl.lock-during-pvp", true);
        bowlX         = c.getDouble("bowl.center-x", 0);
        bowlZ         = c.getDouble("bowl.center-z", 0);
        bowlRadius    = c.getDouble("bowl.radius", 85);
        combatSeconds = Math.max(1, c.getInt("bowl.combat-seconds", 10));
        msgBowl       = color(c.getString("message-bowl",
                "&c&lBOWL &7- &fÇatışma sürerken bölgeden çıkamazsın."));
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Olay bu dunyada mi? Degilse eklenti hicbir seye karismaz. */
    private boolean inWorld(Entity e) {
        return e != null && e.getWorld().getName().equalsIgnoreCase(worldName);
    }

    /** Uyariyi 3 saniyede bir gosterir; aksi halde ekran dolar. */
    private void warn(Player player, String message) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(player.getUniqueId());
        if (last != null && now - last < WARN_GAP_MS) return;
        lastWarn.put(player.getUniqueId(), now);
        player.sendActionBar(Component.text(message));
    }

    // ---------------- ELYTRA ----------------

    /** Suzulmeye gecisi engeller. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!blockElytra) return;
        if (!event.isGliding()) return;              // inise gecisi engelleme
        if (!(event.getEntity() instanceof Player player)) return;
        if (!inWorld(player)) return;

        event.setCancelled(true);
        // Istemci "suzuluyorum" sanmasin diye sunucu tarafinda da kapatiyoruz.
        getServer().getScheduler().runTask(this, () -> player.setGliding(false));
        warn(player, msgElytra);
    }

    /** Elytra'yi gogus slotuna takmayi da engeller (takamazsa hic denemez). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEquip(InventoryClickEvent event) {
        if (!blockElytra) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!inWorld(player)) return;

        // Oyuncu envanterinde gogus zirhi slotunun ham numarasi 38'dir.
        boolean intoChestSlot = event.getRawSlot() == 38;
        ItemStack incoming = event.getCursor();
        boolean elytraToChest = intoChestSlot
                && incoming != null && incoming.getType() == Material.ELYTRA;

        // Shift+tik ile envanterden gogus slotuna gonderme
        ItemStack clicked = event.getCurrentItem();
        boolean elytraShifted = event.isShiftClick()
                && clicked != null && clicked.getType() == Material.ELYTRA;

        if (elytraToChest || elytraShifted) {
            event.setCancelled(true);
            warn(player, msgElytra);
        }
    }

    /** Sag tikla elytra takmayi engeller. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapEquip(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!inWorld(player)) return;

        ItemStack item = event.getItem();
        if (item == null) return;

        if (blockElytra && item.getType() == Material.ELYTRA
                && event.getHand() == EquipmentSlot.HAND) {
            event.setCancelled(true);
            warn(player, msgElytra);
            return;
        }

        if (blockPearl && item.getType() == Material.ENDER_PEARL) {
            event.setCancelled(true);
            player.updateInventory();   // el animasyonu takili kalmasin
            warn(player, msgPearl);
        }
    }

    // ---------------- ENDER INCISI ----------------

    /** Firlatma anini yakalar (sag tik olayi kacarsa ikinci koruma). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!blockPearl) return;
        Projectile projectile = event.getEntity();
        if (!(projectile instanceof EnderPearl)) return;
        if (!inWorld(projectile)) return;

        event.setCancelled(true);
        if (projectile.getShooter() instanceof Player player) {
            player.updateInventory();
            warn(player, msgPearl);
        }
    }

    /** Isinlanma anini da kapatir (baska yoldan atilmis inci varsa). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!blockPearl) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        if (!inWorld(event.getPlayer())) return;

        event.setCancelled(true);
        warn(event.getPlayer(), msgPearl);
    }

    // ---------------- BOWL PVP KILIDI ----------------

    /** PvP etiketi: oyuncu -> etiketin bitecegi zaman (ms). */
    private final Map<UUID, Long> combatUntil = new HashMap<>();

    /** Bowl merkezine olan yatay mesafe. */
    private double bowlDistance(Location loc) {
        double dx = loc.getX() - bowlX;
        double dz = loc.getZ() - bowlZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean inCombat(Player player) {
        Long until = combatUntil.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    /** Oyuncular birbirine vurdugunda iki tarafi da etiketler. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!bowlLock) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!inWorld(victim)) return;

        Player attacker = null;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Projectile pr
                && pr.getShooter() instanceof Player p2) attacker = p2;
        if (attacker == null || attacker.equals(victim)) return;

        long until = System.currentTimeMillis() + combatSeconds * 1000L;
        combatUntil.put(victim.getUniqueId(), until);
        combatUntil.put(attacker.getUniqueId(), until);
    }

    /** Etiket varken bowl sinirindan cikmayi engeller. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!bowlLock) return;

        Location to = event.getTo();
        Player player = event.getPlayer();
        if (!inWorld(player) || !inCombat(player)) return;

        // Sadece sinirin disina cikmaya calisirken devreye girer.
        if (bowlDistance(to) <= bowlRadius) return;
        if (bowlDistance(event.getFrom()) > bowlRadius) return;   // zaten disarda ise karisma

        event.setCancelled(true);

        // Iceriye dogru hafif itis, duvara yapismasin
        Vector inward = new Vector(bowlX - to.getX(), 0, bowlZ - to.getZ());
        if (inward.lengthSquared() > 0.01) {
            player.setVelocity(inward.normalize().multiply(0.45).setY(0.2));
        }
        warn(player, msgBowl);
    }

    /** Geri sayim cubugunu her yarim saniyede tazeler. */
    private void combatTick() {
        if (!bowlLock) return;
        long now = System.currentTimeMillis();

        combatUntil.entrySet().removeIf(entry -> {
            Player player = getServer().getPlayer(entry.getKey());
            long left = entry.getValue() - now;

            if (player == null || !player.isOnline()) return left <= 0;

            if (left <= 0) {
                if (inWorld(player)) {
                    player.sendActionBar(Component.text("Artık bowl bölgesinden çıkabilirsin.",
                            NamedTextColor.GREEN));
                }
                return true;
            }

            if (inWorld(player) && bowlDistance(player.getLocation()) <= bowlRadius) {
                player.sendActionBar(countdownBar(left));
            }
            return false;
        });
    }

    /** "Bowl kilidi ||||||||.... 6.5 sn" seklinde alt cubuk. */
    private Component countdownBar(long millisLeft) {
        int slots = 20;
        double ratio = Math.max(0.0, Math.min(1.0,
                millisLeft / (double) (combatSeconds * 1000L)));
        int filled = (int) Math.round(ratio * slots);

        String full = "|".repeat(Math.max(0, filled));
        String empty = "|".repeat(Math.max(0, slots - filled));
        double seconds = Math.ceil(millisLeft / 100.0) / 10.0;

        return Component.text("Bowl kilidi  ", NamedTextColor.RED)
                .append(Component.text(full, NamedTextColor.GOLD))
                .append(Component.text(empty, NamedTextColor.DARK_GRAY))
                .append(Component.text("  " + seconds + " sn", NamedTextColor.WHITE));
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender,
                             org.bukkit.command.Command command,
                             String label, String[] args) {
        load();
        sender.sendMessage(ChatColor.GREEN + "CursedValleyRules ayarları yeniden yüklendi.");
        return true;
    }
}
