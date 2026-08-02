package clawx.backup.integration;

import clawx.backup.config.BackupConfig;
import clawx.backup.util.Message;

import java.nio.file.Path;

/**
 * 云备份上传统一入口（当前支持：GitHub、百度网盘）。
 */
public final class CloudUploader {

    private CloudUploader() {
    }

    /** 备份完成后调用，将备份文件上传到已启用的云端（逐平台尝试，互不影响） */
    public static void upload(BackupConfig config, Path zipFile) {
        if (!config.isCloudBackupEnabled()) return;

        if (config.isGithubEnabled()) {
            try {
                GitHubUploader.upload(config, zipFile);
                Message.log("§e[云备份] §a✔ GitHub 上传成功: §f" + zipFile.getFileName());
            } catch (Exception e) {
                Message.log("§c[云备份] §4GitHub 上传失败: " + e.getMessage());
            }
        }
        if (config.isBaiduEnabled()) {
            try {
                BaiduUploader.upload(config, zipFile);
                Message.log("§e[云备份] §a✔ 百度网盘上传成功: §f" + zipFile.getFileName());
            } catch (Exception e) {
                Message.log("§c[云备份] §4百度网盘上传失败: " + e.getMessage());
            }
        }
    }
}
