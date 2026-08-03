package com.example.notes;

/**
 * CommandHandler - 命令解析与输出格式化层
 * 
 * 职责：
 * - 解析命令行参数
 * - 调用 NoteStore 的业务方法（不直接接触 ID 生成、时间戳、持久化层）
 * - 将结果格式化为字符串输出
 * 
 * 设计原则：遵循依赖倒置，只依赖业务编排层（NoteStore），
 * 不直接访问底层实现细节（IdGenerator、Clock、NoteRepository）。
 */
public class CommandHandler {

    /** 业务编排层：通过 NoteStore 间接使用底层服务 */
    private final NoteStore store;

    /**
     * 构造函数：注入 NoteStore 实例
     * 
     * @param store 业务编排层实例，负责协调 ID 生成、时间戳和持久化
     */
    public CommandHandler(NoteStore store) {
        this.store = store;
    }

    public String handle(String[] args) {
        if (args == null || args.length == 0) {
            return "usage: add | list | get <id> | delete <id> | count | search <keyword>";
        }
        String cmd = args[0];
        switch (cmd) {
            case "add": {
                if (args.length < 3) {
                    return "usage: add <title> <body>";
                }
                String title = args[1];
                StringBuilder body = new StringBuilder();
                for (int i = 2; i < args.length; i++) {
                    if (i > 2) body.append(' ');
                    body.append(args[i]);
                }
                Note note = store.add(title, body.toString());
                return "added #" + note.id() + " " + note.title();
            }
            case "list": {
                StringBuilder out = new StringBuilder();
                for (Note n : store.list()) {
                    out.append("#").append(n.id()).append(' ').append(n.title()).append('\n');
                }
                return out.length() == 0 ? "(empty)" : out.toString().trim();
            }
            case "get": {
                if (args.length < 2) {
                    return "usage: get <id>";
                }
                long id;
                try {
                    id = Long.parseLong(args[1]);
                } catch (NumberFormatException e) {
                    return "invalid id: must be a number";
                }
                Note n = store.get(id);
                return n == null ? "not found" : "#" + n.id() + " " + n.title() + "\n" + n.body();
            }
            case "delete": {
                if (args.length < 2) {
                    return "usage: delete <id>";
                }
                long id;
                try {
                    id = Long.parseLong(args[1]);
                } catch (NumberFormatException e) {
                    return "invalid id: must be a number";
                }
                return store.delete(id) ? "deleted" : "not found";
            }
            case "count": {
                return String.valueOf(store.count());
            }
            case "search": {
                if (args.length < 2) {
                    return "usage: search <keyword>";
                }
                String keyword = args[1];
                // 支持多词搜索：将剩余参数拼接为完整关键字
                if (args.length > 2) {
                    StringBuilder sb = new StringBuilder(keyword);
                    for (int i = 2; i < args.length; i++) {
                        sb.append(' ').append(args[i]);
                    }
                    keyword = sb.toString();
                }
                java.util.List<Note> results = store.search(keyword);
                if (results.isEmpty()) {
                    return "(no matches)";
                }
                StringBuilder out = new StringBuilder();
                for (Note n : results) {
                    out.append("#").append(n.id()).append(' ').append(n.title()).append('\n');
                }
                return out.toString().trim();
            }
            default:
                return "unknown command: " + cmd;
        }
    }
}