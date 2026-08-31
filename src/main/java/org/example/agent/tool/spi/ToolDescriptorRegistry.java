package org.example.agent.tool.spi;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具描述的中央注册表。
 *
 * <p>把"工具名 -> 元数据"集中在一处管理，避免散落在各 {@code @Tool} 类的注释里。
 * 增删工具时只需要改本类 + 改 {@code @Tool} 方法实现两处，元数据不会被遗漏更新。
 *
 * <p>查找是 O(1)，不依赖 Spring 容器的 bean 扫描。{@link org.example.agent.tool.gateway.ToolGateway}
 * 在每次 invoke 前先 {@code registry.get(name)} 拿到风险/可逆性/可缓存/可并发，再决定走哪条路径。
 *
 * <p>包级可见性：{@code description} 字段同时承担 prompt 注入来源（v2），所以本类不内嵌到 gateway。
 */
@Component
public class ToolDescriptorRegistry {

    private final Map<String, ToolDescriptor> byName;

    public ToolDescriptorRegistry() {
        Map<String, ToolDescriptor> m = new LinkedHashMap<>();

        // ===== File =====
        m.put("read_file", ToolDescriptor.low("read_file",
                "读取文件指定行范围。startLine/endLine 都是 1-based，包含两端。")
                .withTimeout(10_000L));
        m.put("write_file", ToolDescriptor.medium("write_file", true,
                "写入或覆盖文件内容。")
                .withTimeout(10_000L));
        m.put("edit_file", ToolDescriptor.medium("edit_file", true,
                "按 oldText 匹配替换文件内容。")
                .withTimeout(10_000L));
        m.put("list_dir", ToolDescriptor.low("list_dir",
                "列出目录内容（可指定递归深度）。")
                .withTimeout(10_000L));
        m.put("glob_files", ToolDescriptor.low("glob_files",
                "按 glob 模式匹配文件。")
                .withTimeout(30_000L));

        // ===== Shell =====
        m.put("run_shell", ToolDescriptor.high("run_shell",
                "在项目根目录执行 shell 命令，受命令闸与执行闸约束。")
                .withTimeout(60_000L));
        m.put("check_command_exists", ToolDescriptor.lowNonCacheable("check_command_exists",
                "检查命令是否在 PATH 中可用。")
                .withTimeout(5_000L));

        // ===== Grep =====
        m.put("grep", ToolDescriptor.low("grep",
                "在指定路径下按正则搜索文件内容，返回命中行。")
                .withTimeout(30_000L));

        // ===== Git =====
        m.put("git_status", ToolDescriptor.low("git_status",
                "查看工作区与暂存区状态。")
                .withTimeout(10_000L));
        m.put("git_diff", ToolDescriptor.low("git_diff",
                "查看 diff（默认工作区 vs 暂存区）。")
                .withTimeout(30_000L));
        m.put("git_log", ToolDescriptor.low("git_log",
                "查看最近 n 条提交（默认 10）。")
                .withTimeout(15_000L));
        m.put("git_commit", ToolDescriptor.medium("git_commit", true,
                "提交暂存区的变更。")
                .withTimeout(15_000L));
        m.put("git_show", ToolDescriptor.low("git_show",
                "查看某次提交 / 引用对应的内容。")
                .withTimeout(15_000L));

        // ===== Task Plan (主 Agent 专属,SubAgent 拿不到) =====
        // TaskPlanTools 现有 7 个工具 + 阶段 1 新增 5 个工具
        // 注意:这里登记只是为了让 SpringAiReactAgentProvider 能识别 mainAgentOnly 标记,
        // 并非给 ToolGateway 用（TaskGateway 不调这些主 Agent 工具）。
        m.put("create_plan", ToolDescriptor.lowMainOnly("create_plan",
                "创建一个 TaskPlan 来拆解复杂任务。"));
        m.put("start_subtask", ToolDescriptor.lowMainOnly("start_subtask",
                "把指定 SubTask 标记为 IN_PROGRESS。"));
        m.put("complete_subtask", ToolDescriptor.lowMainOnly("complete_subtask",
                "标记当前 SubTask 完成（IN_PROGRESS → COMPLETED）。"));
        m.put("fail_subtask", ToolDescriptor.lowMainOnly("fail_subtask",
                "显式放弃当前 SubTask（IN_PROGRESS → FAILED）。"));
        m.put("query_plan", ToolDescriptor.lowMainOnly("query_plan",
                "查询当前 plan 的完整状态。"));
        m.put("skip_subtask", ToolDescriptor.lowMainOnly("skip_subtask",
                "跳过指定 SubTask 并链式 SKIP 其下游。"));
        m.put("save_checkpoint", ToolDescriptor.lowMainOnly("save_checkpoint",
                "在当前 SubTask 上打一个 checkpoint。"));
        // 阶段 1 新增 5 个工具
        m.put("dispatch_subtask", ToolDescriptor.lowMainOnly("dispatch_subtask",
                "派发一个子 Agent 跑指定任务,主 loop 将在下一轮推理前 await。"));
        m.put("append_subtask", ToolDescriptor.lowMainOnly("append_subtask",
                "向 DAG 追加一个新节点（不立即执行）。"));
        m.put("create_plan_v2", ToolDescriptor.lowMainOnly("create_plan_v2",
                "（阶段 2 启用）创建初始 DAG。"));
        m.put("checkpoint_now", ToolDescriptor.lowMainOnly("checkpoint_now",
                "（阶段 4 启用）触发完整状态快照,等待用户决策。"));
        m.put("inspect_subagent", ToolDescriptor.lowMainOnly("inspect_subagent",
                "（阶段 3 启用）读取 SubAgent session 的 mid-term 或工具调用列表。"));

        this.byName = Collections.unmodifiableMap(m);
    }

    public Optional<ToolDescriptor> get(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(byName.get(name));
    }

    public boolean contains(String name) {
        return name != null && byName.containsKey(name);
    }

    public Set<String> names() {
        return byName.keySet();
    }

    public Collection<ToolDescriptor> all() {
        return byName.values();
    }
}
