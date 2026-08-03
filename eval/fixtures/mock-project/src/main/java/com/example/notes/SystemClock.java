package com.example.notes;

/**
 * SystemClock - 时间服务层的默认实现
 * 
 * 实现策略：直接委托给 JVM 的 System.currentTimeMillis()
 * - 返回毫秒级 Unix 时间戳
 * - 精度取决于操作系统和 JVM 实现（通常为 1-15 毫秒）
 * 
 * 局限性：
 * - 无法在单元测试中模拟固定时间
 * - 受系统时钟调整影响（如 NTP 同步、夏令时切换）
 * 
 * 改进方向：在测试中使用 FixedClock 或 MockClock 注入可控时间戳
 */
public class SystemClock implements Clock {

    /**
     * 获取当前系统时间戳（毫秒）
     * 
     * @return 当前时间的毫秒级 Unix 时间戳
     */
    @Override
    public long now() {
        return System.currentTimeMillis();
    }
}
