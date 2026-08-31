package org.example.agent.context;

import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.context.compression.ConversationCompressor;
import org.example.agent.context.layer.DynamicLayer;
import org.example.agent.context.layer.StaticLayer;
import org.example.agent.context.memory.LongTermStore;
import org.example.agent.context.memory.MemoryIndex;
import org.example.agent.context.observability.PromptDumpObserver;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.event.AgentEvent;
import org.example.agent.core.event.FinishEvent;
import org.example.agent.core.impl.AgentRuntimeImpl;
import org.example.agent.core.task.AgentTask;
import org.example.agent.tool.rollback.RollbackSummary;
import org.example.agent.tool.rollback.SideEffectTracker;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.impl.ContextCommand;
import org.example.cli.session.SessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ContextRuntimeWiringTest {

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void persistsCompletedTurnsAndRendersTwoLayerDictionaryOverview() {
        CapturingChatModel chatModel = new CapturingChatModel(
                response("assistant-one"), response("assistant-two"));

        SessionMessageStore sessionStore = new SessionMessageStore();
        SessionState cliSession = new SessionState();
        String sessionId = cliSession.getSessionId();
        StaticLayer staticLayer = new StaticLayer();
        DynamicLayer dynamicLayer = new DynamicLayer();
        ContextBuilder contextBuilder = new ContextBuilder(
                ContextBudgetPolicy.defaultPolicy(),
                sessionStore,
                new ConversationCompressor(),
                staticLayer,
                dynamicLayer,
                new LongTermStore(),
                new MemoryIndex(),
                null);
        SideEffectTracker sideEffectTracker = new NoOpSideEffectTracker();
        ObjectProvider<ToolCallbackProvider> toolProvider =
                new StaticListableBeanFactory().getBeanProvider(ToolCallbackProvider.class);
        executor = Executors.newSingleThreadExecutor();

        AgentRuntimeImpl runtime = new AgentRuntimeImpl(
                chatModel,
                null,
                toolProvider,
                sideEffectTracker,
                contextBuilder,
                sessionStore,
                null,
                executor);
        PromptDumpObserver promptObserver = new PromptDumpObserver(runtime);
        promptObserver.register();
        promptObserver.register();

        assertThat(runtime.registeredObservers()).containsExactly(promptObserver);

        org.example.agent.core.result.AgentExecutionResult first =
                runtime.execute(task(sessionId, "first-question"));
        assertThat(first.getFinalAnswer()).isEqualTo("assistant-one");
        assertThat(messageTexts(sessionStore.get(sessionId).snapshot()))
                .containsExactly("first-question", "assistant-one");

        List<AgentEvent> secondEvents = runtime.stream(task(sessionId, "second-question"))
                .collectList()
                .block();
        assertThat(secondEvents).isNotNull();
        FinishEvent secondFinish = secondEvents.stream()
                .filter(FinishEvent.class::isInstance)
                .map(FinishEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(secondFinish.getFinalAnswer()).isEqualTo("assistant-two");
        assertThat(messageTexts(sessionStore.get(sessionId).snapshot()))
                .containsExactly("first-question", "assistant-one", "second-question", "assistant-two");

        assertThat(chatModel.prompts).hasSize(2);
        List<Message> secondPrompt = chatModel.prompts.get(1).getInstructions();
        List<String> secondPromptTexts = messageTexts(secondPrompt);

        assertThat(secondPromptTexts)
                .containsSubsequence("second-question");
        assertThat(secondPrompt.stream()
                .filter(UserMessage.class::isInstance)
                .map(SessionMessageStore::extractText)
                .filter("second-question"::equals))
                .hasSize(1);
        // 短期记忆以单段 messages 渲染，旧「每条 message 独立子段」已合并。
        // 验证历史确实在 prompts 中可见（不在 user message 里也能被模型看到）。
        String allText = String.join(" ", secondPromptTexts);
        assertThat(allText).contains("first-question").contains("assistant-one");

        PromptDumpObserver.Snapshot latest = promptObserver.latestSnapshot();
        assertThat(latest).isNotNull();
        assertThat(latest.getMessages()).containsExactlyElementsOf(secondPrompt);

        ContextCommand contextCommand = new ContextCommand(
                contextBuilder,
                ContextBudgetPolicy.defaultPolicy(),
                staticLayer,
                dynamicLayer,
                sessionStore,
                promptObserver);
        StringWriter output = new StringWriter();
        contextCommand.execute(
                "--full",
                new CliContext(runtime, cliSession, new PrintWriter(output, true), Path.of("."), null));

        String out = output.toString();
        assertThat(out)
                .contains("=== 2-LAYER DICTIONARY OVERVIEW ===")
                .contains("Static Layer")
                .contains("Dynamic Layer")
                .contains("role_definition")
                .contains("messages")
                .contains("memory_index")
                .contains("long_term")
                .contains("first-question", "assistant-one", "second-question")
                .doesNotContain("no LLM prompt captured yet");
    }

    private static AgentTask task(String sessionId, String input) {
        return AgentTask.builder()
                .sessionId(sessionId)
                .input(input)
                .role("chat")
                .promptId("chat.react-assistant")
                .build();
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static List<String> messageTexts(List<Message> messages) {
        return messages.stream().map(SessionMessageStore::extractText).toList();
    }

    private static final class CapturingChatModel implements ChatModel {
        private final Deque<ChatResponse> responses = new ArrayDeque<>();
        private final List<Prompt> prompts = new ArrayList<>();

        private CapturingChatModel(ChatResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return responses.removeFirst();
        }
    }

    private static final class NoOpSideEffectTracker implements SideEffectTracker {
        @Override
        public void bind(String executionId) { }

        @Override
        public void clear() { }

        @Override
        public void recordFileChange(String toolName, String path, byte[] preState) { }

        @Override
        public RollbackSummary rollbackAll() {
            return null;
        }
    }
}
