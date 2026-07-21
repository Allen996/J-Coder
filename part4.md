## 7. 会话持久化

### 7.1 存储

单文件 SQLite：`~/.local-cli-copilot/sessions.db`

- 不依赖外部服务
- 包含 WAL 模式 + 定期 checkpoint
- 首次启动时自动建表

### 7.2 Schema

```sql
CREATE TABLE session (
  id              TEXT PRIMARY KEY,
  title           TEXT,
  started_at      INTEGER NOT NULL,    -- epoch ms
  updated_at      INTEGER NOT NULL,
  model           TEXT,
  total_tokens_in  INTEGER DEFAULT 0,
  total_tokens_out INTEGER DEFAULT 0,
  metadata        TEXT                  -- JSON: project root, auto_approve 等
);

CREATE TABLE message (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id      TEXT NOT NULL,
  role            TEXT NOT NULL,         -- user/assistant/system/tool
  content         TEXT,
  tool_call_id    TEXT,                  -- tool result 时关联
  tool_calls      TEXT,                  -- JSON array，assistant 调用工具时
  tool_name       TEXT,                  -- tool result 时记录
  created_at      INTEGER NOT NULL,
  FOREIGN KEY (session_id) REFERENCES session(id)
);

CREATE INDEX idx_message_session ON message(session_id, id);

CREATE TABLE tool_invocation (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id      TEXT NOT NULL,
  message_id      INTEGER,
  tool_name       TEXT NOT NULL,
  args            TEXT,                  -- JSON
  result_redacted TEXT,                  -- 截断/脱敏
  status          TEXT,                  -- ok/denied/timeout/error
  duration_ms     INTEGER,
  authorized      INTEGER,               -- 0/1
  error           TEXT,
  created_at      INTEGER NOT NULL
);

CREATE INDEX idx_tool_session ON tool_invocation(session_id);
```

### 7.3 会话恢复

启动参数：
- `agent` — 新建会话
- `agent --resume <id>` — 恢复指定会话
- `agent --resume` — 交互式选择（`/resume` 命令也行）
- `agent --list-sessions` — 列出最近 20 个会话

恢复时：
1. 加载 session 元数据
2. 按需加载 message 历史（懒加载，避免一次拉全）
3. 重建 ProjectContext（项目根可能已经变了，重新扫描）

### 7.4 导出 / 重放

- `/export <path>` → 导出当前会话为 JSONL（每行一条 message + tool 调用）
- `--replay <path>` → 重放一个导出的会话（用于调试 prompt 改动）
- 导出会自动脱敏：token、密码、密钥全部替换为 `<REDACTED>`

---
