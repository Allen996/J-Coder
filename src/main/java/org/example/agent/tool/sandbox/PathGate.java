package org.example.agent.tool.sandbox;

import org.example.agent.tool.ToolDeniedException;
import org.example.agent.tool.failure.ToolErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 沙箱闸 1：路径闸。
 *
 * <p>规则见 5.3：
 * <ol>
 *   <li>软链解析 → realpath</li>
 *   <li>realpath 必须在白名单内（项目根 + trusted-paths.json 配置）</li>
 *   <li>realpath 不能在黑名单内（精确或前缀匹配）</li>
 *   <li>路径字符串本身不能含 shell 特殊字符（防注入）</li>
 * </ol>
 *
 * <p>任一项不通过抛 {@link ToolDeniedException}，被 {@code ToolGateway} 翻译成 DENIED 状态。
 *
 * <p>Windows 注意：{@code user.home} 在 Windows 上是 {@code C:\Users\xxx}，黑名单前缀
 * （{@code ~/.ssh} 等）会用 {@code user.home} 展开为绝对路径再做匹配，跨平台一致。
 */
@Component
public class PathGate {

    /**
     * shell 特殊字符 —— 出现在 path 字符串里几乎一定是注入尝试。
     * 注意不含 {@code /} 和 {@code \}，否则合法路径都会被拒。
     */
    private static final Pattern SHELL_SPECIAL = Pattern.compile("[`$;|&<>(){}\\[\\]'\"]");

    private static final Set<String> HARDCODED_BLACKLIST = Set.of(
            "/etc", "/proc", "/sys", "/dev"
    );

    private final Path projectRoot;
    private final TrustedPaths trustedPaths;

    public PathGate(@Value("${cli.project-root:}") String projectRootConfig,
                    TrustedPaths trustedPaths) {
        this.projectRoot = resolveProjectRoot(projectRootConfig);
        this.trustedPaths = trustedPaths;
    }

    /**
     * 校验路径。返回解析后的 realpath（调用方直接用，不需要再 resolve）。
     *
     * @param input 用户传入的路径（绝对或相对于 projectRoot）
     * @return 通过校验的 realpath
     * @throws ToolDeniedException 任意一项不通过
     */
    public Path validate(String input) {
        if (input == null || input.isBlank()) {
            throw new ToolDeniedException(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "路径为空",
                    "提供非空路径");
        }
        if (SHELL_SPECIAL.matcher(input).find()) {
            throw new ToolDeniedException(
                    ToolErrorCode.PATH_BLACKLISTED,
                    "路径包含 shell 特殊字符",
                    "路径中不要包含 ` $ ; & | < > ( ) { } [ ] ' \"");
        }

        Path raw = resolveAgainstRoot(input);

        // 不存在的路径也允许 read_file 之类工具来探查 —— realpath 会抛 NoSuchFileException
        // 这种情况由工具层处理（变成 ToolErrorCode.PATH_NOT_FOUND，PARAM 类）
        Path real;
        try {
            real = raw.toRealPath();
        } catch (IOException ioe) {
            // 路径不存在 / 权限不够 —— 透传，让工具层产生 PATH_NOT_FOUND 错误
            return raw.toAbsolutePath().normalize();
        }

        if (isInBlacklist(real)) {
            throw new ToolDeniedException(
                    ToolErrorCode.PATH_BLACKLISTED,
                    "路径在沙箱黑名单内",
                    "选择项目内或 trusted-paths.json 列出的目录");
        }
        if (!isInWhitelist(real)) {
            throw new ToolDeniedException(
                    ToolErrorCode.PATH_OUTSIDE_SANDBOX,
                    "路径不在沙箱白名单内",
                    "沙箱只允许访问项目根目录与 ~/.local-cli-copilot/trusted-paths.json 中配置的目录");
        }
        return real;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    // ============== 内部 ==============

    private Path resolveProjectRoot(String config) {
        if (config != null && !config.isBlank()) {
            return Paths.get(config).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private Path resolveAgainstRoot(String input) {
        Path p = Paths.get(input);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        return projectRoot.resolve(input).normalize();
    }

    private boolean isInWhitelist(Path real) {
        if (isAncestor(projectRoot, real) || real.equals(projectRoot)) {
            return true;
        }
        for (Path trusted : trustedPaths.load()) {
            if (isAncestor(trusted, real) || real.equals(trusted)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInBlacklist(Path real) {
        // 硬编码黑名单（Unix 路径）
        for (String prefix : HARDCODED_BLACKLIST) {
            Path p = Paths.get(prefix);
            if (real.startsWith(p)) {
                return true;
            }
        }
        // 用户家目录下的敏感子目录（跨平台：Windows 上 ~/.ssh 展开为 C:\Users\xxx\.ssh）
        String home = System.getProperty("user.home");
        if (home != null) {
            Path homePath = Paths.get(home);
            String[] sensitive = {".ssh", ".aws", ".kube", ".docker"};
            for (String s : sensitive) {
                Path sensitivePath = homePath.resolve(s);
                if (real.startsWith(sensitivePath)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAncestor(Path ancestor, Path child) {
        return child.toAbsolutePath().normalize().startsWith(
                ancestor.toAbsolutePath().normalize());
    }
}
