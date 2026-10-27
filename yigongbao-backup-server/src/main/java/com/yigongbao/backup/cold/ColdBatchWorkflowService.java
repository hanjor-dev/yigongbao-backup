package com.yigongbao.backup.cold;

import lombok.RequiredArgsConstructor; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import java.time.LocalDateTime;

/** 冷备批次持久化状态流转；每次变化使用版本号 CAS，避免重复确认和跨批次清理。 */
@Service @RequiredArgsConstructor
public class ColdBatchWorkflowService {
 private final ColdBatchMapper batches;
 /** 将新建批次置为写入中。 */
 public ColdBatchEntity start(long id){return transition(id,"CREATED","WRITING",null);}
 /** 原子提交批次统计信息和完成状态，避免半完成批次被清理。 */
 @Transactional public ColdBatchEntity complete(long id,int orders,int artifacts,long bytes,String sha){ColdBatchEntity b=required(id);if(!"WRITING".equals(b.getStatus()))throw new IllegalStateException("非法批次状态迁移");b.setStatus("COMPLETED");b.setOrderCount(orders);b.setArtifactCount(artifacts);b.setTotalSize(bytes);b.setBatchManifestSha256(sha);b.setCompletedAt(LocalDateTime.now());if(batches.updateById(b)!=1)throw new IllegalStateException("批次已被其他管理员更新");return b;}
 /** 校验已完成批次并允许管理员进入热备清理阶段。 */
 public ColdBatchEntity verify(long id){ColdBatchEntity b=required(id);if(!"COMPLETED".equals(b.getStatus()))throw new IllegalStateException("批次未完成写入");b.setStatus("VERIFIED");b.setHotCleanupStatus("READY");if(batches.updateById(b)!=1)throw new IllegalStateException("批次已被其他管理员更新");return b;}
 /** 标记热备文件可清理，实际删除仍由热备清理服务执行。 */
 public ColdBatchEntity markHotCleaned(long id){ColdBatchEntity b=required(id);if(!"VERIFIED".equals(b.getStatus())||!"READY".equals(b.getHotCleanupStatus()))throw new IllegalStateException("批次未通过验证");b.setHotCleanupStatus("CLEANED");if(batches.updateById(b)!=1)throw new IllegalStateException("批次已被其他管理员更新");return b;}
 /** 按状态和版本号执行批次迁移。 */
 private ColdBatchEntity transition(long id,String from,String to,String ignored){ColdBatchEntity b=required(id);if(!from.equals(b.getStatus()))throw new IllegalStateException("非法批次状态迁移");b.setStatus(to);if(batches.updateById(b)!=1)throw new IllegalStateException("批次已被其他管理员更新");return b;}
 /** 读取批次并统一处理不存在错误。 */
 private ColdBatchEntity required(long id){ColdBatchEntity b=batches.selectById(id);if(b==null)throw new IllegalArgumentException("批次不存在");return b;}
}
