package com.example.notes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryNoteRepositoryTest {

    private InMemoryNoteRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryNoteRepository();
    }

    @Test
    void saveAndFindById() {
        Note note = new Note(1L, "title", "body", 1000L);
        repository.save(note);
        Optional<Note> found = repository.findById(1L);
        assertTrue(found.isPresent());
        assertEquals("title", found.get().title());
        assertEquals("body", found.get().body());
    }

    @Test
    void findByIdReturnsEmptyForMissingId() {
        Optional<Note> found = repository.findById(999L);
        assertFalse(found.isPresent(), "Should return empty for non-existent ID");
    }

    @Test
    void findAllReturnsAllSavedNotes() {
        repository.save(new Note(1L, "first", "body1", 1000L));
        repository.save(new Note(2L, "second", "body2", 2000L));
        List<Note> all = repository.findAll();
        assertEquals(2, all.size());
    }

    @Test
    void findAllReturnsDefensiveCopy() {
        repository.save(new Note(1L, "title", "body", 1000L));
        List<Note> list1 = repository.findAll();
        List<Note> list2 = repository.findAll();
        assertFalse(list1 == list2, "Should return different list instances");
    }

    @Test
    void deleteByIdRemovesExistingNote() {
        repository.save(new Note(1L, "title", "body", 1000L));
        boolean deleted = repository.deleteById(1L);
        assertTrue(deleted);
        assertFalse(repository.findById(1L).isPresent());
    }

    @Test
    void deleteByIdReturnsFalseForMissingId() {
        boolean deleted = repository.deleteById(999L);
        assertFalse(deleted, "Should return false when deleting non-existent ID");
    }

    @Test
    void countReflectsSavesAndDeletes() {
        assertEquals(0, repository.count());
        repository.save(new Note(1L, "a", "1", 1000L));
        repository.save(new Note(2L, "b", "2", 2000L));
        assertEquals(2, repository.count());
        repository.deleteById(1L);
        assertEquals(1, repository.count());
    }

    @Test
    void findAllReturnsEmptyListWhenNoNotes() {
        List<Note> all = repository.findAll();
        assertTrue(all.isEmpty());
    }

    @Test
    void saveWithNullNoteThrowsNullPointerException() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> repository.save(null),
                "Saving null note should throw NullPointerException");
    }

    @Test
    void searchWithEmptyKeywordReturnsAllNotes() {
        repository.save(new Note(1L, "title", "body", 1000L));
        repository.save(new Note(2L, "another", "content", 2000L));
        List<Note> results = repository.search("");
        assertEquals(2, results.size(),
                "Search with empty keyword should return all notes");
    }

    @Test
    void searchIsCaseInsensitive() {
        repository.save(new Note(1L, "Hello World", "Test Body", 1000L));
        List<Note> results = repository.search("hello");
        assertEquals(1, results.size(),
                "Search should be case insensitive");
        assertEquals("Hello World", results.get(0).title());
    }

    @Test
    void searchReturnsEmptyListWhenNoMatch() {
        repository.save(new Note(1L, "title", "body", 1000L));
        List<Note> results = repository.search("nonexistent");
        assertTrue(results.isEmpty(),
                "Search with no matches should return empty list");
    }

    @Test
    void countReturnsZeroForEmptyRepository() {
        assertEquals(0, repository.count(),
                "Count should be zero for empty repository");
    }

    @Test
    void searchWithNullKeywordThrowsNullPointerException() {
        // 失败路径：搜索空关键字应抛出异常
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> repository.search(null),
                "Searching with null keyword should throw NullPointerException");
    }

    @Test
    void saveDuplicateIdAllowsDuplicates() {
        // 边界情况：内存实现允许重复 ID（这不是 bug，而是设计选择）
        Note note1 = new Note(1L, "first", "body1", 1000L);
        Note note2 = new Note(1L, "second", "body2", 2000L);
        repository.save(note1);
        repository.save(note2);
        assertEquals(2, repository.count(),
                "InMemoryNoteRepository allows duplicate IDs (by design)");
    }
}
