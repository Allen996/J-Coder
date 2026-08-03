package org.example.agent.tool.failure;

import org.example.agent.tool.ToolDeniedException;
import org.example.agent.tool.ToolExecutionException;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.TimeoutException;

/**
 * 工具失败分类器。
 *
 * <p>纯函数，无状态，可单例注入。判定规则只依赖异常类型 + 工具抛出的 kind，<b>不看错误消息字符串</b>。
 *
 * <p>匹配顺序：先具体后泛化（NoSuchFileException 优先于 IOException，因为前者是后者的子类）。
 * 详见 5.6.1 表格。
 */
@Component
public class FailureClassifier {

    public FailureKind classify(Throwable t) {
        return classify(t, null);
    }

    public FailureKind classify(Throwable t, ToolDescriptorHint hint) {
        if (t == null) {
            return FailureKind.LOGIC;
        }

        // 1. 沙箱拒绝：固定 PARAM
        if (t instanceof ToolDeniedException) {
            return FailureKind.PARAM;
        }

        // 2. 工具自抛的异常：使用它声明的 kind
        if (t instanceof ToolExecutionException tee) {
            return tee.failureKind();
        }

        // 3. 路径相关：确定性问题（路径错了不会因为重试变对）
        if (t instanceof NoSuchFileException
                || t instanceof FileNotFoundException) {
            return FailureKind.PARAM;
        }

        // 4. 真正的瞬时：网络 / 超时 / 中断
        if (t instanceof SocketTimeoutException
                || t instanceof ConnectException
                || t instanceof UnknownHostException
                || t instanceof InterruptedIOException
                || t instanceof TimeoutException
                || t instanceof InterruptedException) {
            return FailureKind.TRANSIENT;
        }

        // 5. 参数非法
        if (t instanceof IllegalArgumentException) {
            return FailureKind.PARAM;
        }

        // 6. 其他 IOException：兜底为瞬时（绝大多数 IO 错误重试有意义）
        if (t instanceof IOException) {
            return FailureKind.TRANSIENT;
        }

        // 7. 兜底：逻辑错误
        return FailureKind.LOGIC;
    }

    /**
     * 工具描述的轻量引用 —— classifier 实际只关心"是不是命令闸"这一项，
     * 但保留 hint 是为了未来扩展（不同 risk 等级的工具有不同分类规则）。
     */
    public record ToolDescriptorHint(boolean commandGate) {
    }
}
