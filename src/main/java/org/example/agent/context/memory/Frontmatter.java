package org.example.agent.context.memory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 YAML frontmatter 解析与生成（part3.md §6.8 / part4.md §7.4）。
 *
 * <p>支持：键值对（字符串 / 数字 / 布尔）、对象（嵌套 key）、数组（每行一个 {@code - value}）。
 * 不实现完整 YAML —— 仅覆盖本项目记忆文件实际写入的字段。
 *
 * <p>格式示例：
 * <pre>
 * ---
 * schema: 1
 * sessionId: 2026-07-23-001
 * createdAt: 2026-07-23T15:30:00
 * ---
 * </pre>
 */
public final class Frontmatter {

    private Frontmatter() { }

    public static String render(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            sb.append(renderValue(e.getKey(), e.getValue(), 0));
        }
        sb.append("---\n");
        return sb.toString();
    }

    private static String renderValue(String key, Object value, int indent) {
        String prefix = " ".repeat(indent);
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append(prefix).append(key).append(":\n");
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sb.append(renderValue(String.valueOf(e.getKey()), e.getValue(), indent + 2));
            }
            return sb.toString();
        }
        if (value instanceof Iterable<?> iter) {
            StringBuilder sb = new StringBuilder();
            sb.append(prefix).append(key).append(":\n");
            for (Object item : iter) {
                if (item instanceof Map<?, ?> itemMap) {
                    // 嵌套对象写法：每行 '- key: value'，需要把首行的 '-' 拍平
                    boolean first = true;
                    for (Map.Entry<?, ?> ie : itemMap.entrySet()) {
                        sb.append(prefix);
                        if (first) {
                            sb.append("  - ");
                            first = false;
                        } else {
                            sb.append("    ");
                        }
                        sb.append(ie.getKey()).append(": ").append(scalar(ie.getValue())).append("\n");
                    }
                } else {
                    sb.append(prefix).append("  - ").append(scalar(item)).append("\n");
                }
            }
            return sb.toString();
        }
        return prefix + key + ": " + scalar(value) + "\n";
    }

    private static String scalar(Object value) {
        if (value == null) return "";
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String s = value.toString();
        if (s.contains(":") || s.contains("#") || s.startsWith("-") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\\\"") + "\"";
        }
        return s;
    }

    /** 解析文件头部的 frontmatter；空或不存在时返回空 map。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String content) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (content == null) return result;
        String[] lines = content.split("\n");
        if (lines.length < 2 || !"---".equals(lines[0].strip())) {
            return result;
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].strip())) {
                end = i;
                break;
            }
        }
        if (end < 0) return result;
        for (int i = 1; i < end; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).replace("\\\"", "\"");
            }
            result.put(key, value);
        }
        return result;
    }

    /** 计算下一次写入需要的字段集合（带默认 schema=1）。 */
    public static Map<String, Object> defaults() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "1");
        return m;
    }
}
