# 调研发现

## 既有需求结论
- 新冷备语义是“整盘封存 + 原盘重新挂载”，旧 `COLD_WRITE/HOT_CLEANUP/COLD_IMPORT` 页面与创建入口应退役。
- 既有需求明确要求封存确认/进度/失败明细、准备离线、确认挂载、故障切换等前端流程。
- 既有实施计划只提出在 `StorageView` 内增加对话框/进度组件，另要求旧 `ColdView` 退役；当前实际代码仍只有旧冷备批次组件，尚无新的 archive 组件文件。
- 冷备长任务必须独立表达封存尝试、校验条目与失败报告，不能在“订单备份”语义下展示。

## 待定位
- 后端是否已有全局封存 attempt 列表/分页接口，是否足够支撑独立冷备页面。

## 已确认根因
### MANIFEST_SHA256_MISMATCH
- 数据库 `bk_order_package.manifest_sha256` 来自线上清单接口字段 `manifestJcsSha256`，是线上业务清单的 JCS 摘要。
- 本地订单 ZIP 中的 `manifest.json` 是 `BackupManifest` 经 Jackson 再序列化后的本地传输对象；其原始字节不等于线上 JCS 规范字节。
- `VolumeArchiveVerifier` 当前直接对 ZIP 内 `manifest.json` 原始字节计算 SHA-256，再与线上 JCS 摘要比较，因此正常备份包也会稳定报 `MANIFEST_SHA256_MISMATCH`。
- 订单 ZIP 整体已有 `package_sha256` 校验，能保证 ZIP 及其 manifest 字节未被篡改；额外的 manifest 校验应解析 JSON 并核对其中声明的 `manifestSha256` 与数据库线上 JCS 摘要一致，而不是比较本地序列化字节摘要。

### 冷备任务错误跳到订单备份页
- `startStorageArchive()` 在创建 `VOLUME_ARCHIVE` 后明确执行 `route.navigate('backup')` 并调用通用 `openJob()`，这是错误跳转的直接原因。
- “订单备份”页面的数据源是 `/admin/backup/jobs`，语义只适合订单备份；封存 attempt 已有独立实体、条目、阶段与失败字段，继续复用订单任务详情会丢失冷备业务语义。

## 当前前端与 API 缺口
- `StorageView` 已内嵌封存对话框、卷详情中的封存历史和封存文件明细，但没有独立冷备管理页和导航入口。
- 旧 `ColdView`/旧批次导入逻辑仍残留在 composable，当前主导航虽未展示它，但不能作为新整盘冷备页面复用。
- 后端目前只有“单卷封存历史”和“单次封存详情”接口，缺少适合独立页面的全局 attempt 列表/筛选接口。
- 冷备重新接入目前在存储卷页用 `prompt` 手输挂载路径；已有扫描候选卷能力，但没有把候选介质身份匹配结果形成专用接入流程，容易选错路径且体验不完整。

## 建议的信息架构（待用户确认）
- 新增一级导航“冷备管理”，不再用旧 `ColdView` 的批次压缩/导入语义。
- 页面分为三个业务区域：概览、封存任务、冷备介质。
- “封存任务”独立展示 archive attempt，而不是订单备份 job：源卷/接替卷、attempt 编号、阶段、校验进度、订单周期、订单/文件/容量、备注、开始/完成时间、失败代码与原因；详情展示逐文件校验结果和任务日志；失败时支持重新封存或放弃。
- “冷备介质”展示 COLD 卷：介质身份、别名、标签备注、存放位置、在线状态、容量、订单周期、订单/文件/容量统计；支持准备离线和重新接入。
- 重新接入应称“冷备盘接入/确认挂载”，而非旧方案的“冷备导入”；先扫描挂载候选，再选择候选并由后端验证 marker/物理指纹，禁止自由文本作为首选交互。
- `StorageView` 保留存储卷基础管理和“发起封存”入口；封存创建成功后跳到“冷备管理”并聚焦本次 attempt。

## 最终审查结论
- 校验器必须拒绝 Windows junction 等所有 reparse point；仅检查 `Files.isSymbolicLink` 不足以阻止受管路径逃逸。实现使用 `NOFOLLOW_LINKS` 的基础属性逐级拒绝 `isSymbolicLink/isOther`，并以真实 junction 回归测试覆盖。
- 全局封存历史属于审计数据，不能静默截断为 200 条；实现改为返回全部记录，同时将逐 attempt 计数改成一次批量查询后分组统计。
- `PREPARE_FAILED` 与 `VERIFY_FAILED/COMMIT_FAILED/CANCELLED` 同属需要管理员处理的失败终态，概览计数、重新封存入口与详情放弃操作均已统一覆盖。
