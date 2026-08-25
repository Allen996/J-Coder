package org.example.agent.intent.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 调试用:打印从 application.yml 里读出来的 API key(只打前 8 位 + 后 8 位 + 长度),
 * 用来确认解析逻辑是否正确。
 */
class ApiKeyDebugTest {

    @Test
    @DisplayName("打印 yml 里读出的 API key(脱敏)")
    void debug() throws Exception {
        String fromYml = readApiKeyFromAppYml();
        System.out.println("[DEBUG] from yml: length=" + (fromYml == null ? "null" : fromYml.length()));
        if (fromYml != null && !fromYml.isBlank()) {
            String head = fromYml.substring(0, Math.min(8, fromYml.length()));
            String tail = fromYml.substring(Math.max(0, fromYml.length() - 8));
            System.out.println("[DEBUG] from yml: head=" + head + " ... tail=" + tail);
            // 也打印完整字符串的 hash,方便对比
            System.out.println("[DEBUG] from yml: sha256-prefix=" + Integer.toHexString(fromYml.hashCode()));
        }

        String fromEnv = System.getenv("DASHSCOPE_API_KEY");
        System.out.println("[DEBUG] from env: " + (fromEnv == null ? "null" : ("length=" + fromEnv.length())));
        if (fromEnv != null && !fromEnv.isBlank()) {
            String head = fromEnv.substring(0, Math.min(8, fromEnv.length()));
            String tail = fromEnv.substring(Math.max(0, fromEnv.length() - 8));
            System.out.println("[DEBUG] from env: head=" + head + " ... tail=" + tail);
        }
    }

    private static String readApiKeyFromAppYml() throws Exception {
        Path yml = Path.of("src/main/resources/application.yml");
        if (!Files.exists(yml)) return null;
        for (String line : Files.readAllLines(yml)) {
            if (line.trim().startsWith("api-key:")) {
                String value = line.substring(line.indexOf(':') + 1).trim();
                if (value.startsWith("${")) {
                    int colon = value.indexOf(":", 2);
                    int close = value.indexOf("}", colon);
                    if (colon > 0 && close > colon) return value.substring(colon + 1, close).trim();
                } else {
                    return value;
                }
            }
        }
        return null;
    }
}