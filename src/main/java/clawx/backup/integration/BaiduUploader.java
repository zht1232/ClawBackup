package clawx.backup.integration;

import clawx.backup.config.BackupConfig;
import clawx.backup.integration.http.Json;
import clawx.backup.integration.http.SimpleHttp;
import clawx.backup.integration.http.SimpleHttp.Response;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 百度网盘云备份上传（PCS superfile2 分片上传，每片 4MB）。
 * <p>
 * 使用前提：需在百度网盘开放平台申请开发者应用，完成 OAuth 授权获取 access_token，
 * 并配置 config.yml 中的 cloud-backup.baidu.*。
 */
public final class BaiduUploader {

    private static final int BLOCK = 4 * 1024 * 1024;
    private static final String API = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2";

    private BaiduUploader() {
    }

    public static void upload(BackupConfig config, Path zipFile) throws Exception {
        String token = config.getBaiduAccessToken();
        String dir = config.getBaiduDir();
        if (token.isEmpty()) throw new IllegalStateException("百度网盘 access-token 未配置");
        String path = (dir.endsWith("/") ? dir : dir + "/") + zipFile.getFileName();
        long size = Files.size(zipFile);

        // 计算每个分片的 MD5（供 precreate 使用）
        List<String> blockMd5s = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(zipFile))) {
            byte[] buf = new byte[BLOCK];
            int read;
            while ((read = readFully(in, buf)) > 0) {
                blockMd5s.add(md5(buf, read));
            }
        }
        String blockListJson = Json.arr(blockMd5s.toArray());

        // 1. precreate
        String preUrl = API + "?method=precreate&access_token=" + enc(token)
                + "&path=" + enc(path) + "&size=" + size
                + "&block_list=" + enc(blockListJson) + "&autoinit=1";
        Response pre = SimpleHttp.get(preUrl, null);
        if (!pre.isOk()) throw new IOException("precreate HTTP " + pre.code + ": " + pre.body);
        String uploadid = Json.getString(pre.body, "uploadid");
        if (uploadid == null || uploadid.isEmpty()) throw new IOException("precreate 失败: " + pre.body);

        // 2. 逐片上传
        List<String> md5s = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(zipFile))) {
            byte[] buf = new byte[BLOCK];
            int partseq = 0;
            int read;
            while ((read = readFully(in, buf)) > 0) {
                String upUrl = API + "?method=upload&access_token=" + enc(token)
                        + "&type=tmpfile&path=" + enc(path)
                        + "&uploadid=" + enc(uploadid) + "&partseq=" + partseq;
                Response up = SimpleHttp.postBytes(upUrl, Arrays.copyOf(buf, read),
                        "application/octet-stream", null);
                if (!up.isOk()) throw new IOException("upload 分片 " + partseq + " HTTP " + up.code + ": " + up.body);
                String md5 = Json.getString(up.body, "md5");
                if (md5 == null) throw new IOException("upload 分片响应异常: " + up.body);
                md5s.add(md5);
                partseq++;
            }
        }

        // 3. create 合并
        String createUrl = API + "?method=create&access_token=" + enc(token)
                + "&path=" + enc(path) + "&size=" + size
                + "&uploadid=" + enc(uploadid)
                + "&block_list=" + enc(Json.arr(md5s.toArray()));
        Response create = SimpleHttp.get(createUrl, null);
        if (!create.isOk()) throw new IOException("create HTTP " + create.code + ": " + create.body);
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static String md5(byte[] data, int len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(data, 0, len);
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }
}
