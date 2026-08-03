package com.example.notes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandHandlerTest {

    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        IdGenerator idGenerator = new SequentialIdGenerator();
        Clock clock = new SystemClock();
        NoteRepository repository = new InMemoryNoteRepository();
        NoteStore store = new NoteStore(idGenerator, clock, repository);
        handler = new CommandHandler(store);
    }

    @Test
    void handleNullArgsReturnsUsage() {
        String result = handler.handle(null);
        assertTrue(result.contains("usage"));
    }

    @Test
    void handleEmptyArgsReturnsUsage() {
        String result = handler.handle(new String[]{});
        assertTrue(result.contains("usage"));
    }

    @Test
    void handleUnknownCommandReturnsError() {
        String result = handler.handle(new String[]{"unknown"});
        assertEquals("unknown command: unknown", result);
    }

    @Test
    void handleAddWithMissingBodyReturnsUsage() {
        String result = handler.handle(new String[]{"add", "title"});
        assertEquals("usage: add <title> <body>", result);
    }

    @Test
    void handleAddSuccess() {
        String result = handler.handle(new String[]{"add", "My Title", "My Body"});
        assertTrue(result.startsWith("added #1 My Title"));
    }

    @Test
    void handleListWhenEmpty() {
        String result = handler.handle(new String[]{"list"});
        assertEquals("(empty)", result);
    }

    @Test
    void handleListWithNotes() {
        handler.handle(new String[]{"add", "First", "Body 1"});
        handler.handle(new String[]{"add", "Second", "Body 2"});
        String result = handler.handle(new String[]{"list"});
        assertTrue(result.contains("#1 First"));
        assertTrue(result.contains("#2 Second"));
    }

    @Test
    void handleGetWithInvalidId() {
        String result = handler.handle(new String[]{"get", "abc"});
        assertEquals("invalid id: must be a number", result);
    }

    @Test
    void handleGetNotFound() {
        String result = handler.handle(new String[]{"get", "999"});
        assertEquals("not found", result);
    }

    @Test
    void handleGetSuccess() {
        handler.handle(new String[]{"add", "Title", "Body"});
        String result = handler.handle(new String[]{"get", "1"});
        assertTrue(result.contains("#1 Title"));
        assertTrue(result.contains("Body"));
    }

    @Test
    void handleDeleteWithInvalidId() {
        String result = handler.handle(new String[]{"delete", "abc"});
        assertEquals("invalid id: must be a number", result);
    }

    @Test
    void handleDeleteNotFound() {
        String result = handler.handle(new String[]{"delete", "999"});
        assertEquals("not found", result);
    }

    @Test
    void handleDeleteSuccess() {
        handler.handle(new String[]{"add", "Title", "Body"});
        String result = handler.handle(new String[]{"delete", "1"});
        assertEquals("deleted", result);
    }

    @Test
    void handleCount() {
        handler.handle(new String[]{"add", "A", "1"});
        handler.handle(new String[]{"add", "B", "2"});
        String result = handler.handle(new String[]{"count"});
        assertEquals("2", result);
    }

    @Test
    void handleSearchWithMissingKeywordReturnsUsage() {
        String result = handler.handle(new String[]{"search"});
        assertEquals("usage: search <keyword>", result);
    }

    @Test
    void handleSearchNoMatches() {
        String result = handler.handle(new String[]{"search", "nonexistent"});
        assertEquals("(no matches)", result);
    }

    @Test
    void handleSearchSuccess() {
        handler.handle(new String[]{"add", "Java Tutorial", "Learn Java programming"});
        handler.handle(new String[]{"add", "Python Guide", "Learn Python basics"});
        String result = handler.handle(new String[]{"search", "Java"});
        assertTrue(result.contains("#1 Java Tutorial"));
        // 应该只匹配到包含 "Java" 的笔记
        assertTrue(!result.contains("Python"));
    }

    @Test
    void handleSearchCaseInsensitive() {
        handler.handle(new String[]{"add", "Hello World", "Greetings"});
        String result = handler.handle(new String[]{"search", "hello"});
        assertTrue(result.contains("#1 Hello World"));
    }

    @Test
    void handleSearchWithMultipleKeywords() {
        handler.handle(new String[]{"add", "Spring Boot Guide", "Build web apps with Spring"});
        String result = handler.handle(new String[]{"search", "Spring Boot"});
        assertTrue(result.contains("#1 Spring Boot Guide"));
    }
}
