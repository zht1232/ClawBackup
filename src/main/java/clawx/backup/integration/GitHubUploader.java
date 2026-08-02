package clawx.backup.integration;

import clawx.backup.config.BackupConfig;
import clawx.backup.integration.http.Json;
import clawx.backup.integration.http.SimpleHttp;
import clawx.backup.integration.http.SimpleHttp.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * GitHub 云备份上传（Release + asset，支持大文件，最多 2GB）。
 */
public final class GitHubUploader {

    private GitHubUploader() {
    }

    public static void upload(BackupConfig config, Path zipFile) throws Exception {
        String token = config.getGithubToken();
        String repo = config.getGithubRepo();
        if (token.isEmpty()) throw new IllegalStateException("GitHub token 未配置");
        if (repo.isEmpty() || !repo.contains("/")) throw new IllegalStateException("GitHub repo 格式应为 owner/repo");

        String fileName = zipFile.getFileName().toString();
        String tag = "backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "token " + token);
        headers.put("Accept", "application/vnd.github+json");
        headers.put("User-Agent", "ClawBackup");

        // 1. 创建 Release
        String createPayload = Json.obj("tag_name", tag, "name", fileName, "draft", false);
        Response create = SimpleHttp.postJson(
                "https://api.github.com/repos/" + repo + "/releases", createPayload, headers);
        if (!create.isOk()) throw new IOException("创建 Release 失败 HTTP " + create.code + ": " + create.body);
        String uploadUrl = Json.getString(create.body, "upload_url");
        if (uploadUrl == null) throw new IOException("响应缺少 upload_url: " + create.body);

        // 去掉 {?name,label} 占位符
        int brace = uploadUrl.indexOf('{');
        if (brace >= 0) uploadUrl = uploadUrl.substring(0, brace);
        uploadUrl += "?name=" + URLEncoder.encode(fileName, "UTF-8");

        // 2. 上传 asset（文件原始字节）
        byte[] data = Files.readAllBytes(zipFile);
        Response up = SimpleHttp.postBytes(uploadUrl, data, "application/octet-stream", headers);
        if (!up.isOk()) throw new IOException("上传 asset 失败 HTTP " + up.code + ": " + up.body);
    }
}
