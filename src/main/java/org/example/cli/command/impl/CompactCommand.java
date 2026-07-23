package org.example.cli.command.impl;

import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.compression.ConversationCompressor;
import org.example.agent.context.session.SessionMessageStore;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CompactCommand implements SlashCommand {

    private final ConversationCompressor compressor;
    private final SessionMessageStore sessionStore;
    private final ContextBudgetPolicy policy;

    public CompactCommand(ConversationCompressor compressor,
                          SessionMessageStore sessionStore,
                          ContextBudgetPolicy policy) {
        this.compressor = compressor;
        this.sessionStore = sessionStore;
        this.policy = policy == null ? ContextBudgetPolicy.defaultPolicy() : policy;
    }

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "manually trigger context compression";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        String sessionId = ctx.session().getSessionId();
        SessionMessageStore.Session session = sessionStore.getOrCreate(sessionId);
        long beforeTokens = sessionStore.estimateUsedTokens(sessionId);
        List<Message> history = session.snapshot();
        if (history.isEmpty()) {
            ctx.out().println("compact: session history is empty, nothing to compress.");
            ctx.out().flush();
            return 0;
        }

        List<Message> compressed = compressor.compress(history, policy, null);
        session.replaceAll(compressed);
        long afterTokens = sessionStore.estimateUsedTokens(sessionId);
        ctx.out().printf("compact: %d messages -> %d messages, %d tokens -> %d tokens (budget %d)%n",
                history.size(), compressed.size(), beforeTokens, afterTokens, policy.sessionReserved());
        ctx.out().flush();
        return 0;
    }
}