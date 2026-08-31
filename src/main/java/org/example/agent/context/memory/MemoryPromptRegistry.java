package org.example.agent.context.memory;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆系统提示词注册表（part4.md §7.6）。
 *
 * <p>启动时加载 {@code classpath:prompts/memory/*.md}，按文件名（即 profile id）索引。
 * 提示词文件 = frontmatter + 正文，正文分 {@code # system} 与 {@code # user} 两段，
 * user 段用 {@code {占位符}} 承接运行时变量。
 *
 * <p>缺失 profile 即 fail-fast —— 记忆是可选能力，但提示词缺失属于部署错误，应当暴露而非静默降级。
 */
@Component
public class MemoryPromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(MemoryPromptRegistry.class);

    public static final String PROMPTS_DIR = "prompts/memory";

    /**
     * profile 文件名清单。
     *
     * <p>mid-term patch / mid-term regen 已删除（mid-term 在 part4 §7.x 重设计后只服务
     * 当前 session 的窗口快照，不再由 LLM 摘要）。
     */
    public static final List<String> PROFILES = List.of(
            "short_term_compress",
            "long_term_extract",
            "long_term_topic_naming",
            "index_summary",
            "repair"
    );

    @Getter
    public static final class Profile {
        private final String name;
        private final String systemText;
        private final String userTemplate;
        private final Map<String, Object> frontmatter;

        public Profile(String name, String systemText, String userTemplate, Map<String, Object> frontmatter) {
            this.name = name;
            this.systemText = systemText == null ? "" : systemText;
            this.userTemplate = userTemplate == null ? "" : userTemplate;
            this.frontmatter = frontmatter == null ? new LinkedHashMap<>() : frontmatter;
        }

        public int maxOutputTokens() {
            Object v = frontmatter.get("maxOutputTokens");
            if (v instanceof Number n) return n.intValue();
            if (v != null) {
                try { return Integer.parseInt(v.toString()); } catch (NumberFormatException ignore) { }
            }
            return 1024;
        }

        public double temperature() {
            Object v = frontmatter.get("temperature");
            if (v instanceof Number n) return n.doubleValue();
            if (v != null) {
                try { return Double.parseDouble(v.toString()); } catch (NumberFormatException ignore) { }
            }
            return 0.2;
        }

        /** 把 {@code userTemplate} 中的 {@code {key}} 占位符替换为 {@code vars.get(key)}。缺失视为空串。 */
        public String renderUser(Map<String, ?> vars) {
            String t = userTemplate;
            if (vars == null || vars.isEmpty()) return t;
            for (Map.Entry<String, ?> e : vars.entrySet()) {
                Object v = e.getValue() == null ? "" : e.getValue();
                t = t.replace("{" + e.getKey() + "}", v.toString());
            }
            return t;
        }
    }

    private final Map<String, Profile> profiles = new LinkedHashMap<>();

    @PostConstruct
    public void load() {
        for (String name : PROFILES) {
            Profile p = loadOne(name);
            profiles.put(name, p);
        }
        log.info("MemoryPromptRegistry loaded {} profile(s): {}", profiles.size(), profiles.keySet());
    }

    private Profile loadOne(String name) {
        Resource res = new ClassPathResource(PROMPTS_DIR + "/" + name + ".md");
        if (!res.exists()) {
            throw new IllegalStateException(
                    "Missing memory prompt file: " + PROMPTS_DIR + "/" + name + ".md");
        }
        try {
            String raw = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return parse(name, raw);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read memory prompt " + name + ": " + ex.getMessage(), ex);
        }
    }

    /** 解析一个提示词文件 —— frontmatter + 正文 + # system / # user 切分。 */
    static Profile parse(String name, String raw) {
        if (raw == null) raw = "";
        Map<String, Object> fm = new LinkedHashMap<>();
        String body = raw;
        if (raw.startsWith("---")) {
            int second = raw.indexOf("\n---", 3);
            if (second > 0) {
                fm = Frontmatter.parse(raw);
                int bodyStart = raw.indexOf('\n', second + 4);
                if (bodyStart > 0) body = raw.substring(bodyStart + 1);
            }
        }
        // 切 # system 与 # user 两段
        int sysIdx = body.indexOf("\n# system");
        int usrIdx = body.indexOf("\n# user");
        String systemText;
        String userText;
        if (sysIdx >= 0 && usrIdx > sysIdx) {
            systemText = body.substring(sysIdx + "\n# system".length(), usrIdx).strip();
            userText = body.substring(usrIdx + "\n# user".length()).strip();
        } else {
            // 退路：整段做 system
            systemText = body.strip();
            userText = "";
        }
        return new Profile(name, systemText, userText, fm);
    }

    /** 取 profile；缺失时抛错（fail-fast）。 */
    public Profile require(String name) {
        Profile p = profiles.get(name);
        if (p == null) {
            throw new IllegalStateException("Memory prompt profile not loaded: " + name
                    + " (loaded=" + profiles.keySet() + ")");
        }
        return p;
    }

    public boolean has(String name) {
        return profiles.containsKey(name);
    }

    /** 调试 / 测试用 —— 列出已加载的 profile 名。 */
    public java.util.Set<String> names() {
        return java.util.Set.copyOf(profiles.keySet());
    }
}