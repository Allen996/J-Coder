package org.example.cli.input;

import org.springframework.stereotype.Component;

/**
 * 把 REPL 读到的一行原始输入路由到四种处理：
 *  - SLASH : 以 "/" 开头，分发给 SlashCommandRegistry
 *  - SHELL : 以 "!" 开头，交给 ShellPassthrough 透传
 *  - AGENT : 默认，构造 AgentTask 走 AgentRuntime.stream()
 *
 * 规则：
 *  - 空行 / 纯空白 → AGENT（实际上 ReplLoop 会先过滤空行）
 *  - "//" 开头 → SLASH（被 slash 解析为 unknown command，正常返回错误）
 */
@Component
public class InputRouter {

    public enum Route { SLASH, SHELL, AGENT }

    public Route route(String raw) {
        if (raw == null) {
            return Route.AGENT;
        }
        String trimmed = raw.stripLeading();
        if (trimmed.startsWith("/")) {
            return Route.SLASH;
        }
        if (trimmed.startsWith("!")) {
            return Route.SHELL;
        }
        return Route.AGENT;
    }

    /**
     * 提取 shell 命令（去掉前导 "!"）。
     */
    public String stripShellPrefix(String raw) {
        if (raw == null) return "";
        String trimmed = raw.stripLeading();
        if (trimmed.startsWith("!")) {
            return trimmed.substring(1).stripLeading();
        }
        return trimmed;
    }

    /**
     * 提取 slash 命令名 + 参数。
     */
    public SlashLine splitSlash(String raw) {
        String trimmed = raw == null ? "" : raw.stripLeading();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int sp = indexOfTopLevelSpace(trimmed);
        if (sp < 0) {
            return new SlashLine(trimmed, "");
        }
        return new SlashLine(trimmed.substring(0, sp), trimmed.substring(sp + 1).stripLeading());
    }

    private static int indexOfTopLevelSpace(String s) {
        boolean inQuote = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quote) inQuote = false;
            } else {
                if (c == '"' || c == '\'') {
                    inQuote = true;
                    quote = c;
                } else if (Character.isWhitespace(c)) {
                    return i;
                }
            }
        }
        return -1;
    }

    public record SlashLine(String command, String args) {
    }
}