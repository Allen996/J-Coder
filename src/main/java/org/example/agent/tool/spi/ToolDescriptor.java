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
 */
public record ToolDescriptor(
        String name,
        ToolRisk risk,
        boolean reversible,
        boolean commandGate,
        String description
) {

    public static ToolDescriptor low(String name, String description) {
        return new ToolDescriptor(name, ToolRisk.LOW, false, false, description);
    }

    public static ToolDescriptor medium(String name, boolean reversible, String description) {
        return new ToolDescriptor(name, ToolRisk.MEDIUM, reversible, false, description);
    }

    public static ToolDescriptor high(String name, String description) {
        return new ToolDescriptor(name, ToolRisk.HIGH, false, true, description);
    }
}
