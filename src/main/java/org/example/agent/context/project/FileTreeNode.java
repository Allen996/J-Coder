package org.example.agent.context.project;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * 文件树节点（part3.md §6.2 文件树深度 ≤ 3）。
 *
 * <p>递归结构，根节点 isDirectory=true 且 name="" 代表根目录自身。
 * children 在 builder 中按目录在前 / 文件在后的字典序固定，便于渲染时输出稳定。
 *
 * <p>不含文件内容 —— 文件内容会通过 {@link ProjectContext#getKeyConfigFiles()} 与 {@code ClaudeMd / readme} 单独注入；
 * 文件树只承载 "项目里有什么 / 路径是什么" 这层语义信息。
 */
@Getter
@Builder
@ToString(of = {"name", "relativePath", "isDirectory", "depth"})
public final class FileTreeNode {

    /** 节点名；根节点为 "". */
    private final String name;

    /** 相对 ProjectContext.root 的路径；根节点为 ""。 */
    private final String relativePath;

    /** 是否目录。 */
    private final boolean isDirectory;

    /** 深度（根 = 0）。 */
    private final int depth;

    /** 子节点；叶节点为空 List。 */
    private final List<FileTreeNode> children;

    public static FileTreeNode empty() {
        return FileTreeNode.builder()
                .name("")
                .relativePath("")
                .isDirectory(true)
                .depth(0)
                .children(List.of())
                .build();
    }
}