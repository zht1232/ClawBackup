package clawx.backup.integration;

import clawx.backup.config.BackupConfig;
import clawx.backup.integration.http.Json;
import clawx.backup.integration.http.SimpleHttp;
import clawx.backup.integration.http.SimpleHttp.Response;
import clawx.backup.util.Message;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Base64;

/**
 * 告警通知统一入口：邮件 / 飞书 / 钉钉。
 */
public final class NotificationManager {

    private NotificationManager() {
    }

    /** 备份开始通知（由 notify.on-backup-start 控制） */
    public static void notifyStart(BackupConfig config, String content) {
        if (!config.isNotifyOnBackupStart()) return;
        sendAll(config, "【ClawBackup】备份开始", content);
    }

    /** 备份结果通知（成功/失败，由 notify.on-backup-success/failure 控制） */
    public static void notifyResult(BackupConfig config, boolean success, String title, String content) {
        boolean enabled = success ? config.isNotifyOnBackupSuccess() : config.isNotifyOnBackupFailure();
        if (!enabled) return;
        sendAll(config, title, content);
    }

    /** 按配置把内容发送到所有已启用的渠道 */
    private static void sendAll(BackupConfig config, String title, String content) {
        String full = title + "\n" + content;

        if (config.isEmailEnabled()) {
            try {
                EmailNotifier.send(config, title, content);
                Message.log("§e[通知] §a✔ 邮件已发送");
            } catch (Exception e) {
                Message.log("§c[通知] §4邮件发送失败: " + e.getMessage());
            }
        }
        if (config.isFeishuEnabled()) {
            try {
                sendFeishu(config.getFeishuWebhook(), full);
                Message.log("§e[通知] §a✔ 飞书已发送");
            } catch (Exception e) {
                Message.log("§c[通知] §4飞书发送失败: " + e.getMessage());
            }
        }
        if (config.isDingtalkEnabled()) {
            try {
                sendDingtalk(config.getDingtalkWebhook(), config.getDingtalkSecret(), full);
                Message.log("§e[通知] §a✔ 钉钉已发送");
            } catch (Exception e) {
                Message.log("§c[通知] §4钉钉发送失败: " + e.getMessage());
            }
        }
    }

    // ===== 飞书 =====
    private static void sendFeishu(String webhook, String text) throws IOException {
        String payload = Json.obj("msg_type", "text", "content", Json.raw(Json.obj("text", text)));
        Response r = SimpleHttp.postJson(webhook, payload, null);
        if (!r.isOk()) throw new IOException("HTTP " + r.code + ": " + r.body);
    }

    // ===== 钉钉（支持加签）=====
    private static void sendDingtalk(String webhook, String secret, String text) throws Exception {
        String url = webhook;
        if (secret != null && !secret.isEmpty()) {
            long ts = System.currentTimeMillis();
            String stringToSign = ts + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes("UTF-8"));
            String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), "UTF-8");
            url = webhook + (webhook.contains("?") ? "&" : "?") + "timestamp=" + ts + "&sign=" + sign;
        }
        String payload = Json.obj("msgtype", "text", "text", Json.raw(Json.obj("content", text)));
        Response r = SimpleHttp.postJson(url, payload, null);
        if (!r.isOk()) throw new IOException("HTTP " + r.code + ": " + r.body);
    }
}
