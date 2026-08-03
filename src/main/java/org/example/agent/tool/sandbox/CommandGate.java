package org.example.agent.tool.sandbox;

import org.example.agent.tool.ToolDeniedException;
import org.example.agent.tool.failure.ToolErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 沙箱闸 2：命令闸。仅对 shell 类工具生效。
 *
 * <p>规则见 5.3：
 * <ul>
 *   <li>黑名单正则：rm -rf /、mkfs、dd if=、fork bomb、{@code >/dev/sd*}、chmod 777 /、{@code curl|sh}</li>
 *   <li>白名单：命令首词命中时跳过授权 UI（v1 暂未接 UI，无实际效果）</li>
 * </ul>
 *
 * <p>白名单意图：常见开发命令（git、mvn、npm、ls、cat 等）不打扰用户。
 * 黑名单意图：即便用户授权了 run_shell，也拦下"明显有害"模式。
 *
 * <p>白名单/黑名单是双层防御：白名单决定"是否要用户确认"，黑名单决定"是否要拒绝执行"。
 * 即使在白名单内，命中黑名单仍然拒绝。
 */
@Component
public class CommandGate {

    private static final List<Pattern> BLACKLIST = List.of(
            Pattern.compile("rm\\s+(-[a-zA-Z]*[rfRF][a-zA-Z]*\\s+)*[/~]"),
            Pattern.compile("\\bmkfs\\b"),
            Pattern.compile("\\bdd\\s+if="),
            Pattern.compile(":\\(\\)\\s*\\{.*\\};\\s*:"),
            Pattern.compile(">\\s*/dev/sd[a-z]"),
            Pattern.compile("\\bchmod\\s+(-R\\s+)?777\\s+/"),
            Pattern.compile("\\bcurl\\s+.*\\|\\s*(bash|sh)\\s*")
    );

    private static final Set<String> FIRST_WORD_WHITELIST = Set.of(
            "ls", "cat", "head", "tail", "wc", "file", "stat", "find",
            "grep", "rg", "tree", "du", "df",
            "git", "gh", "mvn", "gradle", "npm", "pnpm", "yarn",
            "cargo", "go", "python", "pip", "pytest",
            "docker", "docker-compose", "kubectl"
    );

    /**
     * 校验 shell 命令。
     *
     * @param command 完整命令字符串
     * @return 解析出的首词（用于审计 / 日志）
     * @throws ToolDeniedException 命中黑名单时
     */
    public String validate(String command) {
        if (command == null || command.isBlank()) {
            throw new ToolDeniedException(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "命令为空",
                    "提供非空命令");
        }
        for (Pattern p : BLACKLIST) {
            if (p.matcher(command).find()) {
                throw new ToolDeniedException(
                        ToolErrorCode.SHELL_DENIED,
                        "命令命中黑名单模式",
                        "此命令模式被禁用，与安全策略冲突");
            }
        }
        return firstWord(command);
    }

    /**
     * 命令首词是否在白名单内。
     */
    public boolean isWhitelisted(String command) {
        return FIRST_WORD_WHITELIST.contains(firstWord(command));
    }

    public Set<String> whitelist() {
        return FIRST_WORD_WHITELIST;
    }

    private String firstWord(String command) {
        String trimmed = command.stripLeading();
        int idx = indexOfWhitespace(trimmed);
        return idx < 0 ? trimmed : trimmed.substring(0, idx);
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }
}
