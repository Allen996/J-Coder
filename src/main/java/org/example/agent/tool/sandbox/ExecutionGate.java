package org.example.agent.tool.sandbox;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * 沙箱闸 3：执行闸。仅对 shell 类工具生效。
 *
 * <p>职责：
 * <ul>
 *   <li>并发限制：最多 4 个并行 shell（防 fork bomb）</li>
 *   <li>工作目录强制：项目根</li>
 *   <li>环境变量白名单：仅透传安全子集</li>
 * </ul>
 *
 * <p>不做：
 * <ul>
 *   <li>超时控制 —— 由 Process.waitFor(timeout) 在调用方控制，更精确</li>
 *   <li>输出截断 —— 由调用方读 stdout/stderr 时限制 100KB</li>
 * </ul>
 *
 * <p>理由：超时/截断是"执行动作"的属性，跟具体调用绑；
 * 并发/工作目录/环境变量是"系统状态"的属性，可以预绑定。
 */
@Component
public class ExecutionGate {

    private static final int MAX_CONCURRENT_SHELLS = 4;

    private static final Set<String> ENV_WHITELIST = Set.of(
            "PATH", "HOME", "USER", "LANG", "LANGUAGE", "LC_ALL", "TZ",
            "JAVA_HOME", "MAVEN_HOME", "NODE_ENV", "VIRTUAL_ENV",
            "TMP", "TEMP", "TMPDIR"
    );

    private final Semaphore shellPermits;
    private final PathGate pathGate;

    public ExecutionGate(PathGate pathGate) {
        this.shellPermits = new Semaphore(MAX_CONCURRENT_SHELLS);
        this.pathGate = pathGate;
    }

    @PostConstruct
    void init() {
        // 占位：未来可在此加载用户自定义的环境变量白名单覆盖
    }

    @PreDestroy
    void shutdown() {
        // Semaphore 无显式资源，无需 close
    }

    /**
     * 申请一个 shell 执行槽位。阻塞直到可用或线程被中断。
     */
    public ShellPermit acquireShellPermit() throws InterruptedException {
        shellPermits.acquire();
        return new ShellPermit(shellPermits);
    }

    /**
     * 构造 ProcessBuilder 时的"安全环境变量"子集。
     */
    public Map<String, String> safeEnv() {
        Map<String, String> env = System.getenv();
        Map<String, String> filtered = new java.util.HashMap<>();
        for (String key : ENV_WHITELIST) {
            String value = env.get(key);
            if (value != null) {
                filtered.put(key, value);
            }
        }
        return filtered;
    }

    /**
     * 进程的工作目录 = 项目根。
     */
    public java.nio.file.Path workingDir() {
        return pathGate.getProjectRoot();
    }

    public int maxConcurrentShells() {
        return MAX_CONCURRENT_SHELLS;
    }

    /**
     * AutoCloseable 包装，确保 shell 退出时释放槽位（try-with-resources 使用）。
     */
    public static final class ShellPermit implements AutoCloseable {
        private final Semaphore parent;
        private boolean released = false;

        ShellPermit(Semaphore parent) {
            this.parent = parent;
        }

        @Override
        public void close() {
            if (!released) {
                parent.release();
                released = true;
            }
        }
    }
}
