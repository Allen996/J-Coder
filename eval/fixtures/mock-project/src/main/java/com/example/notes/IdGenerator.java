package com.example.notes;

/**
 * IdGenerator - ID 生成层接口
 * 
 * 职责：提供唯一标识符的生成策略
 * 
 * 设计原则：
 * - 接口定义与实现分离，支持多种 ID 生成算法（自增序列、UUID、Snowflake 等）
 * - 线程安全由具体实现保证
 * - ID 的唯一性和单调性由实现方承诺
 * 
 * 默认实现：SequentialIdGenerator（基于 AtomicLong 的自增序列，从 1 开始）
 */
public interface IdGenerator {
    
    /**
     * 生成下一个唯一 ID
     * 
     * @return 唯一的正整数 ID
     */
    long nextId();
}
