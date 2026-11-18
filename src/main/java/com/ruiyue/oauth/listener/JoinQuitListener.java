package com.ruiyue.oauth.listener;

import com.ruiyue.oauth.RiYueOauth;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 进服提示登录、退服保存数据 */
public class JoinQuitListener implements Listener {

    private final RiYueOauth plugin;

    public JoinQuitListener(RiYueOauth plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("riyueoauth.bypass")) return;

        plugin.getDataSync().hideInventory(player);
        if (plugin.getDatabase().isRegistered(player.getUniqueId().toString())) {
            player.sendMessage(plugin.msg("&e请输入 &a/l &f密码 登录"));
        } else {
            player.sendMessage(plugin.msg("&e首次进服，请输入 &a/zc &f密码 [邮箱] 注册"));
        }
        player.sendMessage(plugin.msg("&7未登录前无法移动、聊天、使用物品"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        cleanup(event.getPlayer());
    }

    private void cleanup(Player player) {
        if (plugin.getAuth().isLoggedIn(player)) {
            // 必须同步写库！玩家可能下一秒就连到 B 服，
            // 异步的话 B 服登录时会读到还没写入的旧背包，导致数据回退
            plugin.getDataSync().saveNow(player);
        } else {
            // 没登录就走了，把进服时暂存的东西还回去，别让人白丢装备
            plugin.getDataSync().restorePending(player);
        }
        plugin.getAuth().clear(player);
    }
}
