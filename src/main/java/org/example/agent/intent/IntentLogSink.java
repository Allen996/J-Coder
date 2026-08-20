package org.example.agent.intent;

/**
 * L1/L2 日志接收器。设计稿 §8:每次 L1 / L2 调用都写入 IntentLog,
 * 字段含时间戳、executionId、原始输入、模型原始输出、最终 confidence、
 * 决策、关键词命中、降级原因。
 *
 * <p>多 sink 模式,便于接入 verify.log / 内置 deque 等。
 */
public interface IntentLogSink {

    void onL1(IntentGate.IntentLogEvent event);

    void onL2(IntentGate.IntentLogEvent event);
}