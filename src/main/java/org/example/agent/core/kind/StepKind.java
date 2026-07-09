package org.example.agent.core.kind;

/**
 * 单步的语义类型。与 AgentEvent 的子类型一一对应但语义更精炼：
 * StepRecord 用 StepKind 直接索引，AgentEvent 用多态分发。
 */
public enum StepKind {
    THOUGHT,
    ACTION,
    OBSERVATION
}
