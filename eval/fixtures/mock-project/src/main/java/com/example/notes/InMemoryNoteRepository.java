package com.example.notes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class InMemoryNoteRepository implements NoteRepository {

    private final List<Note> notes = new ArrayList<>();
    private final int maxCount;

    /**
     * 构造函数：使用默认最大条数（从配置文件加载）
     */
    public InMemoryNoteRepository() {
        this(ConfigLoader.loadMaxCount());
    }

    /**
     * 构造函数：指定最大条数
     * 
     * @param maxCount 最大笔记条数，必须为正整数
     */
    public InMemoryNoteRepository(int maxCount) {
        if (maxCount <= 0) {
            throw new IllegalArgumentException("maxCount must be positive, got: " + maxCount);
        }
        this.maxCount = maxCount;
    }

    @Override
    public void save(Note note) {
        if (notes.size() >= maxCount) {
            throw new IllegalStateException(
                "Cannot add more notes. Maximum limit of " + maxCount + " notes reached."
            );
        }
        notes.add(note);
    }

    @Override
    public List<Note> findAll() {
        return new ArrayList<>(notes);
    }

    @Override
    public Optional<Note> findById(long id) {
        for (Note n : notes) {
            if (n.id() == id) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean deleteById(long id) {
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).id() == id) {
                notes.remove(i);
                return true;
            }
        }
        return false;
    }

    @Override
    public long count() {
        return notes.size();
    }

    @Override
    public List<Note> search(String keyword) {
        String lowerKeyword = keyword.toLowerCase();
        List<Note> result = new ArrayList<>();
        for (Note n : notes) {
            if (n.title().toLowerCase().contains(lowerKeyword) ||
                n.body().toLowerCase().contains(lowerKeyword)) {
                result.add(n);
            }
        }
        return result;
    }
}
