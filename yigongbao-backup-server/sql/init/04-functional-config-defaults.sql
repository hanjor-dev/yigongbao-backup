-- 备份子系统业务配置最终基线。
SET time_zone = '+08:00';
-- 同一个业务配置只存在于 bk_config；仅在配置键不存在时插入，不覆盖现场值。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

INSERT INTO bk_config
    (config_key, config_name, category_code, category_name, scope, value_type,
     config_value, options_json, description, secret_ref, config_version,
     changed_at, create_time, update_time)
SELECT v.config_key, v.config_name, v.category_code, v.category_name, 'GLOBAL', v.value_type,
       v.config_value, v.options_json, v.description, v.secret_ref, 1,
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM (
    SELECT 'backup.schedule.enabled' config_key, '自动备份开关' config_name, 'BACKUP_COLLECTION' category_code, '备份采集策略' category_name, 'BOOLEAN' value_type, 'true' config_value, '[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]' options_json, '控制是否自动发现候选订单并创建热备任务。' description, NULL secret_ref
    UNION ALL SELECT 'backup.schedule.interval-days','自动备份周期（天）','BACKUP_COLLECTION','备份采集策略','INTEGER','1',NULL,'自动备份任务执行周期，最小1天。',NULL
    UNION ALL SELECT 'backup.schedule.start-time','自动备份开始时间','BACKUP_COLLECTION','备份采集策略','STRING','05:00',NULL,'按服务器本地时区执行，格式HH:mm；服务停机错过后当天不补跑。',NULL
    UNION ALL SELECT 'backup.collection.days-before','备份X天前的订单','BACKUP_COLLECTION','备份采集策略','INTEGER','7',NULL,'按订单创建日期和服务器本地自然日计算；配置7表示执行日往前第7天及更早创建的正式订单进入备份范围。',NULL
    UNION ALL SELECT 'backup.schedule.max-orders-per-run','单个备份任务最大订单数','BACKUP_COLLECTION','备份采集策略','INTEGER','100',NULL,'自动备份或管理员立即扫描创建的单个任务最多包含的订单数，范围1至1000；超出部分留待下一轮扫描。',NULL
    UNION ALL SELECT 'backup.retry.max-retries','订单最大重试次数','BACKUP_COLLECTION','备份采集策略','INTEGER','3',NULL,'单个订单首次失败后在当前备份任务中的最大重试次数。',NULL
    UNION ALL SELECT 'backup.retry.delays-seconds','订单重试等待秒数','BACKUP_COLLECTION','备份采集策略','STRING','60,300,900',NULL,'订单失败后各次重试前的等待秒数，逗号分隔。',NULL
    UNION ALL SELECT 'backup.schedule.reconcile-enabled','候选对账开关','BACKUP_COLLECTION','备份采集策略','BOOLEAN','true','[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]','控制是否定期对账候选订单；自动备份总开关关闭时本开关也不执行。',NULL
    UNION ALL SELECT 'backup.schedule.reconcile-interval-hours','候选对账周期（小时）','BACKUP_COLLECTION','备份采集策略','INTEGER','24',NULL,'漏单、取消和删除事件的对账周期。',NULL
    UNION ALL SELECT 'storage.backup-reservation-percent','备份工作空间预留比例（%）','STORAGE_MANAGEMENT','存储与容量','INTEGER','200',NULL,'按源文件总量预留下载原文件、压缩包和原子发布临时副本的峰值空间，范围100至500。',NULL
    UNION ALL SELECT 'storage.threshold.hot.percent','热备容量告警阈值（%）','STORAGE_MANAGEMENT','存储与容量','INTEGER','75',NULL,'热备或NAS使用率达到该值时告警。',NULL
    UNION ALL SELECT 'storage.threshold.cold.percent','冷备容量告警阈值（%）','STORAGE_MANAGEMENT','存储与容量','INTEGER','80',NULL,'冷备目标盘使用率达到该值时告警。',NULL
    UNION ALL SELECT 'storage.volume-switch.enabled','存储卷自动切换开关','STORAGE_MANAGEMENT','存储与容量','BOOLEAN','false','[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]','仅从已纳管 HOT STANDBY 卷中自动切换。',NULL
    UNION ALL SELECT 'storage.volume-switch.threshold-percent','存储卷切换阈值（%）','STORAGE_MANAGEMENT','存储与容量','INTEGER','90',NULL,'当前 ACTIVE 卷使用率达到该值时建议切换。',NULL
    UNION ALL SELECT 'storage.volume-health.interval-minutes','存储卷健康检查周期（分钟）','STORAGE_MANAGEMENT','存储与容量','INTEGER','5',NULL,'定期探测已纳管卷的可访问性和容量。',NULL
    UNION ALL SELECT 'cold.max-import-bytes','最大导入解压量（字节）','COLD_ARCHIVE','冷备归档与导入','LONG','4398046511104',NULL,'单个冷备导入任务允许解压的总量，默认4TiB。',NULL
    UNION ALL SELECT 'restore.enabled','线上恢复开关','ONLINE_RESTORE','线上恢复策略','BOOLEAN','true','[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]','控制管理员能否创建线上恢复任务。',NULL
    UNION ALL SELECT 'restore.conflict-policy','恢复冲突策略','ONLINE_RESTORE','线上恢复策略','ENUM','REJECT','[{"label":"拒绝覆盖（推荐）","value":"REJECT"},{"label":"管理员确认后覆盖","value":"CONFIRM_OVERWRITE"},{"label":"直接强制覆盖","value":"FORCE_OVERWRITE"}]','线上订单文件或关联关系已变化时的处理方式。默认拒绝覆盖；强制覆盖可能回填历史文件记录，需由管理员审慎使用。',NULL
    UNION ALL SELECT 'restore.retry-count','恢复失败重试次数','ONLINE_RESTORE','线上恢复策略','INTEGER','3',NULL,'单订单恢复失败后的自动重试次数，范围0至10。',NULL
    UNION ALL SELECT 'cleanup.schedule.enabled','COS自动清理开关','CLOUD_CLEANUP','云端清理策略','BOOLEAN','false','[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]','启用后按周期自动创建并执行COS清理任务。',NULL
    UNION ALL SELECT 'cleanup.schedule.interval-hours','COS自动清理周期（小时）','CLOUD_CLEANUP','云端清理策略','INTEGER','24',NULL,'自动扫描及执行COS清理的周期。',NULL
    UNION ALL SELECT 'cleanup.retention-days','COS保留天数','CLOUD_CLEANUP','云端清理策略','INTEGER','180',NULL,'按订单创建时间计算允许清理范围。',NULL
    UNION ALL SELECT 'cleanup.batch-size','单批清理订单上限','CLOUD_CLEANUP','云端清理策略','INTEGER','100',NULL,'单个清理任务最多处理的订单数量，范围1至500。',NULL
    UNION ALL SELECT 'supplement.allowed-extensions','允许补传扩展名','SUPPLEMENT_FILE','补传文件规则','STRING_LIST','zip,step,stl,dcm',NULL,'允许补传到本地的工程文件扩展名。',NULL
    UNION ALL SELECT 'supplement.max-file-size-bytes','补传单文件大小上限（字节）','SUPPLEMENT_FILE','补传文件规则','LONG','1073741824',NULL,'业务层单个补传文件大小上限，不得超过HTTP硬上限。',NULL
    UNION ALL SELECT 'account.sync-enabled','账号同步开关','ACCOUNT_SYNC','账号同步策略','BOOLEAN','true','[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]','控制是否从线上定时同步账号资料。',NULL
    UNION ALL SELECT 'account.sync-delay-minutes','账号同步周期（分钟）','ACCOUNT_SYNC','账号同步策略','INTEGER','30',NULL,'线上账号同步间隔。',NULL
    UNION ALL SELECT 'account.sync-page-size','账号同步分页大小','ACCOUNT_SYNC','账号同步策略','INTEGER','200',NULL,'单次读取线上账号数量，范围1至500。',NULL
    UNION ALL SELECT 'online.enabled','线上通信总开关','ONLINE_INTEGRATION','线上系统连接','BOOLEAN','true','[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]','控制候选、账号、恢复和清理等所有线上通信。',NULL
    UNION ALL SELECT 'online.base-url','线上系统地址','ONLINE_INTEGRATION','线上系统连接','URL','http://localhost:8081/api',NULL,'线上医工宝内部接口HTTP基础地址，必须包含服务 context-path，例如 http://localhost:8081/api；启用时必填。',NULL
    UNION ALL SELECT 'online.connect-timeout-seconds','线上连接超时（秒）','ONLINE_INTEGRATION','线上系统连接','INTEGER','10',NULL,'HTTP建连超时，范围1至300秒。',NULL
    UNION ALL SELECT 'online.request-timeout-seconds','线上请求超时（秒）','ONLINE_INTEGRATION','线上系统连接','INTEGER','60',NULL,'普通线上接口请求超时，范围1至3600秒。',NULL
    UNION ALL SELECT 'online.file-transfer-timeout-seconds','线上文件传输超时（秒）','ONLINE_INTEGRATION','线上系统连接','INTEGER','7200',NULL,'备份下载和恢复上传等大文件流式传输超时，范围60至86400秒。',NULL
    UNION ALL SELECT 'storage.read-lease.ttl-seconds','存储读取租约时长（秒）','STORAGE','存储管理','INTEGER','600',NULL,'下载和恢复读取卷时的租约时长，必须大于两倍心跳间隔。',NULL
    UNION ALL SELECT 'storage.read-lease.heartbeat-seconds','存储读取租约心跳（秒）','STORAGE','存储管理','INTEGER','30',NULL,'长文件下载期间续租间隔。',NULL
    UNION ALL SELECT 'job.dispatch-interval-ms','任务派发间隔（毫秒）','JOB_EXECUTION','任务执行策略','INTEGER','1000',NULL,'扫描并领取待执行持久化任务的间隔，最小200毫秒。',NULL
    UNION ALL SELECT 'job.lease-seconds','任务租约时长（秒）','JOB_EXECUTION','任务执行策略','INTEGER','60',NULL,'执行器持有任务的租约时长，最小10秒。',NULL
    UNION ALL SELECT 'job.recovery-interval-ms','租约回收间隔（毫秒）','JOB_EXECUTION','任务执行策略','INTEGER','60000',NULL,'回收异常中断过期租约的间隔，最小1000毫秒。',NULL
    UNION ALL SELECT 'job.max-retry-count','任务最大重试次数','JOB_EXECUTION','任务执行策略','INTEGER','3',NULL,'非恢复专属任务的最大自动重试次数，范围0至10。',NULL
) v
WHERE NOT EXISTS (
    SELECT 1 FROM bk_config c
    WHERE c.config_key = v.config_key AND c.scope = 'GLOBAL' AND c.is_deleted = 0
);
