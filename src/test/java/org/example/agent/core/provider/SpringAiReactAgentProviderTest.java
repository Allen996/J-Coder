package org.example.agent.core.provider;

import org.example.agent.tool.spi.ToolDescriptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SpringAiReactAgentProvider 工具物理隔离测试（阶段 1）。
 *
 * <p>验证 collectToolObjects(String agentRole)：
 * <ul>
 *   <li>主 Agent（null / "main"）拿到所有 @Tool bean</li>
 *   <li>SubAgent（"subagent"）拿不到 mainAgentOnly=true 的 bean（即使 bean 里有别的非 mainAgentOnly 工具）</li>
 * </ul>
 *
 * <p>通过包级调用 + 真实 stub bean 实现（不需要 Mockito）。
 */
class SpringAiReactAgentProviderTest {

    private SpringAiReactAgentProvider provider;
    private ToolDescriptorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolDescriptorRegistry();
        provider = new SpringAiReactAgentProvider(
                stubBeanProvider(
                        new ReadFileTool(),         // 通用
                        new RunShellTool(),         // 通用（Q3 决定 run_shell 给 SubAgent）
                        new TaskPlanToolsStub()     // 包含 mainAgentOnly 工具 + 1 个普通工具（混合场景）
                ),
                null, null, null, registry);
    }

    @Test
    void mainAgentSeesAllToolBeans() {
        Object[] tools = provider.collectToolObjects("main");
        Set<Object> set = new HashSet<>();
        for (Object o : tools) set.add(o.getClass());
        assertTrue(set.contains(ReadFileTool.class));
        assertTrue(set.contains(RunShellTool.class));
        assertTrue(set.contains(TaskPlanToolsStub.class),
                "main Agent should see TaskPlanToolsStub bean");
    }

    @Test
    void mainAgentDefaultRoleSeesAllToolBeans() {
        Object[] tools = provider.collectToolObjects(null);
        Set<Object> set = new HashSet<>();
        for (Object o : tools) set.add(o.getClass());
        assertTrue(set.contains(ReadFileTool.class));
        assertTrue(set.contains(RunShellTool.class));
        assertTrue(set.contains(TaskPlanToolsStub.class));
    }

    @Test
    void subAgentSkipsMainOnlyBean() {
        Object[] tools = provider.collectToolObjects("subagent");
        Set<Object> set = new HashSet<>();
        for (Object o : tools) set.add(o.getClass());
        assertTrue(set.contains(ReadFileTool.class));
        assertTrue(set.contains(RunShellTool.class));
        assertFalse(set.contains(TaskPlanToolsStub.class),
                "SubAgent should NOT see TaskPlanToolsStub (it has create_plan / dispatch_subtask main-only tools)");
    }

    @Test
    void subAgentCaseInsensitive() {
        Object[] tools = provider.collectToolObjects("SubAgent");
        Set<Object> set = new HashSet<>();
        for (Object o : tools) set.add(o.getClass());
        assertFalse(set.contains(TaskPlanToolsStub.class),
                "agentRole matching is case-insensitive");
    }

    @Test
    void emptyBeanProviderYieldsEmpty() {
        SpringAiReactAgentProvider emptyProvider = new SpringAiReactAgentProvider(
                stubBeanProvider(), null, null, null, registry);
        assertEquals(0, emptyProvider.collectToolObjects("subagent").length);
        assertEquals(0, emptyProvider.collectToolObjects("main").length);
        assertEquals(0, emptyProvider.collectToolObjects(null).length);
    }

    /**
     * 把若干 bean 塞进一个 StaticListableBeanFactory，
     * 然后用 getBeanProvider(Object.class) 拿到 ObjectProvider<Object>。
     */
    private static ObjectProvider<Object> stubBeanProvider(Object... beans) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        for (Object b : beans) {
            bf.addBean(b.getClass().getName() + "@" + System.identityHashCode(b), b);
        }
        return bf.getBeanProvider(Object.class);
    }

    // ====================== Stub tool beans ======================

    /** 模拟 read_file / list_dir 等"通用"工具。 */
    static class ReadFileTool {
        @Tool(description = "读取文件")
        public String readFile(String path) { return ""; }

        @Tool(description = "列出目录")
        public String listDir(String path) { return ""; }
    }

    /** 模拟 run_shell —— Q3 决定给 SubAgent。 */
    static class RunShellTool {
        @Tool(description = "执行 shell 命令")
        public String runShell(String command) { return ""; }
    }

    /**
     * 模拟 TaskPlanTools（混合 mainAgentOnly + 普通工具）。
     * 当前任务系统下 TaskPlanTools 有 7 个 mainAgentOnly 工具。
     * 本 stub 加 1 个普通方法以测试"包含 mainAgentOnly 工具的 bean 整体被排除"。
     */
    static class TaskPlanToolsStub {
        @Tool(name = "create_plan", description = "stub main-only tool")
        public String createPlan(String goal) { return ""; }

        @Tool(name = "dispatch_subtask", description = "stub main-only tool")
        public String dispatchSubtask(String taskId) { return ""; }

        // 普通工具（即使存在，因为同 bean 已有 mainAgentOnly，整体被排除）
        @Tool(name = "aux_tool", description = "auxiliary tool")
        public String auxTool() { return ""; }
    }
}