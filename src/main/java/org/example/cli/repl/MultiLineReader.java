package org.example.cli.repl;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 多行输入状态机。判断当前累积的输入是否还需要继续读行。
 *
 * 触发多行的条件（任一满足即继续）：
 *  1. 双引号 " 数量为奇数
 *  2. 反引号 ` 数量为奇数
 *  3. 花括号/方括号/圆括号不平衡 —— 通过栈深度判断
 *  4. 当前行是 """  开头（三引号显式收尾符）
 *
 * 实现说明：
 *  - 跳过字符串/反引号内的括号，避免误判（实现：只对处于"代码态"的字符计数）
 *  - """ 收尾优先于"未闭合"判定（用户主动结束意图）
 */
public class MultiLineReader {

    /** 累积行 + 是否还需要继续读。 */
    public record Accumulation(String joined, boolean needsMore) {
    }

    public Accumulation feed(String firstLine) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inDoubleQuote = false;
        boolean inBacktick = false;
        int totalLines = 1;
        String joined = firstLine == null ? "" : firstLine;

        // 显式 """ 收尾
        if (firstLine != null && firstLine.stripLeading().startsWith("\"\"\"")) {
            return new Accumulation(joined, false);
        }

        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (c == '"' && !inBacktick) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == '`' && !inDoubleQuote) {
                inBacktick = !inBacktick;
            } else if (!inDoubleQuote && !inBacktick) {
                if (c == '{' || c == '[' || c == '(') {
                    stack.push(c);
                } else if (c == '}' || c == ']' || c == ')') {
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                }
            }
        }

        boolean needsMore = inDoubleQuote || inBacktick || !stack.isEmpty();
        return new Accumulation(joined, needsMore);
    }

    /**
     * 追加一行（续行），重新判定是否还需要继续。
     */
    public Accumulation append(Accumulation acc, String nextLine) {
        if (nextLine == null) {
            return acc;
        }
        // 显式 """ 收尾
        if (nextLine.stripLeading().startsWith("\"\"\"")) {
            return new Accumulation(acc.joined(), false);
        }
        String joined = acc.joined() + "\n" + nextLine;
        return feedFromScratch(joined);
    }

    private Accumulation feedFromScratch(String joined) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inDoubleQuote = false;
        boolean inBacktick = false;

        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (c == '"' && !inBacktick) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == '`' && !inDoubleQuote) {
                inBacktick = !inBacktick;
            } else if (!inDoubleQuote && !inBacktick) {
                if (c == '{' || c == '[' || c == '(') {
                    stack.push(c);
                } else if (c == '}' || c == ']' || c == ')') {
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                }
            }
        }
        boolean needsMore = inDoubleQuote || inBacktick || !stack.isEmpty();
        return new Accumulation(joined, needsMore);
    }
}