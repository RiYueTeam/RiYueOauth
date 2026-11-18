package com.ruiyue.oauth.listener;

import com.ruiyue.oauth.RiYueOauth;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.Locale;
import java.util.Set;

/** 没登录时的保护：冻结、禁言、禁命令、防偷东西、无敌 */
public class PlayerProtectListener implements Listener {

    // 没登录时唯一允许用的命令
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "l", "login", "zc", "register", "reg",
            "pw", "forgotpassword", "forget", "zx", "logout");

    private final RiYueOauth plugin;

    public PlayerProtectListener(RiYueOauth plugin) {
        this.plugin = plugin;
    }

    private boolean locked(Player player) {
        return !plugin.getAuth().isLoggedIn(player)
                && !player.hasPermission("riyueoauth.bypass");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!locked(event.getPlayer()) || !plugin.getConfig().getBoolean("login.block-move", true)) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        // 允许原地转头，只拦位移
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!locked(event.getPlayer()) || !plugin.getConfig().getBoolean("login.block-chat", true)) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.msg("&c请先登录再聊天: /l 密码"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!locked(event.getPlayer()) || !plugin.getConfig().getBoolean("login.block-command", true)) return;
        String raw = event.getMessage().trim();
        if (raw.startsWith("/")) raw = raw.substring(1);
        String name = raw.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!ALLOWED_COMMANDS.contains(name)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.msg("&c请先登录: /l 密码"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (locked(event.getPlayer()) && plugin.getConfig().getBoolean("login.block-interact", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (locked(event.getPlayer()) && plugin.getConfig().getBoolean("login.block-interact", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p && locked(p)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player p && locked(p)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (locked(event.getPlayer()) && plugin.getConfig().getBoolean("login.block-interact", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player p && locked(p)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player p && locked(p)
                && plugin.getConfig().getBoolean("login.block-damage", true)) {
            event.setCancelled(true); // 没登录时无敌
        }
        if (event instanceof EntityDamageByEntityEvent hit && hit.getDamager() instanceof Player attacker
                && locked(attacker) && plugin.getConfig().getBoolean("login.block-damage", true)) {
            event.setCancelled(true); // 也不能打别人
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (locked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (locked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (locked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (locked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (locked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        if (locked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (locked(event.getPlayer())) event.setCancelled(true);
    }
}
