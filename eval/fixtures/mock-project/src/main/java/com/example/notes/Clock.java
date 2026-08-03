package com.example.notes;

/**
 * Clock - 时间服务层接口
 * 
 * 职责：提供当前时间戳的获取能力
 * 
 * 设计原则：
 * - 抽象系统时间依赖，使业务逻辑可测试
 * - 支持在测试中注入固定时间或可控时钟（如 FixedClock、MockClock）
 * - 返回毫秒级 Unix 时间戳
 * 
 * 默认实现：SystemClock（委托给 System.currentTimeMillis()）
 */
public interface Clock {
    
    /**
     * 获取当前时间戳（毫秒）
     * 
     * @return 当前时间的毫秒级 Unix 时间戳
     */
    long now();
}
