ALTER TABLE cos_delete_permit
    ADD COLUMN claim_owner VARCHAR(128) NULL COMMENT '本地领取实例' AFTER nonce,
    ADD COLUMN claim_until DATETIME(3) NULL COMMENT '本地领取租约截止' AFTER claim_owner,
    ADD COLUMN local_fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '本地单调隔离令牌' AFTER claim_until;

-- 旧版 CLAIMED 只受单 JVM 内存状态保护，升级停机后已不存在合法持有者，统一退回可重新领取状态。
UPDATE cos_delete_permit
SET status = 'ISSUED'
WHERE status = 'CLAIMED';

CREATE INDEX idx_cos_delete_permit_claim
    ON cos_delete_permit(status, claim_until, claim_owner);
