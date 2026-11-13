package com.armourrestricted;

import io.papermc.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RestrictedListener implements Listener {

    private final ArmourRestricted plugin;
    private final Map<UUID, Double> travelBuffer = new HashMap<>();
    private final Map<String, Long> killStamps = new HashMap<>();

    public RestrictedListener(ArmourRestricted plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------- join / quit

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.onJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        travelBuffer.remove(e.getPlayer().getUniqueId());
        plugin.onQuit(e.getPlayer());
    }

    // ---------------------------------------------------------- armor lock

    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> plugin.enforceArmor(p));
    }

    // ---------------------------------------------------------- book

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!plugin.isEtaBook(e.getItem())) return;
        Player p = e.getPlayer();
        if (e.getHand() == EquipmentSlot.OFF_HAND && plugin.isEtaBook(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        p.openBook(plugin.buildBook(p));
    }

    // ---------------------------------------------------------- tasks

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        plugin.addProgress(p, TaskType.BLOCKS_MINED, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        String key = killer.getUniqueId() + ":" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = killStamps.get(key);
        if (last != null && now - last < plugin.killCooldownMs()) return;
        killStamps.put(key, now);
        plugin.addProgress(killer, TaskType.KILL_PLAYERS, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Monster)) return;
        Player killer = e.getEntity().getKiller();
        if (killer != null) plugin.addProgress(killer, TaskType.KILL_MOBS, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (!(e.getEntity() instanceof TNTPrimed tnt)) return;
        Player credit = null;
        if (tnt.getSource() instanceof Player sp) {
            credit = sp;
        } else {
            double best = Double.MAX_VALUE;
            for (Player near : tnt.getWorld().getNearbyPlayers(tnt.getLocation(), plugin.tntRadius())) {
                if (near.getGameMode() == GameMode.SPECTATOR) continue;
                double dist = near.getLocation().distanceSquared(tnt.getLocation());
                if (dist < best) {
                    best = dist;
                    credit = near;
                }
            }
        }
        if (credit != null) plugin.addProgress(credit, TaskType.TNT_BLASTS, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || !e.hasChangedPosition()) return;
        Player p = e.getPlayer();
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        if (from.getWorld() != to.getWorld()) return;
        double dist = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        if (dist > 10) return; // teleport / lag spike
        UUID id = p.getUniqueId();
        double buf = travelBuffer.merge(id, dist, Double::sum);
        if (buf >= 1.0) {
            long whole = (long) buf;
            travelBuffer.put(id, buf - whole);
            plugin.addProgress(p, TaskType.TRAVEL, whole);
        }
    }
}
