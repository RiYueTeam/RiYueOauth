package com.ruiyue.oauth.model;

/**
 * 玩家游戏数据的数据库记录。
 * 三块内容都是序列化后的文本，统一存在数据库里，
 * 换服/换设备登录时直接读回来。
 */
public record PlayerSnapshot(
        String uuid,
        String inventory,
        String enderChest,
        String statistics,
        long updatedAt
) {}
