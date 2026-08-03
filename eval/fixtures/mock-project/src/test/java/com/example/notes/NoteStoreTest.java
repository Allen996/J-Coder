package com.example.notes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteStoreTest {

    private NoteStore store;

    @BeforeEach
    void setUp() {
        IdGenerator idGenerator = new SequentialIdGenerator();
        Clock clock = new SystemClock();
        NoteRepository repository = new InMemoryNoteRepository();
        store = new NoteStore(idGenerator, clock, repository);
    }

    @Test
    void addAssignsSequentialIds() {
        Note a = store.add("first", "body one");
        Note b = store.add("second", "body two");
        assertEquals(1L, a.id());
        assertEquals(2L, b.id());
    }

    @Test
    void listReturnsInsertionOrder() {
        store.add("a", "1");
        store.add("b", "2");
        var ids = store.list().stream().map(Note::id).toList();
        assertEquals(java.util.List.of(1L, 2L), ids);
    }

    @Test
    void getReturnsExistingNote() {
        Note added = store.add("title", "body");
        Note fetched = store.get(added.id());
        assertNotNull(fetched);
        assertEquals("title", fetched.title());
        assertEquals("body", fetched.body());
    }

    @Test
    void getReturnsNullForMissingId() {
        assertNull(store.get(999L));
    }

    @Test
    void deleteRemovesExistingNote() {
        Note added = store.add("title", "body");
        assertTrue(store.delete(added.id()));
        assertNull(store.get(added.id()));
    }

    @Test
    void deleteReturnsFalseForMissingId() {
        assertFalse(store.delete(123L));
    }

    @Test
    void countReflectsInsertionsAndDeletions() {
        store.add("a", "1");
        store.add("b", "2");
        assertEquals(2, store.count());
        store.delete(1L);
        assertEquals(1, store.count());
    }

    @Test
    void searchReturnsMatchingNotesByTitle() {
        store.add("Java Tutorial", "Learn Java");
        store.add("Python Guide", "Learn Python");
        var results = store.search("Java");
        assertEquals(1, results.size());
        assertEquals("Java Tutorial", results.get(0).title());
    }

    @Test
    void searchReturnsMatchingNotesByBody() {
        store.add("Title A", "Contains Java keyword");
        store.add("Title B", "Contains Python keyword");
        var results = store.search("Java");
        assertEquals(1, results.size());
        assertEquals("Title A", results.get(0).title());
    }

    @Test
    void searchIsCaseInsensitive() {
        store.add("Hello World", "Greetings");
        var results = store.search("hello");
        assertEquals(1, results.size());
    }

    @Test
    void searchReturnsEmptyWhenNoMatch() {
        store.add("Java Tutorial", "Learn Java");
        var results = store.search("nonexistent");
        assertTrue(results.isEmpty());
    }

    @Test
    void searchThrowsOnNullKeyword() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> store.search(null)
        );
    }
}
