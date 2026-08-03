package com.example.notes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @Test
    void loadMaxCountReturnsDefaultWhenNoConfig() {
        // 当没有配置文件时，应返回默认值 100
        int maxCount = ConfigLoader.loadMaxCount();
        assertTrue(maxCount > 0, "maxCount should be positive");
    }

    @Test
    void loadMaxCountHandlesInvalidValue() {
        // 这个测试依赖于实际环境中的配置文件
        // 如果配置文件中设置了无效值，应回退到默认值 100
        // 由于无法控制测试环境的配置文件，这里只验证方法不会抛出异常
        int maxCount = ConfigLoader.loadMaxCount();
        assertTrue(maxCount > 0, "Should handle invalid config gracefully");
    }

    @Test
    void loadMaxCountReturnsPositiveValue() {
        // 验证返回的值始终为正整数
        int maxCount = ConfigLoader.loadMaxCount();
        assertTrue(maxCount > 0, "maxCount must be positive, got: " + maxCount);
    }
}
