package com.example.notes;

/**
 * Main - 应用入口与依赖组装层
 * 
 * 职责：负责实例化各分层的具体实现，并通过构造函数注入到上层组件
 * 
 * 分层装配流程：
 * 1. ID 生成层：SequentialIdGenerator（基于 AtomicLong 的自增序列）
 * 2. 时间服务层：SystemClock（委托给 System.currentTimeMillis()）
 * 3. 持久化层：InMemoryNoteRepository（基于 ArrayList 的内存存储）
 * 4. 业务编排层：NoteStore（协调上述三层）
 * 5. 命令解析层：CommandHandler（处理 CLI 输入输出）
 */
public class Main {

    public static void main(String[] args) {
        // ===== 第 0 步：解析全局配置参数 =====
        
        // 从配置文件加载默认最大条数
        int maxCount = ConfigLoader.loadMaxCount();
        
        // 检查命令行参数中是否有 --max=N 覆盖配置
        String[] actualArgs = args;
        if (args != null && args.length > 0) {
            String lastArg = args[args.length - 1];
            if (lastArg.startsWith("--max=")) {
                try {
                    int overrideValue = Integer.parseInt(lastArg.substring(6));
                    if (overrideValue > 0) {
                        maxCount = overrideValue;
                    } else {
                        System.err.println("Warning: invalid --max value, using default/config value");
                    }
                    // 移除最后一个参数，获取实际命令参数
                    actualArgs = new String[args.length - 1];
                    System.arraycopy(args, 0, actualArgs, 0, args.length - 1);
                } catch (NumberFormatException e) {
                    System.err.println("Warning: invalid --max format, using default/config value");
                }
            }
        }
        
        // ===== 第 1 步：实例化各分层的具体实现 =====
        
        // ID 生成层实现
        IdGenerator idGenerator = new SequentialIdGenerator();
        
        // 时间服务层实现
        Clock clock = new SystemClock();
        
        // 持久化层实现（传入最大条数配置）
        NoteRepository repository = new InMemoryNoteRepository(maxCount);
        
        // ===== 第 2 步：组装业务编排层 =====
        
        // 将三个独立分层注入 NoteStore
        NoteStore store = new NoteStore(idGenerator, clock, repository);
        
        // ===== 第 3 步：组装命令解析层 =====
        
        // CommandHandler 只依赖 NoteStore，不直接接触底层实现
        CommandHandler handler = new CommandHandler(store);
        
        // ===== 第 4 步：执行并输出结果 =====
        
        System.out.println(handler.handle(actualArgs));
    }
}
