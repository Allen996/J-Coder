package org.example.agent.core.task.verify;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * 验证命令模板（part5 §8.7）。
 *
 * <p>由 {@link VerifyRunner} 根据项目类型自适应生成：
 * <ul>
 *   <li>Java/Maven: {@code mvn -q compile && mvn -q test}</li>
 *   <li>Spring Boot: {@code mvn -q compile && mvn -q test-compile}</li>
 *   <li>其它: {@code ./build.sh} 或自描述</li>
 * </ul>
 */
@Getter
@Builder
@ToString(of = {"kind", "commands"})
public final class VerifyCommandTemplate {

    public enum Kind { MAVEN_COMPILE_TEST, MAVEN_COMPILE_TEST_COMPILE, GRADLE, NPM_TEST, GENERIC_BUILD }

    @Builder.Default
    private final Kind kind = Kind.GENERIC_BUILD;

    @Builder.Default
    private final List<String> commands = List.of();

    @Builder.Default
    private final String description = "";

    public String renderCommand() {
        return String.join(" && ", commands);
    }
}