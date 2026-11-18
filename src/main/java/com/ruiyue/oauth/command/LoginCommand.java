package com.ruiyue.oauth.command;

import com.ruiyue.oauth.RiYueOauth;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /l 密码（/login 是别名）—— 登录账号 */
public class LoginCommand implements CommandExecutor {

    private final RiYueOauth plugin;

    public LoginCommand(RiYueOauth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.msg("&c这个命令只能玩家在服务器里用"));
            return true;
        }
        if (plugin.getAuth().isLoggedIn(player)) {
            player.sendMessage(plugin.msg("&c你已经登录了"));
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(plugin.msg("&e用法: /l <密码>"));
            return true;
        }
        if (plugin.getAuth().onCooldown(player)) {
            player.sendMessage(plugin.msg("&c操作太快了，等一秒再试"));
            return true;
        }

        plugin.getAuth().login(player, args[0], result -> {
            if (!player.isOnline()) return; // 验证期间掉线了，别再动他的数据
            player.sendMessage(plugin.msg((result.success() ? "&a" : "&c") + result.message()));
            if (result.success()) {
                plugin.getDataSync().loadAndApply(player, () -> plugin.getAuth().finishLogin(player));
            }
        });
        return true;
    }
}
