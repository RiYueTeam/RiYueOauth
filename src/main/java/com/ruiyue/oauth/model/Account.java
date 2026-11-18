package com.ruiyue.oauth.model;

/** 一个账号的数据库记录 */
public record Account(
        String uuid,
        String username,
        String passwordHash,
        String salt,
        String email,
        long registeredAt,
        long lastLogin
) {}
