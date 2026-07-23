package org.example.agent.context.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 极简 .gitignore 解析（part3.md §6.2 第一条规则）。
 *
 * <p>实现约束（v1）：
 * <ul>
 *   <li>只覆盖 .gitignore 实际项目里最常用的语法 —— 注释（#）、空行、根相对 vs 任意目录匹配（leading {@code /} vs 无前缀）、尾斜杠（目录专属）、{@code *} / {@code **} / {@code ?} 通配。</li>
 *   <li>不实现 {@code !}（否定 pattern），不实现 {@code [abc]} 字符组 —— 实际命中常见模式即可。</li>
 *   <li>大小写敏感（与 git 一致）。</li>
 * </ul>
 *
 * <p>与 git 实现的差异：git 在子目录下的 .gitignore 是叠加生效的；本类只解析根目录的 .gitignore。
 * 这与 part3.md "应用 .gitignore 规则" 的范围一致（粒度足够，避免引入 git CLI 依赖）。
 */
public final class GitignoreMatcher {

    private final List<Rule> rules;

    public GitignoreMatcher(List<String> lines) {
        List<Rule> rs = new ArrayList<>();
        if (lines != null) {
            for (String raw : lines) {
                Rule r = Rule.parse(raw);
                if (r != null) rs.add(r);
            }
        }
        this.rules = Collections.unmodifiableList(rs);
    }

    public static GitignoreMatcher empty() {
        return new GitignoreMatcher(Collections.emptyList());
    }

    /** 给定相对项目根的路径，判断是否被 .gitignore 忽略。 */
    public boolean isIgnored(Path relativePath) {
        if (rules.isEmpty()) return false;
        String p = normalize(relativePath);
        for (Rule r : rules) {
            if (r.matches(p)) return true;
        }
        return false;
    }

    static String normalize(Path relativePath) {
        String s = relativePath.toString().replace('\\', '/');
        if (s.startsWith("./")) s = s.substring(2);
        if (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    /** 单条规则。 */
    private static final class Rule {
        private final String pattern;
        private final boolean dirOnly;
        private final boolean anchored;
        private final java.util.regex.Pattern regex;

        private Rule(String pattern, boolean dirOnly, boolean anchored, java.util.regex.Pattern regex) {
            this.pattern = pattern;
            this.dirOnly = dirOnly;
            this.anchored = anchored;
            this.regex = regex;
        }

        static Rule parse(String raw) {
            if (raw == null) return null;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) return null;
            boolean dirOnly = line.endsWith("/");
            if (dirOnly) line = line.substring(0, line.length() - 1);
            boolean anchored = line.startsWith("/");
            if (anchored) line = line.substring(1);
            String regexStr = toRegex(line, anchored, dirOnly);
            try {
                return new Rule(line, dirOnly, anchored,
                        java.util.regex.Pattern.compile(regexStr));
            } catch (Exception ex) {
                return null;
            }
        }

        boolean matches(String relativePath) {
            java.util.regex.Matcher m = regex.matcher(relativePath);
            if (!m.find()) return false;
            // 目录专属规则：在任意位置匹配都行（即 "build" 既要匹配 build/，也要匹配 build/foo）。
            // 我们已经在 toRegex 里加了 (?:/.*)? 形式，这里再做语义校验：匹配串必须覆盖
            // pattern 的 basename（以保证像 "build" 这种不带 / 的模式仅匹配包含 build 段的情况）。
            if (dirOnly) {
                String base = pattern;
                int slash = base.lastIndexOf('/');
                if (slash >= 0) base = base.substring(slash + 1);
                // 必须有一个路径段等于 base（路径里包含 base 或以 base 开头）
                if (!relativePath.equals(base) && !relativePath.startsWith(base + "/")
                        && !relativePath.contains("/" + base + "/") && !relativePath.endsWith("/" + base)) {
                    return false;
                }
            }
            return true;
        }

        private static String toRegex(String pattern, boolean anchored, boolean dirOnly) {
            StringBuilder sb = new StringBuilder();
            if (anchored) sb.append('^');
            int i = 0;
            while (i < pattern.length()) {
                char c = pattern.charAt(i);
                if (c == '*') {
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                        // ** 匹配任意层级（含跨目录）
                        sb.append(".*");
                        i += 2;
                        if (i < pattern.length() && pattern.charAt(i) == '/') i++;
                    } else {
                        // * 匹配除 / 外的任意字符
                        sb.append("[^/]*");
                        i++;
                    }
                } else if (c == '?') {
                    sb.append("[^/]");
                    i++;
                } else if ("\\.[]()+|^$".indexOf(c) >= 0) {
                    sb.append('\\').append(c);
                    i++;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            // dirOnly 模式：允许匹配目录本身 ("build") 或目录下的内容 ("build/foo")
            if (dirOnly) {
                sb.append("(?:/.*)?");
            }
            if (!anchored) sb.append("$");
            return sb.toString();
        }
    }
}