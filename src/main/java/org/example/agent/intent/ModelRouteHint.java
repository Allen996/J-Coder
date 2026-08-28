package org.example.agent.intent;

/**
 * 模型档位提示。设计稿 §6:第一层分类的真正价值是路由到合适的模型。
 *
 * <ul>
 *   <li>{@link #LIGHT} —— 轻量档,适合 READ_CODE / CHAT_QA</li>
 *   <li>{@link #CODE}  —— 代码专精档,适合 WRITE_PROJECT / PLANNING</li>
 *   <li>{@link #GENERAL} —— 通用档,作为兜底</li>
 * </ul>
 */
public enum ModelRouteHint {
    LIGHT,
    CODE,
    GENERAL;

    public static ModelRouteHint parseLoose(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        return switch (s) {
            case "light", "l", "fast" -> LIGHT;
            case "code", "c", "coding" -> CODE;
            case "general", "g", "default" -> GENERAL;
            default -> null;
        };
    }
}