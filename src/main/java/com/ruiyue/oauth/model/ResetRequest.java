package com.ruiyue.oauth.model;

/** 一次找回密码请求（邮箱 -> 验证码） */
public record ResetRequest(
        String email,
        String code,
        String uuid,
        long createdAt
) {}
