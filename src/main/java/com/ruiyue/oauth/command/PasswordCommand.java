package com.ruiyue.oauth.command;

import com.ruiyue.oauth.RiYueOauth;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /pw 邮箱            -> 获取验证码
 * /pw 验证码 新密码    -> 重置密码
 */
public class PasswordCommand implements CommandExecutor {

    private final RiYueOauth plugin;

    public PasswordCommand(RiYueOauth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.msg("&c这个命令只能玩家在服务器里用"));
            return true;
        }
        if (plugin.getAuth().onCooldown(player)) {
            player.sendMessage(plugin.msg("&c操作太快了，等一秒再试"));
            return true;
        }

        if (args.length == 1) {
            String email = args[0];
            if (!email.contains("@") || !email.contains(".")) {
                player.sendMessage(plugin.msg("&c用法: /pw 邮箱  或  /pw 验证码 新密码"));
                return true;
            }
            plugin.getAuth().requestReset(email, result -> {
                if (player.isOnline()) {
                    player.sendMessage(plugin.msg((result.success() ? "&a" : "&c") + result.message()));
                }
            });
            return true;
        }

        if (args.length == 2) {
            String code = args[0];
            String newPassword = args[1];
            int min = plugin.getConfig().getInt("password.min-length", 6);
            int max = plugin.getConfig().getInt("password.max-length", 32);
            if (newPassword.length() < min || newPassword.length() > max) {
                player.sendMessage(plugin.msg("&c新密码长度要在 " + min + "~" + max + " 位之间"));
                return true;
            }
            plugin.getAuth().confirmReset(code, newPassword, result -> {
                if (!player.isOnline()) return;
                player.sendMessage(plugin.msg((result.success() ? "&a" : "&c") + result.message()));
                if (result.success() && plugin.getAuth().isLoggedIn(player)) {
                    // 改完密码就把当前会话踢下线，必须重新登录
                    plugin.getDataSync().save(player);
                    plugin.getAuth().clear(player);
                    plugin.getDataSync().hideInventory(player);
                    player.sendMessage(plugin.msg("&e请用 /l 新密码 重新登录"));
                }
            });
            return true;
        }

        player.sendMessage(plugin.msg("&e用法: /pw 邮箱  或  /pw 验证码 新密码"));
        return true;
    }
}
