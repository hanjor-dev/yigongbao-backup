# Windows订单备份模拟测试

该流程只模拟线上候选、订单清单、COS文件下载、清单确认和备份回执；本地备份系统仍使用实际MySQL和Windows目录。

1. 执行最新完整初始化脚本，已有数据库则追加执行 `2026-11-02-15-automatic-backup-schedule.sql`。
2. 在系统配置页面设置：
   - `online.enabled=true`
   - `online.base-url=http://127.0.0.1:18081`
   - `backup.schedule.enabled=true`
   - `backup.schedule.interval-days=1`
   - `backup.schedule.start-time=<当前时间后1分钟>`
   - `backup.collection.days-before=7`（按自然日备份7天前及更早创建的正式订单）
   - 已在“存储卷管理”中纳管一个 ONLINE、ACTIVE 的 HOT 卷
   - `storage.managed-roots=D:/yigongbao-backup/data,D:/yigongbao-backup/cold-disks,D:/yigongbao-backup/cold-import`
3. 本地与模拟服务仍需配置相同的 `BACKUP_SHARED_HMAC_SECRET`；模拟服务不会校验签名，但本地HTTP客户端要求该值存在。
4. 在本地备份后端 `.env` 设置 `BACKUP_DEV_CANDIDATE_LIMIT=5`（仅开发联调，生产必须删除或设为0）；线上候选接口仍使用内部技术分页，但不会限制正式环境单个任务的订单总数。然后启动模拟服务：`node tools/mock-online-backup-server.mjs`。
5. 启动本地备份后端和前端，以管理员登录后进入“订单备份”。
6. 到达配置的开始时间后，在“订单备份”页查看系统自动创建的单个5订单备份任务及其明细。
7. 在“订单文件”验证5个订单、每个订单1个关联资产，并分别下载订单ZIP。
8. 检查 `D:/yigongbao-backup/data/orders/<备份日期YYYYMMDD>/<订单号>-<订单ID>.zip`；ZIP必须包含 `manifest.json` 和 `files/`。实际目录日期以备份任务日期为准，订单号中的非法路径字符会被替换为下划线。

错过当天开始时间不会补跑，请将开始时间调整到下一分钟再测试。
