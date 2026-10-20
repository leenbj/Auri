---
status: accepted
---
# ADR-009：自动发送按 Destination 显式授权，修订不自动重发

产品负责人要求“转录后自动发送”，而 PRD 要求音频上传、文本入库、Agent 读取、自动发送各自独立授权，且转录文本是数据而非指令。因此：创建 Destination 时必须显式选择“自动发送”或“预览确认”，不设隐含默认，这一选择作为独立的授权记录保存，与“Agent 可读取”的授权分开；自动发送的是 ASR 初稿 Revision，并标记 `review_state=not_reviewed`；用户事后改字产生的新 Revision，默认不自动重发给已收到旧版本的 Destination，须用户确认。

## Considered Options

- **全局默认自动发送**：否决，授权必须由用户对每个 Destination 显式作出，不能靠默认值代为同意。
- **全局默认预览确认（PRD 第 2.2 节原默认）**：否决，免手持是耳机录音的核心价值；改为创建 Destination 时一次性选择，免去逐条确认。
- **新 Revision 自动重发**：否决，Agent 可能已按旧 Revision 行动，自动重发会让 `action_request` 重复执行（对应 FR-TXT-002）。

## Consequences

- 创建 Destination 的流程（App 内与网关侧）必须包含这一选择，缺省时不允许完成创建。
- 选了“预览确认”的 Destination，在用户确认前不为它创建 Delivery；选了“自动发送”的，ASR 完成后随 Revision 提交网关并立即创建 Delivery。
- 重发是针对某个 Destination 的显式用户操作；Agent 侧据 `review_state` 与 Revision 编号自行区分新旧版本。
