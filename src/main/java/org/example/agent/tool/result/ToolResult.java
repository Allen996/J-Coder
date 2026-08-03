package org.example.agent.tool.result;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 工具的统一返回契约。
 *
 * <p>工具方法签名必须返回本类型（不允许返回裸 String / void / 业务对象），
 * 原因：
 * <ul>
 *   <li>CliRenderer 折叠展示需要结构化字段（status / 错误码 / 内容长度）</li>
 *   <li>Spring AI 收到的是 {@code content} 字段的明文，错误也走同一条路径，
 *       框架感知不到"这是失败"</li>
 *   <li>AuditLogger（5.5）从 status + errorCode 落库，结构化字段比字符串解析稳定</li>
 * </ul>
 *
 * <p>失败场景下 content 通常是简短的错误摘要（给 LLM 看的），完整诊断信息在 audit log。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(
        Status status,
        String content,
        ToolError error
) {

    public enum Status { OK, ERROR, DENIED }

    // ============== 工厂方法 ==============

    public static ToolResult ok(String content) {
        return new ToolResult(Status.OK, content, null);
    }

    public static ToolResult error(ToolError error) {
        return new ToolResult(Status.ERROR, null, error);
    }

    public static ToolResult error(ToolError error, String briefForLlm) {
        return new ToolResult(Status.ERROR, briefForLlm, error);
    }

    public static ToolResult denied(ToolError error) {
        return new ToolResult(Status.DENIED, null, error);
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    public boolean isError() {
        return status == Status.ERROR || status == Status.DENIED;
    }
}
