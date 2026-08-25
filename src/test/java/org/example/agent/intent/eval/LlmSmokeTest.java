package org.example.agent.intent.eval;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 一发即弃的 LLM 烟雾测试,用来诊断 "Error while extracting response" 的根因
 * (key 过期 / model 名错误 / 账号无权限 等)。
 *
 * <p>运行: {@code mvn test "-Dtest=LlmSmokeTest" "-Dintent.eval.smoke-model=qwen3.7-flash"}
 * 默认 model 是 {@code qwen3.7-flash};可通过 {@code -Dintent.eval.smoke-model} 覆盖。
 *
 * <p>从环境变量 DASHSCOPE_API_KEY 或 application.yml 里读 key;
 * 调用一次简单的 chat,打印原始响应或完整异常链。
 */
class LlmSmokeTest {

    @Test
    @DisplayName("LLM 烟雾测试:打一发,看 key/model/响应是否正常")
    void smoke() throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = readApiKeyFromAppYml();
        }
        String modelName = System.getProperty("intent.eval.smoke-model", "qwen3.7-flash");

        System.out.println("[SMOKE] apiKey loaded: " + (apiKey != null && !apiKey.isBlank()));
        System.out.println("[SMOKE] using model: " + modelName);

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[SMOKE] no api key — skipping");
            return;
        }

        DashScopeApi api = DashScopeApi.builder()
                .apiKey(apiKey)
                .baseUrl(readBaseUrlFromEnv())
                .build();
        DashScopeChatOptions opts = DashScopeChatOptions.builder()
                .model(modelName)
                .withTemperature(0.0)
                .withMultiModel(true)
                .build();
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(opts)
                .build();

        try {
            var resp = chatModel.call(new Prompt(
                    List.of(
                            new SystemMessage("你是一个测试 agent。"),
                            new UserMessage("回复 'ok' 两个字符")),
                    opts));
            System.out.println("[SMOKE] call OK: " + resp);
            if (resp != null && resp.getResult() != null && resp.getResult().getOutput() != null) {
                System.out.println("[SMOKE] text: " + resp.getResult().getOutput().getText());
            }
        } catch (Throwable t) {
            System.err.println("[SMOKE] call FAILED: " + t.getClass().getName() + ": " + t.getMessage());
            Throwable cause = t.getCause();
            int depth = 0;
            while (cause != null && depth < 8) {
                System.err.println("[SMOKE] caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
                depth++;
            }
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

    /**
     * 读取 base-url,优先级:DASHSCOPE_BASE_URL 环境变量 > yml 里的 spring.ai.dashscope.base-url 占位符 > SDK 默认。
     * 不显式传 base-url 会落到 dashscope.aliyuncs.com 官方端点,导致私人网关 / 兼容层完全失效。
     */
    private static String readBaseUrlFromEnv() {
        String fromEnv = System.getenv("DASHSCOPE_BASE_URL");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        try {
            Path yml = Path.of("src/main/resources/application.yml");
            if (!Files.exists(yml)) return null;
            for (String line : Files.readAllLines(yml)) {
                String t = line.trim();
                if (t.startsWith("base-url:")) {
                    String value = t.substring(t.indexOf(':') + 1).trim();
                    if (value.startsWith("${")) {
                        int colon = value.indexOf(":", 2);
                        int close = value.indexOf("}", colon);
                        if (colon > 0 && close > colon) return value.substring(colon + 1, close).trim();
                    } else {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}