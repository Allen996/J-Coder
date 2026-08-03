package com.example.notes;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * ConfigLoader - 配置加载器
 * 
 * 职责：按优先级加载配置文件并解析配置项
 * 
 * 查找路径优先级（从高到低）：
 * 1. 当前工作目录 (cwd): ./notes.properties
 * 2. 用户主目录 (user home): ~/.notes/notes.properties
 * 3. classpath: /notes.properties (打包在 jar 中的默认配置)
 * 
 * 设计原则：
 * - 高优先级配置覆盖低优先级配置
 * - 找不到任何配置文件时使用硬编码默认值
 * - 无效配置值回退到默认值
 */
public class ConfigLoader {

    private static final String CONFIG_FILENAME = "notes.properties";
    private static final String MAX_COUNT_KEY = "notes.max.count";
    private static final int DEFAULT_MAX_COUNT = 100;

    /**
     * 加载最大笔记条数配置
     * 
     * @return 配置的最大条数，如果配置无效则返回默认值 100
     */
    public static int loadMaxCount() {
        Properties props = loadProperties();
        if (props == null) {
            return DEFAULT_MAX_COUNT;
        }
        
        String value = props.getProperty(MAX_COUNT_KEY);
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_MAX_COUNT;
        }
        
        try {
            int maxCount = Integer.parseInt(value.trim());
            if (maxCount > 0) {
                return maxCount;
            } else {
                // 非正整数，回退到默认值
                return DEFAULT_MAX_COUNT;
            }
        } catch (NumberFormatException e) {
            // 非法格式，回退到默认值
            return DEFAULT_MAX_COUNT;
        }
    }

    /**
     * 按优先级加载配置文件
     * 
     * @return 合并后的 Properties 对象，如果所有路径都找不到则返回 null
     */
    private static Properties loadProperties() {
        Properties merged = new Properties();
        boolean found = false;

        // 优先级 3（最低）: classpath
        try (InputStream is = ConfigLoader.class.getResourceAsStream("/" + CONFIG_FILENAME)) {
            if (is != null) {
                merged.load(is);
                found = true;
            }
        } catch (IOException e) {
            // 忽略 classpath 加载失败
        }

        // 优先级 2: user home (~/.notes/notes.properties)
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            File userConfig = new File(userHome, ".notes" + File.separator + CONFIG_FILENAME);
            if (userConfig.exists()) {
                try (FileInputStream fis = new FileInputStream(userConfig)) {
                    Properties userProps = new Properties();
                    userProps.load(fis);
                    merged.putAll(userProps); // 用户配置覆盖 classpath 配置
                    found = true;
                } catch (IOException e) {
                    // 忽略用户配置加载失败
                }
            }
        }

        // 优先级 1（最高）: cwd (./notes.properties)
        File cwdConfig = new File(CONFIG_FILENAME);
        if (cwdConfig.exists()) {
            try (FileInputStream fis = new FileInputStream(cwdConfig)) {
                Properties cwdProps = new Properties();
                cwdProps.load(fis);
                merged.putAll(cwdProps); // cwd 配置覆盖之前的所有配置
                found = true;
            } catch (IOException e) {
                // 忽略 cwd 配置加载失败
            }
        }

        return found ? merged : null;
    }
}
