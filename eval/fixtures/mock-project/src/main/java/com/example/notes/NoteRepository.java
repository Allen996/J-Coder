package com.example.notes;

import java.util.List;
import java.util.Optional;

/**
 * NoteRepository - 持久化层接口
 * 
 * 职责：定义笔记数据的存储、检索、删除和查询操作契约
 * 
 * 设计原则：
 * - 接口与实现分离，支持多种存储后端（内存、文件系统、数据库等）
 * - 所有读取操作返回防御性副本或不可变对象，避免外部修改内部状态
 * - 搜索操作支持大小写不敏感的关键字匹配
 * 
 * 默认实现：InMemoryNoteRepository（基于 ArrayList 的内存存储，进程重启后数据丢失）
 */
public interface NoteRepository {
    
    /**
     * 保存笔记
     * 
     * @param note 要保存的笔记对象
     */
    void save(Note note);
    
    /**
     * 查询所有笔记（按插入顺序）
     * 
     * @return 笔记列表的防御性副本
     */
    List<Note> findAll();
    
    /**
     * 根据 ID 查询单个笔记
     * 
     * @param id 笔记 ID
     * @return 包含笔记的 Optional，若不存在则返回 empty
     */
    Optional<Note> findById(long id);
    
    /**
     * 根据 ID 删除笔记
     * 
     * @param id 笔记 ID
     * @return 若删除成功返回 true，若笔记不存在返回 false
     */
    boolean deleteById(long id);
    
    /**
     * 统计笔记总数
     * 
     * @return 当前存储的笔记数量
     */
    long count();
    
    /**
     * 关键字搜索（大小写不敏感）
     * 
     * @param keyword 搜索关键字
     * @return 标题或正文包含关键字的笔记列表
     */
    List<Note> search(String keyword);
}
