---
status: accepted
---
# ADR-0020：只有录下 Record 的那台设备能为它写 Revision 或删除它

一个 User 可以把多台手机配对到同一个 Gateway。V1 规定：Record 归录下它的那台设备所有，只有这台设备能为它提交新的 Revision 或删除它；其他设备的提交被 Gateway 以 `RECORD_NOT_ORIGIN_DEVICE` 拒绝。因此 Revision 号可以是该设备本地从 1 递增的整数，Gateway 不需要裁定先后，也接受不连续的版本号（保存了但没发布的版本不会到达 Gateway）。

## Considered Options

- **任何已配对设备都能写同一个 Record**：否决。两台手机可能各自产生“第 2 版”，Gateway 必须裁定谁先谁后，手机之间还要先同步对方的版本才能编辑；V1 没有“手机从 Gateway 读回别的设备的 Record”这条链路，Recording 也只存在于原设备上。
- **版本号由 Gateway 分配**：否决。手机离开局域网时无法取号（ADR-0018），离线保存修订就做不了。

## Consequences

- Gateway 在首次入库时记下 Record 的来源设备，之后每次提交和删除都核对设备身份；设备身份来自设备令牌，不信任请求体自报。
- 设备被吊销或手机丢失后，它名下的 Record 在 Gateway 上仍可被 Agent 读取，但没有任何设备能再修订或删除它们；删除只能通过网关命令行。这个管理入口要在 DEV-015 里提供。
- 换新手机不会继承旧手机的 Record 写权限。若日后要做设备迁移或多设备编辑，需要改 Revision 的编号方式（例如带设备标识或由 Gateway 定序），属于合同的不兼容变更，另写 ADR。
- 同一个 `record_id + revision` 重复提交的幂等规则不变：只比较 `text_sha256`，相同返回首次入库的结果，不同返回 `RECORD_VERSION_CONFLICT`。
