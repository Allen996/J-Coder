package org.example.agent.intent;

import org.example.agent.tool.spi.ToolDescriptorRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把意图标签映射到"该意图推荐使用的工具集"。
 *
 * <p>用于:
 * <ol>
 *   <li>初始化 AgentTask 的 toolAllowList(收紧白名单);</li>
 *   <li>L2 门控时判断"模型挑的工具是否属于本意图推荐集合",否则升级到 code 档复核。</li>
 * </ol>
 */
@Component
public class IntentAwareToolSet {

    private final ToolDescriptorRegistry registry;

    public IntentAwareToolSet(ToolDescriptorRegistry registry) {
        this.registry = registry;
    }

    private static final Map<IntentLabel, List<String>> RECOMMENDED = Map.of(
            IntentLabel.READ_CODE, List.of("read_file", "list_dir", "glob_files", "grep", "git_show", "recall_tool_result"),
            IntentLabel.WRITE_PROJECT, List.of("read_file", "list_dir", "grep", "write_file", "edit_file", "glob_files"),
            IntentLabel.RUN_COMMAND, List.of("run_shell", "check_command_exists", "git_status", "git_diff", "git_commit", "git_log"),
            IntentLabel.CHAT_QA, List.of(),
            IntentLabel.PLANNING, List.of("read_file", "list_dir", "glob_files", "grep", "recall_tool_result")
    );

    public List<String> recommendedFor(IntentLabel label) {
        if (label == null) return List.of();
        return RECOMMENDED.getOrDefault(label, List.of());
    }

    public boolean isRecommendedFor(IntentLabel label, String toolName) {
        if (toolName == null) return false;
        return recommendedFor(label).contains(toolName);
    }

    public Set<String> allKnownTools() {
        return registry.names();
    }
}