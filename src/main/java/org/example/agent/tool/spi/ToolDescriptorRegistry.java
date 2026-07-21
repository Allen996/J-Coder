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
 * 在每次 invoke 前先 {@code registry.get(name)} 拿到风险/可逆性等，再决定走哪条路径。
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
                "读取文件指定行范围。startLine/endLine 都是 1-based，包含两端。"));
        m.put("write_file", ToolDescriptor.medium("write_file", true,
                "写入或覆盖文件内容。"));
        m.put("edit_file", ToolDescriptor.medium("edit_file", true,
                "按 oldText 匹配替换文件内容。"));
        m.put("list_dir", ToolDescriptor.low("list_dir",
                "列出目录内容（可指定递归深度）。"));
        m.put("glob_files", ToolDescriptor.low("glob_files",
                "按 glob 模式匹配文件。"));

        // ===== Shell =====
        m.put("run_shell", ToolDescriptor.high("run_shell",
                "在项目根目录执行 shell 命令，受命令闸与执行闸约束。"));
        m.put("check_command_exists", ToolDescriptor.low("check_command_exists",
                "检查命令是否在 PATH 中可用。"));

        // ===== Grep =====
        m.put("grep", ToolDescriptor.low("grep",
                "在指定路径下按正则搜索文件内容，返回命中行。"));

        // ===== Git =====
        m.put("git_status", ToolDescriptor.low("git_status",
                "查看工作区与暂存区状态。"));
        m.put("git_diff", ToolDescriptor.low("git_diff",
                "查看 diff（默认工作区 vs 暂存区）。"));
        m.put("git_log", ToolDescriptor.low("git_log",
                "查看最近 n 条提交（默认 10）。"));
        m.put("git_commit", ToolDescriptor.medium("git_commit", true,
                "提交暂存区的变更。"));
        m.put("git_show", ToolDescriptor.low("git_show",
                "查看某次提交 / 引用对应的内容。"));

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
