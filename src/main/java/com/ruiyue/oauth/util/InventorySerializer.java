package com.ruiyue.oauth.util;

import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Statistic;

/**
 * 把背包 / 末影箱 / 统计信息变成可存进数据库的文本，
 * 登录时再还原回玩家身上。
 */
public final class InventorySerializer {

    // 带参数的统计：击杀按生物类型记，挖掘/合成/使用按物品类型记
    private static final Statistic[] ENTITY_STATS = {
            Statistic.KILL_ENTITY, Statistic.ENTITY_KILLED_BY
    };
    private static final Statistic[] MATERIAL_STATS = {
            Statistic.MINE_BLOCK, Statistic.CRAFT_ITEM, Statistic.BREAK_ITEM,
            Statistic.USE_ITEM, Statistic.PICKUP, Statistic.DROP
    };

    private InventorySerializer() {}

    // ------------------------- 背包 / 末影箱 -------------------------

    /** 新格式标记：NBT 完整数据；没带这个标记的是旧版 YAML 数据 */
    private static final String NBT_PREFIX = "NBT1:";

    /**
     * 物品数组 -> 文本（NBT 格式，连自定义标签一起存）。
     * 比 Bukkit 自带的序列化强在：别的插件写进物品的私有 NBT 不会丢。
     */
    public static String serializeItems(ItemStack[] items) {
        try {
            ReadWriteNBT compound = NBT.itemStackArrayToNBT(items);
            return NBT_PREFIX + compound.toString();
        } catch (Exception e) {
            // NBT 万一失败，退回老办法，至少物品本身不丢
            return serializeItemsLegacy(items);
        }
    }

    /** 玩家背包 + 护甲 + 副手，合成一个数组整组用 NBT 存 */
    public static String serializePlayerInventory(Player player) {
        ItemStack[] contents = player.getInventory().getContents();   // 36 格
        ItemStack[] armor = player.getInventory().getArmorContents(); // 4 格
        ItemStack[] all = new ItemStack[41];
        System.arraycopy(contents, 0, all, 0, contents.length);
        System.arraycopy(armor, 0, all, 36, armor.length);
        all[40] = player.getInventory().getItemInOffHand();
        return serializeItems(all);
    }

    /** 文本 -> 还原背包（前 36 格）、护甲（s36~s39）和副手（s40） */
    public static void applyPlayerInventory(Player player, String data) {
        ItemStack[] all = deserializeItems(data, 41);
        ItemStack[] main = new ItemStack[36];
        ItemStack[] armor = new ItemStack[4];
        System.arraycopy(all, 0, main, 0, 36);
        System.arraycopy(all, 36, armor, 0, 4);
        player.getInventory().setContents(main);
        player.getInventory().setArmorContents(armor);
        player.getInventory().setItemInOffHand(all[40] == null
                ? new ItemStack(Material.AIR) : all[40]);
    }

    /** 文本 -> 物品数组（按给定的格子数还原），兼容旧版 YAML 数据 */
    public static ItemStack[] deserializeItems(String data, int size) {
        ItemStack[] items = new ItemStack[size];
        if (data == null || data.isEmpty()) return items;

        if (data.startsWith(NBT_PREFIX)) {
            try {
                ReadWriteNBT compound = NBT.parseNBT(data.substring(NBT_PREFIX.length()));
                ItemStack[] parsed = NBT.itemStackArrayFromNBT(compound);
                if (parsed != null) {
                    System.arraycopy(parsed, 0, items, 0, Math.min(parsed.length, size));
                    return items;
                }
            } catch (Exception ignored) {
                // NBT 数据坏了，往下退回旧格式再试一次
            }
        }
        return deserializeItemsLegacy(data, size);
    }

    // ---------- 旧格式兜底（升级前的老数据也能正常读） ----------

    private static String serializeItemsLegacy(ItemStack[] items) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null) yaml.set("s" + i, items[i]);
        }
        return yaml.saveToString();
    }

    private static ItemStack[] deserializeItemsLegacy(String data, int size) {
        ItemStack[] items = new ItemStack[size];
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(data);
            for (String key : yaml.getKeys(false)) {
                if (!key.startsWith("s")) continue;
                try {
                    int slot = Integer.parseInt(key.substring(1));
                    if (slot >= 0 && slot < size) items[slot] = yaml.getItemStack(key);
                } catch (NumberFormatException ignored) {
                    // 忽略历史脏数据
                }
            }
        } catch (InvalidConfigurationException e) {
            // 数据坏了就当空背包，不让玩家进不去服
        }
        return items;
    }

    // ------------------------- 统计信息 -------------------------

    /** 玩家统计 -> 文本（只存非零项，避免库体积爆炸） */
    public static String serializeStatistics(Player player) {
        YamlConfiguration yaml = new YamlConfiguration();

        ConfigurationSection single = yaml.createSection("single");
        for (Statistic stat : Statistic.values()) {
            try {
                int value = player.getStatistic(stat);
                if (value > 0) single.set(stat.name(), value);
            } catch (IllegalArgumentException ignored) {
                // 需要参数的统计，下面单独处理
            }
        }

        ConfigurationSection entity = yaml.createSection("entity");
        for (Statistic stat : ENTITY_STATS) {
            ConfigurationSection section = entity.createSection(stat.name());
            for (EntityType type : EntityType.values()) {
                try {
                    int value = player.getStatistic(stat, type);
                    if (value > 0) section.set(type.name(), value);
                } catch (IllegalArgumentException ignored) {
                    // 这个版本没有该统计项
                }
            }
        }

        ConfigurationSection material = yaml.createSection("material");
        for (Statistic stat : MATERIAL_STATS) {
            ConfigurationSection section = material.createSection(stat.name());
            for (Material type : Material.values()) {
                if (!type.isItem() && !type.isBlock()) continue;
                try {
                    int value = player.getStatistic(stat, type);
                    if (value > 0) section.set(type.name(), value);
                } catch (IllegalArgumentException ignored) {
                    // 这个版本没有该统计项
                }
            }
        }

        return yaml.saveToString();
    }

    /** 文本 -> 还原到玩家统计 */
    public static void applyStatistics(Player player, String data) {
        if (data == null || data.isEmpty()) return;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(data);

            ConfigurationSection single = yaml.getConfigurationSection("single");
            if (single != null) {
                for (String key : single.getKeys(false)) {
                    try {
                        Statistic stat = Statistic.valueOf(key);
                        player.setStatistic(stat, single.getInt(key));
                    } catch (IllegalArgumentException ignored) {
                        // 换版本后统计项改名/删除，跳过即可
                    }
                }
            }

            ConfigurationSection entity = yaml.getConfigurationSection("entity");
            if (entity != null) {
                for (String statName : entity.getKeys(false)) {
                    try {
                        Statistic stat = Statistic.valueOf(statName);
                        ConfigurationSection section = entity.getConfigurationSection(statName);
                        if (section == null) continue;
                        for (String typeName : section.getKeys(false)) {
                            try {
                                player.setStatistic(stat, EntityType.valueOf(typeName), section.getInt(typeName));
                            } catch (IllegalArgumentException ignored) {
                                // 旧版本生物名对不上，跳过
                            }
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 统计项不存在
                    }
                }
            }

            ConfigurationSection material = yaml.getConfigurationSection("material");
            if (material != null) {
                for (String statName : material.getKeys(false)) {
                    try {
                        Statistic stat = Statistic.valueOf(statName);
                        ConfigurationSection section = material.getConfigurationSection(statName);
                        if (section == null) continue;
                        for (String typeName : section.getKeys(false)) {
                            try {
                                player.setStatistic(stat, Material.valueOf(typeName), section.getInt(typeName));
                            } catch (IllegalArgumentException ignored) {
                                // 旧版本物品名对不上，跳过
                            }
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 统计项不存在
                    }
                }
            }
        } catch (InvalidConfigurationException e) {
            // 统计数据坏了不影响进服
        }
    }
}
