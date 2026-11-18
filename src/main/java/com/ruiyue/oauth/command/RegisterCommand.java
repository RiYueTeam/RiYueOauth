package com.ruiyue.oauth.command;

import com.ruiyue.oauth.RiYueOauth;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /zc 密码 [邮箱] —— 注册账号 */
public class RegisterCommand implements CommandExecutor {

    private final RiYueOauth plugin;

    public RegisterCommand(RiYueOauth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.msg("&c这个命令只能玩家在服务器里用"));
            return true;
        }
        if (plugin.getAuth().isLoggedIn(player)) {
            player.sendMessage(plugin.msg("&c你已经登录了，不用重复注册"));
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            player.sendMessage(plugin.msg("&e用法: /zc <密码> [邮箱]"));
            return true;
        }

        String password = args[0];
        String email = args.length == 2 ? args[1] : "";
        int min = plugin.getConfig().getInt("password.min-length", 6);
        int max = plugin.getConfig().getInt("password.max-length", 32);
        if (password.length() < min || password.length() > max) {
            player.sendMessage(plugin.msg("&c密码长度要在 " + min + "~" + max + " 位之间"));
            return true;
        }
        if (!email.isEmpty() && (!email.contains("@") || !email.contains("."))) {
            player.sendMessage(plugin.msg("&c邮箱格式不对，检查一下再试"));
            return true;
        }
        if (email.isEmpty() && plugin.getConfig().getBoolean("password.require-email", false)) {
            player.sendMessage(plugin.msg("&c服务器要求注册时必须填邮箱: /zc 密码 邮箱"));
            return true;
        }
        if (plugin.getAuth().onCooldown(player)) {
            player.sendMessage(plugin.msg("&c操作太快了，等一秒再试"));
            return true;
        }

        plugin.getAuth().register(player, password, email, result -> {
            if (!player.isOnline()) return; // 注册期间掉线了，别再动他的数据
            player.sendMessage(plugin.msg((result.success() ? "&a" : "&c") + result.message()));
            if (result.success()) {
                plugin.getDataSync().loadAndApply(player, () -> plugin.getAuth().finishLogin(player));
            }
        });
        return true;
    }
}
