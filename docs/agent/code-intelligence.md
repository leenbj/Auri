# 代码索引

- GitNexus 提供符号检索、调用图和影响面分析。索引保存在 Mac 主检出 `/Users/ethan/Desktop/CODE/auri/.gitnexus/`，不提交到 git。
- aoci 提供可随仓库读取的文本索引：见仓库根目录 `aoci.txt`、`aoci.meta.txt`、`aoci.code.txt`。

## Mac 开发 agent

改不熟悉的代码前先查 GitNexus，再在当前 worktree 用 `rg` 和源码核对。例如：

```bash
/Users/ethan/.local/bin/gitnexus context claim_deliveries --repo /Users/ethan/Desktop/CODE/auri
rg -n 'claim_deliveries' services/gateway
```

Codex 也可调用 GitNexus MCP 的 `query`、`context`、`impact`，将 `repo` 指定为 `/Users/ethan/Desktop/CODE/auri`。其他 worktree 可通过 `~/.gitnexus/` 全局注册表访问主检出索引；有多个仓库或同名索引时必须指定该绝对路径。

索引只反映主检出的已索引内容，不包含其他 worktree 尚未合并的改动；调用图可能遗漏引用，须以当前源码和搜索结果核对。

每次合并进 main 后，在 Mac 主检出刷新：

```bash
(cd /Users/ethan/Desktop/CODE/auri && /Users/ethan/.local/bin/gitnexus analyze --index-only)
```

GitNexus 使用 PolyForm Noncommercial 许可证，商用前须复核。

## Cage 中的 Claude

Cage 不运行这两个工具：直接读取仓库中的 `aoci.*` 文本；需要 GitNexus 调用图或影响面时，派 Mac worker 查询并带回结论。

## 派发规格片段

> 改代码前先读 `docs/agent/code-intelligence.md`；在 Mac 用主检出索引查符号、调用关系和影响面，再用当前 worktree 的 `rg` 与源码核对。Cage 读取 `aoci.*`，需要调用图时派 Mac worker 查询。
