package org.example.agent.tool.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolDescriptorRegistry 单元测试 —— 校验主 Agent 专属工具标记正确。
 *
 * <p>阶段 1 验证：
 * <ul>
 *   <li>阶段 1 新增的 5 个工具 + 旧的 TaskPlanTools 7 个工具都标 mainAgentOnly=true</li>
 *   <li>通用工具（read_file / write_file / grep / run_shell 等）保持 mainAgentOnly=false</li>
 *   <li>用 mainAgentOnly() 便捷方法覆盖默认值</li>
 * </ul>
 */
class ToolDescriptorRegistryTest {

    private final ToolDescriptorRegistry registry = new ToolDescriptorRegistry();

    @Test
    void mainOnlyToolsMarkedCorrectly() {
        // 阶段 1 新工具
        assertMainOnly("dispatch_subtask");
        assertMainOnly("create_plan");
        assertMainOnly("append_subtask");
        assertMainOnly("checkpoint_now");
        assertMainOnly("inspect_subagent");
        // TaskPlanTools 旧 7 个
        assertMainOnly("start_subtask");
        assertMainOnly("complete_subtask");
        assertMainOnly("fail_subtask");
        assertMainOnly("query_plan");
        assertMainOnly("skip_subtask");
        assertMainOnly("save_checkpoint");
    }

    @Test
    void generalToolsRemainAvailableToSubAgent() {
        // Q3 决定 run_shell 默认给 SubAgent → 不能是 mainAgentOnly
        assertNotMainOnly("run_shell");
        // 文件 / 搜索 / grep / git 一律给 SubAgent
        assertNotMainOnly("read_file");
        assertNotMainOnly("write_file");
        assertNotMainOnly("edit_file");
        assertNotMainOnly("list_dir");
        assertNotMainOnly("glob_files");
        assertNotMainOnly("grep");
        assertNotMainOnly("git_status");
        assertNotMainOnly("git_diff");
        assertNotMainOnly("git_log");
        assertNotMainOnly("git_show");
        assertNotMainOnly("check_command_exists");
    }

    @Test
    void mainAgentOnlyFactoryMarksCorrectly() {
        ToolDescriptor desc = ToolDescriptor.lowMainOnly("foo", "bar");
        assertTrue(desc.mainAgentOnly(), "lowMainOnly should set mainAgentOnly=true");
        assertTrue(desc.cacheable(), "lowMainOnly inherits LOW defaults");

        ToolDescriptor mid = ToolDescriptor.mediumMainOnly("foo2", true, "bar");
        assertTrue(mid.mainAgentOnly());
        assertFalse(mid.cacheable(), "mediumMainOnly inherits MEDIUM defaults");
    }

    @Test
    void mainAgentOnlyOverrideWorks() {
        // 一个原本 LOW（SubAgent 可见）的工具，用 markMainAgentOnly() 提升为主 Agent 专属
        ToolDescriptor desc = ToolDescriptor.low("foo", "bar").markMainAgentOnly();
        assertTrue(desc.mainAgentOnly());
    }

    private void assertMainOnly(String name) {
        ToolDescriptor d = registry.get(name).orElse(null);
        assertNotNull(d, "ToolDescriptor missing for: " + name);
        assertTrue(d.mainAgentOnly(), "Expected mainAgentOnly=true for: " + name);
    }

    private void assertNotMainOnly(String name) {
        ToolDescriptor d = registry.get(name).orElse(null);
        assertNotNull(d, "ToolDescriptor missing for: " + name);
        assertFalse(d.mainAgentOnly(), "Expected mainAgentOnly=false for: " + name);
    }
}