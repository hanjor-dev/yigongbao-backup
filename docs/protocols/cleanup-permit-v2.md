# COS 清理授权协议 v2

本地清理必须以冻结对象的精确版本为边界。线上评估和 permit 响应缺少任一摘要或版本身份时，本地服务失败关闭，不执行删除。

## 评估响应

`POST /internal/cleanup/v1/evaluate` 的 `data` 必须包含：

- `taskId`
- `snapshotHash`：候选快照摘要
- `objectSetHash`：冻结对象集合摘要
- `objects[]`：每项至少包含 `objectKey`、`versionIdentity`，可同时返回 `platform`、`bucket`、`sourceFileId`、`sharedReferenceStatus`、`versionBytes`

本地服务将上述对象键、版本身份和摘要原样固化，之后不允许扩大或替换集合。

## permit 响应

`POST /internal/cleanup/v1/tasks/{taskId}/permits` 的 `data` 必须包含：

- `permitId`、`taskId`、`status=ACTIVE`、`expiresAt`
- `objectSetHash`：必须与本地冻结任务完全一致
- `objects[]`：每项包含 `objectKey` 和 `versionIdentity`，集合必须与本地冻结集合完全一致且不得重复

本地申请 permit 时会提交真实的 `snapshotHash`、`objectSetHash`、fencing token 及按 `objectKey + versionIdentity` 计算的校验摘要。旧的 `objectKeys[]` 响应仅可解析用于兼容诊断；由于缺少版本身份，不具备生产删除资格。

## 删除约束

本地仅在数据库 permit 租约有效时逐项删除。COS 删除实现必须接受并校验 `versionIdentity`；HTTP 删除地址还必须在查询参数 `versionId` 或 `versionIdentity` 中绑定相同版本。未绑定版本、版本不一致、集合摘要不一致、permit 过期或租约丢失都会在发送删除请求前终止。
