package clawx.backup.integration.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * 极简 HTTP 客户端（基于 HttpURLConnection，无第三方依赖）。
 */
public final class SimpleHttp {

    public static final int TIMEOUT_MS = 30000;

    private SimpleHttp() {
    }

    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        public boolean isOk() {
            return code >= 200 && code < 300;
        }
    }

    public static Response postJson(String url, String json, Map<String, String> headers) throws IOException {
        return send("POST", url, json.getBytes("UTF-8"), "application/json; charset=utf-8", headers);
    }

    public static Response postBytes(String url, byte[] data, String contentType, Map<String, String> headers) throws IOException {
        return send("POST", url, data, contentType, headers);
    }

    /** 流式上传（避免把大文件整个读入内存），contentLength 需为实际字节数 */
    public static Response postStream(String url, long contentLength, InputStream in,
                                      String contentType, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS * 4);
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            conn.setFixedLengthStreamingMode(contentLength);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Response(code, readAll(is));
        } finally {
            conn.disconnect();
        }
    }

    public static Response get(String url, Map<String, String> headers) throws IOException {
        return send("GET", url, null, null, headers);
    }

    private static Response send(String method, String url, byte[] data, String contentType,
                                 Map<String, String> headers) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (data != null && data.length > 0) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(data);
                }
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Response(code, readAll(is));
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        }
    }
}
