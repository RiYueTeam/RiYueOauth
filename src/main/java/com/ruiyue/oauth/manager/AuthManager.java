package com.ruiyue.oauth.manager;

import com.ruiyue.oauth.RiYueOauth;
import com.ruiyue.oauth.model.Account;
import com.ruiyue.oauth.model.ResetRequest;
import com.ruiyue.oauth.util.MailService;
import com.ruiyue.oauth.util.PasswordUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 注册 / 登录 / 找回密码的业务逻辑 */
public class AuthManager {

    /** 结果：成功与否 + 给玩家看的话；后面几个字段只给找回密码流程用 */
    public record AuthResult(boolean success, String message,
                             String code, int expire, String name, String email) {
        public AuthResult(boolean success, String message) {
            this(success, message, "", 0, "", "");
        }
        // 找回密码专用：success 在这里表示"邮件是否发成功"
        public AuthResult(boolean sent, String code, int expire, String name, String email) {
            this(sent, "", code, expire, name, email);
        }
    }

    private final RiYueOauth plugin;
    private final MailService mailService;
    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastAttempt = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    public AuthManager(RiYueOauth plugin) {
        this.plugin = plugin;
        this.mailService = new MailService(plugin);
    }

    public boolean isLoggedIn(Player player) {
        return loggedIn.contains(player.getUniqueId());
    }

    public void markLoggedIn(Player player) {
        loggedIn.add(player.getUniqueId());
    }

    public void clear(Player player) {
        loggedIn.remove(player.getUniqueId());
        lastAttempt.remove(player.getUniqueId());
    }

    /** 简单的 1 秒冷却，防刷命令 */
    public boolean onCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastAttempt.get(player.getUniqueId());
        if (last != null && now - last < 1000) return true;
        lastAttempt.put(player.getUniqueId(), now);
        return false;
    }

    // ------------------------------------------------------------------
    // 注册 /zc
    // ------------------------------------------------------------------

    public void register(Player player, String password, String email, Consumer<AuthResult> callback) {
        String uuid = player.getUniqueId().toString();

        if (plugin.getDatabase().isRegistered(uuid)) {
            callback.accept(new AuthResult(false, "你已经注册过了，直接 /l 密码 登录"));
            return;
        }
        Account sameName = plugin.getDatabase().getAccountByUsername(player.getName());
        if (sameName != null && !sameName.uuid().equals(uuid)) {
            callback.accept(new AuthResult(false, "这个用户名已被占用，请联系管理员"));
            return;
        }
        if (email != null && !email.isEmpty()) {
            Account sameEmail = plugin.getDatabase().getAccountByEmail(email);
            if (sameEmail != null) {
                callback.accept(new AuthResult(false, "这个邮箱已经被注册过了"));
                return;
            }
        }

        // 名字先在主线程取，别在异步线程碰 Bukkit 对象
        String name = player.getName();

        // 哈希运算放异步，避免卡住服务器主线程
        runAsync(() -> {
            String salt = PasswordUtil.generateSalt();
            String hash = PasswordUtil.hash(password, salt);
            long now = System.currentTimeMillis();
            boolean ok = plugin.getDatabase().createAccount(
                    new Account(uuid, name, hash, salt, email, now, now));
            return ok
                    ? new AuthResult(true, "注册成功！你已自动登录，数据开始同步")
                    : new AuthResult(false, "注册失败，数据库写入出错，请联系管理员");
        }, callback);
    }

    // ------------------------------------------------------------------
    // 登录 /l /login
    // ------------------------------------------------------------------

    public void login(Player player, String password, Consumer<AuthResult> callback) {
        String uuid = player.getUniqueId().toString();
        Account account = plugin.getDatabase().getAccountByUuid(uuid);

        if (account == null) {
            callback.accept(new AuthResult(false, "你还没注册，请先输入 /zc 密码 [邮箱]"));
            return;
        }
        if (isLoggedIn(player)) {
            callback.accept(new AuthResult(false, "你已经登录了"));
            return;
        }

        runAsync(() -> {
            if (!PasswordUtil.verify(password, account.salt(), account.passwordHash())) {
                return new AuthResult(false, "密码错误，再试一次");
            }
            plugin.getDatabase().updateLastLogin(uuid, System.currentTimeMillis());
            return new AuthResult(true, "登录成功！");
        }, callback);
    }

    /** 登录/注册成功后的收尾：标记、回荣誉玩家、传送 */
    public void finishLogin(Player player) {
        markLoggedIn(player);
        teleportIfConfigured(player);
        player.sendMessage(plugin.msg("&a欢迎回来，&e" + player.getName() + " &a！"));
    }

    private void teleportIfConfigured(Player player) {
        if (!plugin.getConfig().getBoolean("login.teleport-on-login", false)) return;
        World world = Bukkit.getWorld(plugin.getConfig().getString("login.teleport-world", "world"));
        if (world == null) return;
        double x = plugin.getConfig().getDouble("login.teleport-x", 0);
        double y = plugin.getConfig().getDouble("login.teleport-y", 64);
        double z = plugin.getConfig().getDouble("login.teleport-z", 0);
        player.teleport(new Location(world, x, y, z));
    }

    // ------------------------------------------------------------------
    // 找回密码 /pw
    // ------------------------------------------------------------------

    /** 第一步：/pw 邮箱 -> 发验证码 */
    public void requestReset(String email, Consumer<AuthResult> callback) {
        Account account = plugin.getDatabase().getAccountByEmail(email);
        if (account == null) {
            // 不告诉对方这个邮箱有没有注册，防止被人拿来撞库
            callback.accept(new AuthResult(true, "如果该邮箱已注册，验证码已发出"));
            return;
        }

        String code = String.valueOf(100000 + random.nextInt(900000));
        long now = System.currentTimeMillis();
        plugin.getDatabase().saveResetCode(email, code, account.uuid(), now);
        String name = playerName(account); // 主线程先取名字，别在异步线程碰 Bukkit API

        runAsync(() -> {
            int expire = plugin.getConfig().getInt("recovery.code-expire-seconds", 300);
            boolean sent = mailService.sendCode(email, code, expire);
            return new AuthResult(sent, code, expire, name, email);
        }, result -> {
            // 回到主线程了，这里碰 Bukkit API 是安全的
            if (result.success()) {
                callback.accept(new AuthResult(true,
                        "验证码已发到 " + email + "，有效期 " + result.expire() + " 秒"));
            } else {
                Bukkit.getConsoleSender().sendMessage(
                        plugin.msg("&c[找回密码] " + name + " 的验证码: &e" + code
                                + " &c（邮箱未配置或发送失败）"));
                callback.accept(new AuthResult(true, "验证码已生成，请联系管理员获取（控制台可见）"));
            }
        });
    }

    /** 第二步：/pw 验证码 新密码 -> 重置 */
    public void confirmReset(String code, String newPassword, Consumer<AuthResult> callback) {
        ResetRequest request = plugin.getDatabase().getResetByCode(code);
        if (request == null) {
            callback.accept(new AuthResult(false, "验证码错误或已过期"));
            return;
        }
        int expire = plugin.getConfig().getInt("recovery.code-expire-seconds", 300);
        if (System.currentTimeMillis() - request.createdAt() > expire * 1000L) {
            plugin.getDatabase().deleteResetCode(request.email());
            callback.accept(new AuthResult(false, "验证码已过期，请重新获取"));
            return;
        }

        runAsync(() -> {
            String salt = PasswordUtil.generateSalt();
            String hash = PasswordUtil.hash(newPassword, salt);
            boolean ok = plugin.getDatabase().updatePassword(request.uuid(), hash, salt);
            if (!ok) return new AuthResult(false, "重置失败，请联系管理员");
            plugin.getDatabase().deleteResetCode(request.email());
            return new AuthResult(true, "密码已重置，请用 /l 新密码 登录");
        }, callback);
    }

    private String playerName(Account account) {
        Player online = Bukkit.getPlayer(UUID.fromString(account.uuid()));
        return online != null ? online.getName() : account.username();
    }

    // ------------------------------------------------------------------

    /** 异步跑耗时操作，结果回到主线程再交给调用方 */
    private void runAsync(java.util.function.Supplier<AuthResult> work, Consumer<AuthResult> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            AuthResult result;
            try {
                result = work.get();
            } catch (Exception e) {
                plugin.getLogger().warning("账号操作异常: " + e.getMessage());
                result = new AuthResult(false, "操作失败，请联系管理员");
            }
            AuthResult finalResult = result;
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(finalResult));
        });
    }
}
