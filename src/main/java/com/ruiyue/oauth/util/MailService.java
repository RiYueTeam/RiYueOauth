package com.ruiyue.oauth.util;

import com.ruiyue.oauth.RiYueOauth;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 极简邮件发送器（不依赖第三方库）。
 * 支持 465 隐式 SSL 和 587 STARTTLS。
 * 没配置邮箱时验证码会打在控制台，管理员手动转告玩家。
 */
public class MailService {

    private final RiYueOauth plugin;
    private final boolean enabled;
    private final String host;
    private final int port;
    private final boolean ssl;
    private final String username;
    private final String password;
    private final String from;

    public MailService(RiYueOauth plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("recovery.smtp.enabled", false);
        this.host = plugin.getConfig().getString("recovery.smtp.host", "smtp.qq.com");
        this.port = plugin.getConfig().getInt("recovery.smtp.port", 465);
        this.ssl = plugin.getConfig().getBoolean("recovery.smtp.ssl", true);
        this.username = plugin.getConfig().getString("recovery.smtp.username", "");
        this.password = plugin.getConfig().getString("recovery.smtp.password", "");
        this.from = plugin.getConfig().getString("recovery.smtp.from", username);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 发验证码，返回是否成功。这个方法要放在异步线程调用 */
    public boolean sendCode(String to, String code, int expireSeconds) {
        String subject = "【日月账号】找回密码验证码";
        String body = "你的验证码是：" + code
                + "\n" + expireSeconds + " 秒内有效，过期请重新获取。"
                + "\n如果不是你本人操作，请忽略这封邮件。";
        return send(to, subject, body);
    }

    private boolean send(String to, String subject, String body) {
        if (!enabled) return false;
        if (username.isEmpty() || password.isEmpty()) {
            plugin.getLogger().warning("邮箱未配置完整，验证码改由控制台输出");
            return false;
        }

        Socket socket = null;
        try {
            socket = ssl
                    ? SSLSocketFactory.getDefault().createSocket(host, port)
                    : new Socket(host, port);
            socket.setSoTimeout(10000);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            expect(readResponse(reader), "220");
            send(writer, "EHLO riyueoauth");
            String ehlo = readResponse(reader);
            expect(ehlo, "250");

            if (!ssl) {
                send(writer, "STARTTLS");
                expect(readResponse(reader), "220");
                socket = ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(socket, host, port, true);
                socket.setSoTimeout(10000);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                send(writer, "EHLO riyueoauth");
                expect(readResponse(reader), "250");
            }

            send(writer, "AUTH LOGIN");
            expect(readResponse(reader), "334");
            send(writer, b64(username));
            expect(readResponse(reader), "334");
            send(writer, b64(password));
            expect(readResponse(reader), "235");

            send(writer, "MAIL FROM:<" + from + ">");
            expect(readResponse(reader), "250");
            send(writer, "RCPT TO:<" + to + ">");
            expect(readResponse(reader), "250");
            send(writer, "DATA");
            expect(readResponse(reader), "354");

            writer.write("From: " + from + "\r\n");
            writer.write("To: " + to + "\r\n");
            writer.write("Subject: =?UTF-8?B?" + b64(subject) + "?=\r\n");
            writer.write("Content-Type: text/plain; charset=UTF-8\r\n");
            writer.write("Content-Transfer-Encoding: base64\r\n");
            writer.write("\r\n");
            writer.write(b64(body));
            writer.write("\r\n.\r\n");
            writer.flush();
            expect(readResponse(reader), "250");

            send(writer, "QUIT");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("发送找回密码邮件失败: " + e.getMessage());
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(BufferedWriter writer, String command) throws IOException {
        writer.write(command + "\r\n");
        writer.flush();
    }

    /** SMTP 响应可能是多行的，直到遇到 "250 " 这种形式才结束 */
    private static String readResponse(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
            if (line.length() < 4 || line.charAt(3) != '-') break;
        }
        return sb.toString();
    }

    private static void expect(String response, String code) throws IOException {
        if (!response.startsWith(code)) {
            throw new IOException("SMTP 期望 " + code + "，实际收到: " + response.trim());
        }
    }
}
