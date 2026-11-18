package com.ruiyue.oauth.command;

import com.ruiyue.oauth.RiYueOauth;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /zx —— 退出登录，先保存数据再冻结账号 */
public class LogoutCommand implements CommandExecutor {

    private final RiYueOauth plugin;

    public LogoutCommand(RiYueOauth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.msg("&c这个命令只能玩家在服务器里用"));
            return true;
        }
        if (!plugin.getAuth().isLoggedIn(player)) {
            player.sendMessage(plugin.msg("&c你还没登录"));
            return true;
        }

        plugin.getDataSync().save(player);   // 先把身上的东西落库
        plugin.getAuth().clear(player);      // 撤销登录状态
        plugin.getDataSync().hideInventory(player); // 再藏起来，防止被人看/拿
        player.sendMessage(plugin.msg("&a已退出登录，用 /l 密码 重新登录"));
        return true;
    }
}
