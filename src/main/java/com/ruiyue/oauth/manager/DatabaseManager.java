package com.ruiyue.oauth.manager;

import com.ruiyue.oauth.RiYueOauth;
import com.ruiyue.oauth.model.Account;
import com.ruiyue.oauth.model.PlayerSnapshot;
import com.ruiyue.oauth.model.ResetRequest;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库管理：支持两种模式，让多台服务器共用同一份账号和背包数据。
 *
 * - sqlite：所有服在同一台机器，指向同一个 .db 文件。简单，但多服同时写会有锁竞争。
 * - mysql ：所有服（可跨机器）连同一个 MySQL/MariaDB 库。跨服推荐这个。
 *
 * 所有方法加锁并做断线重连，因为 SQLite 不适合并发写、MySQL 连接也可能超时断开。
 */
public class DatabaseManager {

    private final RiYueOauth plugin;
    private Connection connection;
    private boolean mysql;
    private String jdbcUrl;
    private String dbUser;
    private String dbPass;

    public DatabaseManager(RiYueOauth plugin) {
        this.plugin = plugin;
    }

    /** 打开数据库并建表；失败时抛异常让插件停止加载，避免带病运行 */
    public void init() throws SQLException {
        String type = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
        this.mysql = type.equals("mysql") || type.equals("mariadb");

        if (mysql) {
            initMySql();
        } else {
            initSqlite();
        }
        createTables();
        plugin.getLogger().info("数据库已就绪（" + (mysql ? "MySQL" : "SQLite") + "）"
                + (mysql ? ": " + plugin.getConfig().getString("database.mysql.database") : ""));
    }

    private void initSqlite() throws SQLException {
        String configured = plugin.getConfig().getString("database.file", "plugins/RiYueOauth/database.db");
        File file = new File(configured);
        if (!file.isAbsolute()) {
            file = new File(".", configured); // 相对服务器根目录
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("无法创建数据库目录: " + parent.getAbsolutePath());
        }
        jdbcUrl = "jdbc:sqlite:" + file.getAbsolutePath();
        connection = DriverManager.getConnection(jdbcUrl);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout = 10000");
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA foreign_keys = ON");
        }
        plugin.getLogger().info("SQLite 文件: " + file.getAbsolutePath());
    }

    private void initMySql() throws SQLException {
        String host = plugin.getConfig().getString("database.mysql.host", "localhost");
        int port = plugin.getConfig().getInt("database.mysql.port", 3306);
        String database = plugin.getConfig().getString("database.mysql.database", "riyueoauth");
        dbUser = plugin.getConfig().getString("database.mysql.username", "root");
        dbPass = plugin.getConfig().getString("database.mysql.password", "");
        String params = plugin.getConfig().getString("database.mysql.params",
                "useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                        + "&useSSL=false&allowPublicKeyRetrieval=true");
        jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database + "?" + params;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("找不到 MySQL 驱动，检查 jar 是否完整", e);
        }
        connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
        plugin.getLogger().info("已连接 MySQL: " + host + ":" + port + "/" + database);
    }

    /** 建表。两边语法不同：MySQL 要指定引擎/字符集和字段长度，SQLite 用 COLLATE NOCASE */
    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            if (mysql) {
                st.execute("CREATE TABLE IF NOT EXISTS accounts (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "username VARCHAR(64) NOT NULL UNIQUE, " +
                        "password_hash VARCHAR(255) NOT NULL, " +
                        "salt VARCHAR(255) NOT NULL, " +
                        "email VARCHAR(255) DEFAULT NULL, " +
                        "registered_at BIGINT NOT NULL, " +
                        "last_login BIGINT NOT NULL DEFAULT 0, " +
                        "KEY idx_accounts_email (email)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

                st.execute("CREATE TABLE IF NOT EXISTS player_data (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "inventory MEDIUMTEXT, " +
                        "enderchest MEDIUMTEXT, " +
                        "statistics MEDIUMTEXT, " +
                        "updated_at BIGINT NOT NULL" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

                st.execute("CREATE TABLE IF NOT EXISTS pw_reset (" +
                        "email VARCHAR(255) PRIMARY KEY, " +
                        "code VARCHAR(32) NOT NULL, " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "created_at BIGINT NOT NULL" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            } else {
                st.execute("CREATE TABLE IF NOT EXISTS accounts (" +
                        "uuid TEXT PRIMARY KEY, " +
                        "username TEXT NOT NULL UNIQUE COLLATE NOCASE, " +
                        "password_hash TEXT NOT NULL, " +
                        "salt TEXT NOT NULL, " +
                        "email TEXT COLLATE NOCASE, " +
                        "registered_at INTEGER NOT NULL, " +
                        "last_login INTEGER NOT NULL DEFAULT 0" +
                        ")");

                st.execute("CREATE INDEX IF NOT EXISTS idx_accounts_email ON accounts(email)");

                st.execute("CREATE TABLE IF NOT EXISTS player_data (" +
                        "uuid TEXT PRIMARY KEY, " +
                        "inventory TEXT, " +
                        "enderchest TEXT, " +
                        "statistics TEXT, " +
                        "updated_at INTEGER NOT NULL" +
                        ")");

                st.execute("CREATE TABLE IF NOT EXISTS pw_reset (" +
                        "email TEXT PRIMARY KEY COLLATE NOCASE, " +
                        "code TEXT NOT NULL, " +
                        "uuid TEXT NOT NULL, " +
                        "created_at INTEGER NOT NULL" +
                        ")");
            }
        }
    }

    /** 每次操作前确保连接可用，MySQL 长时间空闲会被服务端掐断 */
    private synchronized Connection conn() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(3)) {
            plugin.getLogger().warning("数据库连接已断开，正在重连...");
            connection = mysql
                    ? DriverManager.getConnection(jdbcUrl, dbUser, dbPass)
                    : DriverManager.getConnection(jdbcUrl);
        }
        return connection;
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().warning("关闭数据库失败: " + e.getMessage());
            }
            connection = null;
        }
    }

    // ------------------------------------------------------------------
    // 账号
    // ------------------------------------------------------------------

    public synchronized Account getAccountByUuid(String uuid) {
        return queryOne("SELECT * FROM accounts WHERE uuid = ?", uuid);
    }

    public synchronized Account getAccountByUsername(String username) {
        // MySQL 的 utf8mb4 默认不区分大小写，无需 COLLATE NOCASE
        return queryOne("SELECT * FROM accounts WHERE username = ?", username);
    }

    public synchronized Account getAccountByEmail(String email) {
        return queryOne("SELECT * FROM accounts WHERE email = ?", email);
    }

    public synchronized boolean isRegistered(String uuid) {
        return getAccountByUuid(uuid) != null;
    }

    public synchronized boolean createAccount(Account account) {
        // SQLite 用 INSERT OR IGNORE，MySQL 用 INSERT IGNORE
        String sql = (mysql ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ") + "accounts " +
                "(uuid, username, password_hash, salt, email, registered_at, last_login) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, account.uuid());
            ps.setString(2, account.username());
            ps.setString(3, account.passwordHash());
            ps.setString(4, account.salt());
            ps.setString(5, account.email());
            ps.setLong(6, account.registeredAt());
            ps.setLong(7, account.lastLogin());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("创建账号失败: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean updatePassword(String uuid, String hash, String salt) {
        return update("UPDATE accounts SET password_hash = ?, salt = ? WHERE uuid = ?",
                hash, salt, uuid);
    }

    public synchronized boolean updateEmail(String uuid, String email) {
        return update("UPDATE accounts SET email = ? WHERE uuid = ?", email, uuid);
    }

    public synchronized void updateLastLogin(String uuid, long time) {
        update("UPDATE accounts SET last_login = ? WHERE uuid = ?", time, uuid);
    }

    // ------------------------------------------------------------------
    // 游戏数据（背包 / 末影箱 / 统计）
    // ------------------------------------------------------------------

    public synchronized PlayerSnapshot getSnapshot(String uuid) {
        try (PreparedStatement ps = conn().prepareStatement("SELECT * FROM player_data WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new PlayerSnapshot(
                        rs.getString("uuid"),
                        rs.getString("inventory"),
                        rs.getString("enderchest"),
                        rs.getString("statistics"),
                        rs.getLong("updated_at")
                );
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("读取玩家数据失败: " + e.getMessage());
            return null;
        }
    }

    public synchronized boolean saveSnapshot(PlayerSnapshot snapshot) {
        // 两种数据库的 upsert 语法不同
        String sql = mysql
                ? "INSERT INTO player_data (uuid, inventory, enderchest, statistics, updated_at) " +
                  "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE " +
                  "inventory = VALUES(inventory), enderchest = VALUES(enderchest), " +
                  "statistics = VALUES(statistics), updated_at = VALUES(updated_at)"
                : "INSERT INTO player_data (uuid, inventory, enderchest, statistics, updated_at) " +
                  "VALUES (?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET " +
                  "inventory = excluded.inventory, enderchest = excluded.enderchest, " +
                  "statistics = excluded.statistics, updated_at = excluded.updated_at";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, snapshot.uuid());
            ps.setString(2, snapshot.inventory());
            ps.setString(3, snapshot.enderChest());
            ps.setString(4, snapshot.statistics());
            ps.setLong(5, snapshot.updatedAt());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("保存玩家数据失败: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 找回密码验证码
    // ------------------------------------------------------------------

    public synchronized void saveResetCode(String email, String code, String uuid, long createdAt) {
        String sql = mysql
                ? "INSERT INTO pw_reset (email, code, uuid, created_at) VALUES (?, ?, ?, ?) " +
                  "ON DUPLICATE KEY UPDATE code = VALUES(code), uuid = VALUES(uuid), created_at = VALUES(created_at)"
                : "INSERT INTO pw_reset (email, code, uuid, created_at) VALUES (?, ?, ?, ?) " +
                  "ON CONFLICT(email) DO UPDATE SET code = excluded.code, uuid = excluded.uuid, created_at = excluded.created_at";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, code);
            ps.setString(3, uuid);
            ps.setLong(4, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("保存验证码失败: " + e.getMessage());
        }
    }

    public synchronized ResetRequest getResetCode(String email) {
        return queryReset("SELECT * FROM pw_reset WHERE email = ?", email);
    }

    public synchronized ResetRequest getResetByCode(String code) {
        return queryReset("SELECT * FROM pw_reset WHERE code = ?", code);
    }

    public synchronized void deleteResetCode(String email) {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM pw_reset WHERE email = ?")) {
            ps.setString(1, email);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("删除验证码失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private boolean update(String sql, Object... params) {
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("数据库更新失败: " + e.getMessage());
            return false;
        }
    }

    private Account queryOne(String sql, String param) {
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Account(
                        rs.getString("uuid"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("salt"),
                        rs.getString("email"),
                        rs.getLong("registered_at"),
                        rs.getLong("last_login")
                );
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询账号失败: " + e.getMessage());
            return null;
        }
    }

    private ResetRequest queryReset(String sql, String param) {
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ResetRequest(
                        rs.getString("email"),
                        rs.getString("code"),
                        rs.getString("uuid"),
                        rs.getLong("created_at")
                );
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询验证码失败: " + e.getMessage());
            return null;
        }
    }
}
