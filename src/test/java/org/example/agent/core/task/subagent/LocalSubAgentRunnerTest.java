package org.example.agent.core.task.subagent;

import org.example.agent.core.handle.AgentHandle;
import org.example.agent.core.reason.FinishReason;
import org.example.agent.core.result.AgentExecutionResult;
import org.example.agent.core.runtime.AgentRuntime;
import org.example.agent.core.task.AgentTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LocalSubAgentRunner 超时检测测试（阶段 1）。
 *
 * <p>手写 stub 替换 Mockito —— JDK 23 上 Mockito-inline byte-buddy attach 失败。
 * 验证：
 * <ul>
 *   <li>正常完成：返回 COMPLETED + report</li>
 *   <li>超时：返回 TIMEOUT + 调用 cancel</li>
 *   <li>执行异常：返回 FAILED + reason</li>
 *   <li>CANCELLED / ERROR finishReason 映射到 TIMEOUT / FAILED</li>
 * </ul>
 */
class LocalSubAgentRunnerTest {

    @Test
    void completedReturnsReport() {
        StubRuntime runtime = new StubRuntime();
        runtime.nextResult = AgentExecutionResult.builder()
                .reason(FinishReason.FINISH)
                .finalAnswer("hello from sub")
                .executionId("exec-1")
                .totalSteps(3)
                .totalTokensUsed(100)
                .build();

        LocalSubAgentRunner runner = new LocalSubAgentRunner(runtime, stubContextBuilder(),
                new org.example.agent.context.session.SessionCompressor(), stubSessionStore());
        SubAgentResult r = runner.run(new SubAgentTask(
                "st-1", "t", "d", "o", List.of(), "p", "c", 5000));
        assertEquals(SubAgentStatus.COMPLETED, r.status());
        assertEquals("hello from sub", r.report());
        assertEquals(1, runtime.executeCalls.get());
    }

    @Test
    void timeoutReturnsTimeoutAndCancels() throws Exception {
        StubRuntime runtime = new StubRuntime();
        CountDownLatch executingLatch = new CountDownLatch(1);
        CountDownLatch releasedLatch = new CountDownLatch(1);
        AtomicReference<String> canceledId = new AtomicReference<>();

        runtime.executor = task -> {
            executingLatch.countDown();
            try {
                assertTrue(releasedLatch.await(30, TimeUnit.SECONDS));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            // cancel 之后,模拟 SubAgent 内部中断器响应 —— 返回 CANCELLED 而非 FINISH
            return AgentExecutionResult.builder()
                    .reason(FinishReason.CANCELLED)
                    .finalAnswer("cancelled-by-test")
                    .executionId("exec-late")
                    .totalSteps(1)
                    .totalTokensUsed(10)
                    .build();
        };
        runtime.canceller = id -> {
            canceledId.set(id);
            releasedLatch.countDown();
            return new StubHandle();
        };

        LocalSubAgentRunner runner = new LocalSubAgentRunner(runtime, stubContextBuilder(),
                new org.example.agent.context.session.SessionCompressor(), stubSessionStore());
        SubAgentResult r = runner.run(new SubAgentTask(
                "st-1", "t", "d", "o", List.of(), "p", "c", 200)); // 200ms 超时

        assertTrue(executingLatch.await(5, TimeUnit.SECONDS), "SubAgent execute should start");
        assertEquals(SubAgentStatus.TIMEOUT, r.status());
        assertTrue(r.reason().toLowerCase().contains("timeout") || r.reason().toLowerCase().contains("cancel"),
                "reason should mention timeout/cancel: " + r.reason());
        assertNotNull(canceledId.get(), "cancel() should be invoked");
    }

    @Test
    void runtimeExceptionReturnsFailed() {
        StubRuntime runtime = new StubRuntime();
        runtime.executor = task -> { throw new RuntimeException("boom"); };

        LocalSubAgentRunner runner = new LocalSubAgentRunner(runtime, stubContextBuilder(),
                new org.example.agent.context.session.SessionCompressor(), stubSessionStore());
        SubAgentResult r = runner.run(new SubAgentTask(
                "st-1", "t", "d", "o", List.of(), "p", "c", 5000));
        assertEquals(SubAgentStatus.FAILED, r.status());
        assertTrue(r.reason().contains("boom"), "reason should include cause: " + r.reason());
    }

    @Test
    void cancelledFinishReasonMapsToTimeout() {
        StubRuntime runtime = new StubRuntime();
        runtime.nextResult = AgentExecutionResult.builder()
                .reason(FinishReason.CANCELLED)
                .finalAnswer("cancelled mid-run")
                .executionId("exec-c")
                .totalSteps(1)
                .totalTokensUsed(10)
                .build();

        LocalSubAgentRunner runner = new LocalSubAgentRunner(runtime, stubContextBuilder(),
                new org.example.agent.context.session.SessionCompressor(), stubSessionStore());
        SubAgentResult r = runner.run(new SubAgentTask(
                "st-1", "t", "d", "o", List.of(), "p", "c", 5000));
        assertEquals(SubAgentStatus.TIMEOUT, r.status());
    }

    @Test
    void errorFinishReasonMapsToFailed() {
        StubRuntime runtime = new StubRuntime();
        runtime.nextResult = AgentExecutionResult.builder()
                .reason(FinishReason.ERROR)
                .finalAnswer("")
                .executionId("exec-e")
                .totalSteps(1)
                .totalTokensUsed(0)
                .build();

        LocalSubAgentRunner runner = new LocalSubAgentRunner(runtime, stubContextBuilder(),
                new org.example.agent.context.session.SessionCompressor(), stubSessionStore());
        SubAgentResult r = runner.run(new SubAgentTask(
                "st-1", "t", "d", "o", List.of(), "p", "c", 5000));
        assertEquals(SubAgentStatus.FAILED, r.status());
        assertTrue(r.reason().contains("ERROR"));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<org.example.agent.context.builder.ContextBuilder> stubContextBuilder() {
        return (ObjectProvider<org.example.agent.context.builder.ContextBuilder>) (ObjectProvider<?>) new EmptyObjectProvider();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<org.example.agent.context.session.SessionMessageStore> stubSessionStore() {
        return (ObjectProvider<org.example.agent.context.session.SessionMessageStore>) (ObjectProvider<?>) new EmptyObjectProvider();
    }

    // ====================== Stub AgentRuntime ======================

    interface RuntimeExecutor {
        AgentExecutionResult execute(AgentTask task);
    }

    interface RuntimeCanceller {
        AgentHandle cancel(String executionId);
    }

    static final class StubRuntime implements AgentRuntime {
        volatile RuntimeExecutor executor;
        volatile RuntimeCanceller canceller;
        volatile AgentExecutionResult nextResult;
        final AtomicInteger executeCalls = new AtomicInteger();

        @Override
        public AgentExecutionResult execute(AgentTask task) {
            executeCalls.incrementAndGet();
            if (executor != null) return executor.execute(task);
            return nextResult;
        }

        @Override
        public reactor.core.publisher.Flux<org.example.agent.core.event.AgentEvent> stream(AgentTask task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentHandle cancel(String executionId) {
            if (canceller != null) return canceller.cancel(executionId);
            return new StubHandle();
        }

        @Override
        public void registerObserver(org.example.agent.core.observer.ReActLoopObserver observer) { }

        @Override
        public List<org.example.agent.core.observer.ReActLoopObserver> registeredObservers() {
            return new ArrayList<>();
        }
    }

    static final class StubHandle extends AgentHandle {
        StubHandle() { super("exec-stub", () -> {}); }
    }

    /** 永不返回任何 bean 的 ObjectProvider stub。 */
    static final class EmptyObjectProvider implements ObjectProvider<Object> {
        @Override public Object getObject() { throw new RuntimeException("no beans"); }
        @Override public Object getObject(Object... args) { throw new RuntimeException("no beans"); }
        @Override public Object getIfAvailable() { return null; }
        @Override public Object getIfUnique() { return null; }
        @Override public java.util.Iterator<Object> iterator() {
            return java.util.Collections.<Object>emptyList().iterator();
        }
    }
}