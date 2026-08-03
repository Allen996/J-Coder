package org.example.cli.session;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单会话（单进程实例）的可变状态。Part 1 全部 in-memory，Part 4 会换成 SQLite 持久化。
 *
 * 字段语义：
 *  - sessionId  : 一次 CLI 启动的唯一 id；当前 CLI 永远只有一个 session（重启即新 id）
 *  - currentModel : 由 /model 命令更新；Part 1 仅做 UI 记录，Part 2 接入实际模型切换
 *  - planMode / verbose / autoApprove : 各类 slash 命令的开关
 *  - totalTokensIn / totalTokensOut : CliRenderer 在 ThoughtEvent 上累加（/cost 数据源）
 *  - historyForExport : CliRenderer 在每个 FinishEvent.finalAnswer 上 push（/export 数据源）
 */
@Component
@Getter
public class SessionState {

    private final String sessionId = UUID.randomUUID().toString();

    
    private volatile String currentModel = "qwen3.7-plus";

    private volatile boolean planMode = false;
    private volatile boolean verbose = false;
    private volatile boolean autoApprove = false;

    private final AtomicLong totalTokensIn = new AtomicLong(0L);
    private final AtomicLong totalTokensOut = new AtomicLong(0L);

    private final List<String> historyForExport = new CopyOnWriteArrayList<>();

    public void setCurrentModel(String model) {
        if (model != null && !model.isBlank()) {
            this.currentModel = model.trim();
        }
    }

    public void setPlanMode(boolean planMode) {
        this.planMode = planMode;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public void setAutoApprove(boolean autoApprove) {
        this.autoApprove = autoApprove;
    }

    public void togglePlanMode() {
        this.planMode = !this.planMode;
    }

    public void toggleVerbose() {
        this.verbose = !this.verbose;
    }

    public void toggleAutoApprove() {
        this.autoApprove = !this.autoApprove;
    }

    public void addTokens(long in, long out) {
        if (in > 0) totalTokensIn.addAndGet(in);
        if (out > 0) totalTokensOut.addAndGet(out);
    }

    public void appendHistory(String text) {
        if (text != null && !text.isBlank()) {
            historyForExport.add(text);
        }
    }
}