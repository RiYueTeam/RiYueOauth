package com.ruiyue.oauth.manager;

import com.ruiyue.oauth.RiYueOauth;
import com.ruiyue.oauth.model.PlayerSnapshot;
import com.ruiyue.oauth.util.InventorySerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家数据同步：背包、末影箱、统计信息统一存数据库。
 * 未登录时先把身上的东西暂存并清空，登录成功后从数据库加载回来；
 * 数据库里没有记录的历史玩家，就用暂存的东西还原，不会丢装备。
 */
public class DataSyncManager {

    private final RiYueOauth plugin;
    private final Map<UUID, ItemStack[]> pendingInventory = new HashMap<>();
    private final Map<UUID, ItemStack[]> pendingEnderChest = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();

    public DataSyncManager(RiYueOauth plugin) {
        this.plugin = plugin;
    }

    /** 进服时暂存并清空身上的东西（必须主线程调用） */
    public void hideInventory(Player player) {
        if (!plugin.getConfig().getBoolean("login.hide-inventory", true)) return;
        UUID uuid = player.getUniqueId();
        pendingInventory.put(uuid, player.getInventory().getContents().clone());
        pendingEnderChest.put(uuid, player.getEnderChest().getContents().clone());
        player.getInventory().clear();
        player.getEnderChest().clear();
        hidden.add(uuid);
    }

    /** 没登录就走了：把东西还回去，别让人白丢装备 */
    public void restorePending(Player player) {
        UUID uuid = player.getUniqueId();
        ItemStack[] inv = pendingInventory.remove(uuid);
        ItemStack[] ender = pendingEnderChest.remove(uuid);
        if (inv != null) player.getInventory().setContents(inv);
        if (ender != null) player.getEnderChest().setContents(ender);
        hidden.remove(uuid);
    }

    public void discardPending(UUID uuid) {
        pendingInventory.remove(uuid);
        pendingEnderChest.remove(uuid);
        hidden.remove(uuid);
    }

    public boolean isHidden(UUID uuid) {
        return hidden.contains(uuid);
    }

    /** 登录成功后：读数据库 -> 回主线程还原到玩家身上 */
    public void loadAndApply(Player player, Runnable onDone) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerSnapshot snapshot = plugin.getDatabase().getSnapshot(uuid.toString());
            Bukkit.getScheduler().runTask(plugin, () -> {
                // 玩家可能在这期间掉线了：掉了就啥也别改，他身上的东西由退服逻辑负责
                if (!player.isOnline()) return;

                boolean invApplied = false;
                boolean enderApplied = false;
                if (snapshot != null) {
                    if (plugin.getConfig().getBoolean("data-sync.sync-inventory", true)
                            && snapshot.inventory() != null && !snapshot.inventory().isEmpty()) {
                        InventorySerializer.applyPlayerInventory(player, snapshot.inventory());
                        invApplied = true;
                    }
                    if (plugin.getConfig().getBoolean("data-sync.sync-enderchest", true)
                            && snapshot.enderChest() != null && !snapshot.enderChest().isEmpty()) {
                        player.getEnderChest().setContents(
                                InventorySerializer.deserializeItems(snapshot.enderChest(), 27));
                        enderApplied = true;
                    }
                    if (plugin.getConfig().getBoolean("data-sync.sync-statistics", true)
                            && snapshot.statistics() != null && !snapshot.statistics().isEmpty()) {
                        InventorySerializer.applyStatistics(player, snapshot.statistics());
                    }
                    player.updateInventory();
                }

                // 数据库里没这块数据的，把进服时暂存的东西还回去，绝不能凭空清空玩家的背包
                if (!invApplied) {
                    ItemStack[] inv = pendingInventory.get(uuid);
                    if (inv != null) player.getInventory().setContents(inv);
                }
                if (!enderApplied) {
                    ItemStack[] ender = pendingEnderChest.get(uuid);
                    if (ender != null) player.getEnderChest().setContents(ender);
                }

                pendingInventory.remove(uuid);
                pendingEnderChest.remove(uuid);
                hidden.remove(uuid);
                onDone.run();
            });
        });
    }

    /** 保存玩家数据：主线程收集，异步写库 */
    public void save(Player player) {
        PlayerSnapshot snapshot = capture(player);
        if (snapshot == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.getDatabase().saveSnapshot(snapshot));
    }

    /** 关服/reload 时同步写库，因为那时调度器可能已经停了 */
    public void saveNow(Player player) {
        PlayerSnapshot snapshot = capture(player);
        if (snapshot != null) plugin.getDatabase().saveSnapshot(snapshot);
    }

    /** 主线程收集玩家当前数据 */
    private PlayerSnapshot capture(Player player) {
        if (!plugin.getConfig().getBoolean("data-sync.sync-inventory", true)
                && !plugin.getConfig().getBoolean("data-sync.sync-enderchest", true)
                && !plugin.getConfig().getBoolean("data-sync.sync-statistics", true)) {
            return null;
        }

        String uuid = player.getUniqueId().toString();
        String inventory = plugin.getConfig().getBoolean("data-sync.sync-inventory", true)
                ? InventorySerializer.serializePlayerInventory(player)
                : null;
        String enderChest = plugin.getConfig().getBoolean("data-sync.sync-enderchest", true)
                ? InventorySerializer.serializeItems(player.getEnderChest().getContents())
                : null;
        String statistics = plugin.getConfig().getBoolean("data-sync.sync-statistics", true)
                ? InventorySerializer.serializeStatistics(player)
                : null;

        return new PlayerSnapshot(uuid, inventory, enderChest, statistics, System.currentTimeMillis());
    }
}
