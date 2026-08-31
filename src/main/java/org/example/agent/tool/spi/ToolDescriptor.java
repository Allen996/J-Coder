package org.example.agent.tool.spi;

/**
 * 工具的元数据描述。
 *
 * <p>由 {@link ToolDescriptorRegistry} 集中维护 —— 工具实现类（{@code @Tool} 方法所在 bean）
 * 不直接持有本描述。原因：保持 {@code @Tool} 类的 POJO 形态，方便 Spring AI 通过反射识别；
 * 风险等级 / 可逆性 / 是否走命令闸 这些是"我们的策略"，不属于工具自身语义。
 *
 * @param name          工具名（必须与 {@code @Tool(name=...)} 一致或与方法名一致）
 * @param risk          风险等级，决定是否需要用户授权
 * @param reversible    调用失败时是否可由 SideEffectTracker 自动回滚
 * @param commandGate   是否走 CommandGate（仅 shell 类工具为 true）
 * @param description   人类可读的简短描述，写入 prompt / 日志
 * @param timeoutMs     单次调用超时（毫秒）。{@code 0} 表示沿用 {@code cli.tool.default-timeout-ms}
 * @param cacheable     是否可外置到磁盘缓存（recall_tool_result 召回）
 * @param readonly      是否无副作用。readonly 工具可在 ReActLoop 中并发分发
 * @param mainAgentOnly 是否仅主 Agent 可见。true 时 SubAgent 工具注册会被过滤掉（物理隔离）
 */
public record ToolDescriptor(
        String name,
        ToolRisk risk,
        boolean reversible,
        boolean commandGate,
        String description,
        long timeoutMs,
        boolean cacheable,
        boolean readonly,
        boolean mainAgentOnly
) {

    /** 默认 LOW：不需授权、不走命令闸、不写盘、可外置、可并发、SubAgent 可见。 */
    public static ToolDescriptor low(String name, String description) {
        return new ToolDescriptor(name, ToolRisk.LOW, false, false, description,
                0L, true, true, false);
    }

    /** LOW 但不可外置缓存（如 check_command_exists,结果依赖 PATH 状态）。可并发、SubAgent 可见。 */
    public static ToolDescriptor lowNonCacheable(String name, String description) {
        return new ToolDescriptor(name, ToolRisk.LOW, false, false, description,
                0L, false, true, false);
    }

    /** 默认 MEDIUM：写工具,不可外置、不可并发。{@code reversible} 由调用方指定。SubAgent 可见。 */
    public static ToolDescriptor medium(String name, boolean reversible, String description) {
        return new ToolDescriptor(name, ToolRisk.MEDIUM, reversible, false, description,
                0L, false, false, false);
    }

    /** HIGH：shell 类,不走外置缓存、不可并发。SubAgent 可见（默认）。 */
    public static ToolDescriptor high(String name, String description) {
        return new ToolDescriptor(name, ToolRisk.HIGH, false, true, description,
                0L, false, false, false);
    }

    /** 主 Agent 专属 LOW：与 low() 相同，但 {@code mainAgentOnly=true}。SubAgent 拿不到。 */
    public static ToolDescriptor lowMainOnly(String name, String description) {
        return new ToolDescriptor(name, ToolRisk.LOW, false, false, description,
                0L, true, true, true);
    }

    /** 主 Agent 专属 MEDIUM：与 medium() 相同，但 {@code mainAgentOnly=true}。SubAgent 拿不到。 */
    public static ToolDescriptor mediumMainOnly(String name, boolean reversible, String description) {
        return new ToolDescriptor(name, ToolRisk.MEDIUM, reversible, false, description,
                0L, false, false, true);
    }

    /** 自定义超时的便捷方法。 */
    public ToolDescriptor withTimeout(long timeoutMs) {
        return new ToolDescriptor(name, risk, reversible, commandGate, description,
                timeoutMs, cacheable, readonly, mainAgentOnly);
    }

    /** 标记为主 Agent 专属（覆盖现有 mainAgentOnly 字段）。 */
    public ToolDescriptor markMainAgentOnly() {
        return new ToolDescriptor(name, risk, reversible, commandGate, description,
                timeoutMs, cacheable, readonly, true);
    }
}
