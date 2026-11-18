package com.ruiyue.oauth;

import com.ruiyue.oauth.command.LoginCommand;
import com.ruiyue.oauth.command.LogoutCommand;
import com.ruiyue.oauth.command.PasswordCommand;
import com.ruiyue.oauth.command.RegisterCommand;
import com.ruiyue.oauth.listener.JoinQuitListener;
import com.ruiyue.oauth.listener.PlayerProtectListener;
import com.ruiyue.oauth.manager.AuthManager;
import com.ruiyue.oauth.manager.DataSyncManager;
import com.ruiyue.oauth.manager.DatabaseManager;
import de.tr7zw.changeme.nbtapi.NBT;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

/** 日月账号系统主类 */
public class RiYueOauth extends JavaPlugin {

    private DatabaseManager database;
    private AuthManager auth;
    private DataSyncManager dataSync;

    @Override
    public void onEnable() {
        saveDefaultConfig(); // 第一次启动生成 config.yml

        // 提前初始化 NBT 库（已重定位打包进来），失败就别带病运行
        if (!NBT.preloadApi()) {
            getLogger().severe("NBT 库初始化失败，插件已禁用");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        database = new DatabaseManager(this);
        try {
            database.init();
        } catch (SQLException e) {
            getLogger().severe("数据库初始化失败，插件已禁用: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        auth = new AuthManager(this);
        dataSync = new DataSyncManager(this);

        bindCommand("zc", new RegisterCommand(this));
        bindCommand("l", new LoginCommand(this));
        bindCommand("pw", new PasswordCommand(this));
        bindCommand("zx", new LogoutCommand(this));

        getServer().getPluginManager().registerEvents(new JoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerProtectListener(this), this);

        startAutoSave();
        getLogger().info("RiYueOauth 已启动：/zc 注册 /l 登录 /pw 找回 /zx 退出");
    }

    @Override
    public void onDisable() {
        // 先停掉本插件所有定时任务，避免异步写库和关连接打架
        Bukkit.getScheduler().cancelTasks(this);
        if (dataSync != null && auth != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (auth.isLoggedIn(player)) dataSync.saveNow(player);
            }
        }
        if (database != null) database.close();
        getLogger().info("RiYueOauth 已关闭，数据已保存");
    }

    private void bindCommand(String name, CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("命令 " + name + " 未注册，请检查 plugin.yml");
            return;
        }
        command.setExecutor(executor);
    }

    private void startAutoSave() {
        int seconds = getConfig().getInt("data-sync.auto-save-interval-seconds", 300);
        if (seconds <= 0) return;
        long ticks = seconds * 20L;
        // 定时回到主线程收集数据，再丢异步写库
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (auth.isLoggedIn(player) && !player.hasPermission("riyueoauth.bypass")) {
                    dataSync.save(player);
                }
            }
        }, ticks, ticks);
    }

    /** 统一加前缀 + 处理 & 颜色代码 */
    public String msg(String text) {
        String prefix = getConfig().getString("prefix", "&8[&b日月&8] &r");
        return ChatColor.translateAlternateColorCodes('&', prefix + text);
    }

    public DatabaseManager getDatabase() {
        return database;
    }

    public AuthManager getAuth() {
        return auth;
    }

    public DataSyncManager getDataSync() {
        return dataSync;
    }
}
