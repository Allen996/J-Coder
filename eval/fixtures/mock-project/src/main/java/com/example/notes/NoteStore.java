package com.example.notes;

import java.util.List;
import java.util.Objects;

/**
 * NoteStore - 业务编排层（协调层）
 * 
 * 分层架构说明：
 * - ID 生成层：通过 IdGenerator 接口获取唯一标识符（默认实现：SequentialIdGenerator）
 * - 时间服务层：通过 Clock 接口获取当前时间戳（默认实现：SystemClock）
 * - 持久化层：通过 NoteRepository 接口存储和检索数据（默认实现：InMemoryNoteRepository）
 * 
 * 本类负责协调这三层，实现业务逻辑的编排，但不直接实现任何一层的具体功能。
 */
public class NoteStore {

    // ========== 分层依赖注入 ==========
    
    /** ID 生成层：负责分配唯一标识符 */
    private final IdGenerator idGenerator;
    
    /** 时间服务层：提供可测试的时间上下文 */
    private final Clock clock;
    
    /** 持久化层：负责数据的存储与检索 */
    private final NoteRepository repository;

    /**
     * 构造函数：注入三个独立的分层接口
     * 
     * @param idGenerator ID 生成器接口实现
     * @param clock       时钟服务接口实现
     * @param repository  数据仓储接口实现
     */
    public NoteStore(IdGenerator idGenerator, Clock clock, NoteRepository repository) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * 添加笔记 - 显式展示三层协作流程
     * 
     * 执行步骤：
     * 1. [ID 生成层] 调用 idGenerator.nextId() 获取唯一 ID
     * 2. [时间服务层] 调用 clock.now() 获取创建时间戳
     * 3. [领域模型] 构造不可变的 Note 对象
     * 4. [持久化层] 调用 repository.save() 存储笔记
     * 
     * @param title 笔记标题
     * @param body  笔记正文
     * @return 创建的 Note 对象
     */
    public Note add(String title, String body) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        
        // 第 1 层：ID 生成
        long id = idGenerator.nextId();
        
        // 第 2 层：时间戳获取
        long createdAt = clock.now();
        
        // 构造领域对象
        Note note = new Note(id, title, body, createdAt);
        
        // 第 3 层：持久化
        repository.save(note);
        
        return note;
    }

    public List<Note> list() {
        return repository.findAll();
    }

    public Note get(long id) {
        return repository.findById(id).orElse(null);
    }

    public boolean delete(long id) {
        return repository.deleteById(id);
    }

    public int count() {
        return (int) repository.count();
    }

    public List<Note> search(String keyword) {
        Objects.requireNonNull(keyword, "keyword");
        return repository.search(keyword);
    }
}
