package clawx.backup.integration.http;

/**
 * 极简 JSON 构建与字段提取（无第三方依赖，够用于 webhook / API 请求）。
 */
public final class Json {

    private Json() {
    }

    /** JSON 字符串转义 */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 构建 JSON 对象 {"a":"b","n":1}，参数为交替的 key/value */
    public static String obj(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(escape(String.valueOf(kv[i]))).append("\":");
            sb.append(value(kv[i + 1]));
        }
        return sb.append('}').toString();
    }

    /** 构建 JSON 数组 ["a",1] */
    public static String arr(Object... items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(value(items[i]));
        }
        return sb.append(']').toString();
    }

    /**
     * 包装一段已是合法 JSON 的字符串，使其在 obj/arr 中原样注入（不做字符串转义）。
     * 用于嵌套对象/数组，例如: Json.obj("content", Json.raw(Json.obj("text", "hi")))。
     */
    public static Object raw(String json) {
        return new Raw(json);
    }

    /** 内部包装：标记该字符串应原样输出，不转义 */
    private static final class Raw {
        final String json;
        Raw(String json) { this.json = json; }
    }

    private static String value(Object v) {
        if (v instanceof Raw) return ((Raw) v).json;
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return '"' + escape(v.toString()) + '"';
    }

    /**
     * 从 JSON 响应中提取字段值（字符串/数字/布尔/嵌套对象）。
     * 通过查找 "field": 实现，够用于常见 API 响应解析。
     */
    public static String getString(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;
        char c = json.charAt(start);
        if (c == '"') {
            int end = json.indexOf('"', start + 1);
            if (end < 0) return null;
            return unescape(json.substring(start + 1, end));
        } else if (c == '{' || c == '[') {
            int depth = 0;
            boolean inStr = false;
            for (int i = start; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (inStr) {
                    if (ch == '\\') i++;
                    else if (ch == '"') inStr = false;
                    continue;
                }
                if (ch == '"') inStr = true;
                else if (ch == '{' || ch == '[') depth++;
                else if (ch == '}' || ch == ']') {
                    depth--;
                    if (depth == 0) return json.substring(start, i + 1);
                }
            }
            return json.substring(start);
        }
        int end = start;
        while (end < json.length()
                && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') {
            end++;
        }
        return json.substring(start, end).trim();
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                sb.append('u');
                            }
                        } else sb.append('u');
                        break;
                    default: sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }
}
