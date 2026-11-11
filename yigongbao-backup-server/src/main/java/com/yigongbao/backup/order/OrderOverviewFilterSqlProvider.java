package com.yigongbao.backup.order;

import com.yigongbao.backup.storage.volume.*;

/** 首页订单下钻筛选；共享策略谓词在 COUNT 与 LIMIT 前执行。 */
public final class OrderOverviewFilterSqlProvider {
    private OrderOverviewFilterSqlProvider(){}
    public static String selectIds(){return base(false);}
    public static String count(){return "SELECT COUNT(*) FROM ("+base(true)+") overview_orders";}
    private static String base(boolean count){
        StorageVolumeAccessPolicy p=new StorageVolumeAccessPolicy();
        String readable=p.readableSql(new StorageVolumeAccessSqlPredicate.SqlAliases("v","a")).sql();
        String offline=p.expectedOfflineSql(new StorageVolumeAccessSqlPredicate.SqlAliases("v","a")).sql();
        String copy=" FROM bk_order_package p JOIN bk_artifact_copy c ON c.artifact_type='ORDER_PACKAGE' AND c.artifact_id=p.id AND c.lifecycle='ACTIVE' AND c.is_deleted=0 LEFT JOIN bk_storage_volume v ON v.id=c.storage_volume_id LEFT JOIN bk_volume_archive a ON a.id=v.operation_ref_id WHERE p.snapshot_id=s.id AND p.is_deleted=0 AND p.status='AVAILABLE' AND p.backup_mode=s.backup_mode";
        String healthy="c.state IN ('PRESENT','VERIFIED')";
        String readableCopy=copy+" AND "+healthy+" AND "+readable;
        String abnormalCopy=copy+" AND (c.state NOT IN ('PRESENT','VERIFIED') OR c.storage_volume_id IS NULL OR v.id IS NULL OR NOT ("+offline+"))";
        StringBuilder sql=new StringBuilder("SELECT s.id FROM bk_order_snapshot s WHERE s.is_deleted=0");
        sql.append(" AND (#{backupMode} IS NULL OR s.backup_mode=#{backupMode})");
        sql.append(" AND (#{storageIssue} IS NULL OR (s.backup_mode='REAL' AND s.snapshot_status='AVAILABLE'))");
        sql.append(" AND (#{storageAccessible} IS NULL OR (#{storageAccessible}=TRUE AND EXISTS(SELECT 1").append(readableCopy).append(")) OR (#{storageAccessible}=FALSE AND NOT EXISTS(SELECT 1").append(readableCopy).append(")))");
        sql.append(" AND (#{storageIssue} IS NULL OR (#{storageIssue}='UNREADABLE' AND NOT EXISTS(SELECT 1").append(readableCopy).append(") AND (NOT EXISTS(SELECT 1").append(copy).append(") OR EXISTS(SELECT 1").append(abnormalCopy).append("))))");
        if(!count)sql.append(" ORDER BY s.order_created_at DESC,s.id DESC");
        if(!count)sql.append(" LIMIT #{limit} OFFSET #{offset}");
        return sql.toString();
    }
}
