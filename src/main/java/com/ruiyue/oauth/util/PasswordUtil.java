package com.ruiyue.oauth.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** 密码哈希：PBKDF2 加盐，数据库里不存明文密码 */
public final class PasswordUtil {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {}

    /** 生成随机盐，返回 Base64 文本 */
    public static String generateSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /** 用密码 + 盐算出哈希，返回 Base64 文本 */
    public static String hash(String password, String salt) {
        try {
            byte[] saltBytes = Base64.getDecoder().decode(salt);
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(), saltBytes, ITERATIONS, KEY_LENGTH);
            byte[] hash = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希计算失败", e);
        }
    }

    /** 校验密码，用常量时间比较防止时序侧信道 */
    public static boolean verify(String password, String salt, String expectedHash) {
        String actual = hash(password, salt);
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    /** 密码长度是否合规 */
    public static boolean lengthOk(String password, int min, int max) {
        int len = password == null ? 0 : password.length();
        return len >= min && len <= max;
    }
}
