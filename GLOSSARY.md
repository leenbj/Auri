# Auri（原工作代号 VoiceBridge）

通过蓝牙耳机手势控制录音、把口述转成文字，并经持久化网关交给获授权 Agent 读取的系统。本文件只记录领域词汇，不含实现细节。

## Language

### 采集

**Recording（录音）**:
一个 Record 的音频，只存在于手机本地，只包含有人声的片段；整段无人说话时为空。每个 Recording 属于且仅属于一个 Record。
_Avoid_: 音频文件、录音记录

**Strict Bluetooth Input（严格蓝牙输入）**:
录音的音频只能来自所选蓝牙耳机的麦克风，并有实际路由证据；任何情况下都没有手机麦克风回退。
_Avoid_: 蓝牙优先、麦克风自动回退、已连接即可

**Control Mode（控制模式）**:
用户显式开启的“耳机录音控制”；开启且未在录音时处于待命（ARMED）。待命不代表麦克风已开，也不保证系统会继续送来按键。
_Avoid_: 监听模式、常驻录音

**Capture Session（采集会话）**:
从一次有效 START 到封存结束的一次采集过程；路由已确认的产生且仅产生一个 Record，在路由确认前被取消的不产生。

**Route Evidence（路由证据）**:
系统观测到的实际输入来源记录，用来证明音频来自所选耳机。
_Avoid_: 已连接、已配对（它们都不是证据）

**Quarantine（隔离区）**:
路由未确认前音频帧的内存暂存处；其中的帧不落盘、不转录、不上传。

**Headset Profile（耳机档案）**:
某种耳机在某平台上的命令映射与已实测能力；每项能力各自带证据等级与状态，未知不算通过。

### 文本

**Record（记录）**:
一条逻辑条目，由一次路由已确认的 Capture Session 产生，其下挂着由不可变 Revision 组成的链。没录到人声的 Record 没有 Revision，标为“无语音”；音频被删除后，Record 与其文本仍然存在。
_Avoid_: 笔记、条目、转录结果

**Revision（修订）**:
Record 的一个不可变文本版本；ASR 转录与用户每次保存的修订各产生新的 Revision，已发布的 Revision 不可覆盖。转录只是 Revision 的一种来源，不是独立实体。同一 Record 有更新的 Revision 发布后，旧的称为被取代（Superseded），仍然保留、仍可读取。只有录下该 Record 的那台设备能为它产生 Revision。
_Avoid_: Transcript、TranscriptRevision、转录稿

### 投递

**Gateway（网关）**:
用户自托管的持久化服务：保存已发布的 Revision，并通过 MCP 把它们交给 Agent。
_Avoid_: 服务器、后端

**Agent（智能体）**:
通过 MCP 与网关交互的外部智能体（例如 Hermes），以自己的认证身份读取并确认文本。
_Avoid_: 客户端

**Destination（目标）**:
绑定一套专属 Agent 凭证的投递目标，身份由认证结果确定，并承载“自动发送”或“预览确认”的选择。
_Avoid_: target（不加限定时）、自报的 agent_id

**Delivery（投递）**:
针对某个 Revision 与某个 Destination 的独立投递单元，各自拥有租约与结果状态。
_Avoid_: 推送、消费记录

### 投递状态

**Waiting for Gateway（等待网关）**:
手机当前连不上网关（例如不在同一局域网），已发布的 Revision 在手机本地排队，连上后自动继续；不是失败，不需要用户处理。
_Avoid_: 发送失败、离线错误

**Stored（已入库）**:
网关已持久化某个 Revision；不代表任何 Agent 已看到它。

**Leased（已领取）**:
Agent 已取走某个 Delivery 并持有租约；只代表读到，不代表处理。

**Acknowledged（已确认）**:
Agent 已完成约定的确认步骤，只影响该 Destination 的 Delivery；网关能验证的是“确认”，验证不了“处理”。
_Avoid_: 已处理、已消费

**Notified（已通知）**:
网关已向某个 Destination 发出唤醒通知；不代表对方收到。

**Accepted（已接单）**:
Destination 的运行器已回应接下通知（例如 webhook 的 202）；不代表 Agent 已处理。

### 隔离

**User（用户）**:
使用系统的一个人；每个 User 拥有且仅拥有一个 Tenant。
_Avoid_: 账号

**Tenant（租户）**:
数据与权限的隔离边界，是一个 User 的私有空间；每个 Record、Destination、Delivery 属于且仅属于一个 Tenant。
_Avoid_: 工作区（该词在 PRD 中指一种 Destination）
