# 代码索引

- GitNexus 提供符号检索、调用图和影响面分析。索引保存在 Mac 主检出 `/Users/ethan/Desktop/CODE/auri/.gitnexus/`，不提交到 git。
- aoci 提供可随仓库读取的文本索引：见仓库根目录 `aoci.txt`、`aoci.meta.txt`、`aoci.code.txt`。

## aoci 文本索引与维护

按需直接读取 `aoci.txt` 和 `aoci.code.txt`；不要求每个会话完整读取 overview。

在 Mac 上修改代码并合并前，等当前 worktree 的文件稳定后，先获取该检出的维护指引：

```bash
/Users/ethan/.local/opt/aoci-code/0.2.0-rc2/bin/aoci --repo . index agent guide --agent codex --json
```

在关联该 worktree 的 Codex aoci MCP 中调用 `aoci_maintain({})`，阅读候选及相关源码，按返回指引通过 `aoci_update_entry` 提交完整批次；保留各项的 `path`、`candidate_id`、`source_sha256`，并将 `code_plan.batch_id` 原样作为 `code_batch_id`。删除文件时按返回的 `orphan_remove_candidates` 提交 `removals`；若 `remaining` 非零，继续维护并提交后续批次，直到对齐。不要用 `aoci scan --force` 覆盖基线来掩盖漂移。

最后在该 worktree 根目录执行，两个命令的预期退出码均为 0：

```bash
/Users/ethan/.local/opt/aoci-code/0.2.0-rc2/bin/aoci --repo . verify
/Users/ethan/.local/opt/aoci-code/0.2.0-rc2/bin/aoci --repo . check
```

`.gitattributes` 中的 `* text=auto eol=lf` 是 aoci 稳定原始字节基线所需的行尾规则，不要删除。

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
