package com.example.notes;

import java.util.concurrent.atomic.AtomicLong;

/**
 * SequentialIdGenerator - ID 生成层的默认实现
 * 
 * 实现策略：基于 AtomicLong 的线程安全自增序列
 * - 初始值为 0，首次调用返回 1
 * - 保证线程安全（通过 AtomicLong 的原子操作）
 * - 保证单调递增（每次调用返回值严格大于前一次）
 * 
 * 局限性：
 * - 进程重启后 ID 序列重置（从 1 重新开始）
 * - 分布式部署时可能产生 ID 碰撞
 * - 不适用于需要全局唯一 ID 的场景
 * 
 * 改进方向：可替换为 UUID、Snowflake 算法或数据库序列生成器
 */
public class SequentialIdGenerator implements IdGenerator {

    /** 原子计数器，初始值为 0 */
    private final AtomicLong counter = new AtomicLong(0);

    /**
     * 生成下一个唯一 ID（从 1 开始递增）
     * 
     * @return 唯一的正整数 ID
     */
    @Override
    public long nextId() {
        return counter.incrementAndGet();
    }
}
