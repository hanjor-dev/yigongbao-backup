const normalized = value => String(value || '').toUpperCase();

/** 仅允许在线热备候选或已重新接入且空闲的只读冷备卷进入受控激活流程。 */
export function canSetAsActiveVolume(volume) {
  if (!volume || normalized(volume.mountStatus) !== 'ONLINE') return false;
  if (normalized(volume.adminStatus || 'ENABLED') !== 'ENABLED') return false;
  if (normalized(volume.operationStatus || 'IDLE') !== 'IDLE') return false;
  if (normalized(volume.tier) === 'HOT') return normalized(volume.writeStatus) !== 'ACTIVE';
  return normalized(volume.tier) === 'COLD' && normalized(volume.writeStatus) === 'READ_ONLY';
}

/** 仅为尚未取得写权限的在线、空闲热备卷提供“设为备用”操作。 */
export function canSetAsStandbyVolume(volume) {
  if (!volume || normalized(volume.tier) !== 'HOT') return false;
  if (normalized(volume.mountStatus) !== 'ONLINE') return false;
  if (normalized(volume.adminStatus || 'ENABLED') !== 'ENABLED') return false;
  if (normalized(volume.operationStatus || 'IDLE') !== 'IDLE') return false;
  return ['READ_ONLY', 'DISABLED'].includes(normalized(volume.writeStatus));
}

/** 仅允许空闲、在线且未停用的 HOT/STANDBY 卷显式取消备用。 */
export function canCancelStandbyVolume(volume) {
  if (!volume || normalized(volume.tier) !== 'HOT') return false;
  if (normalized(volume.mountStatus) !== 'ONLINE') return false;
  if (normalized(volume.adminStatus || 'ENABLED') !== 'ENABLED') return false;
  if (normalized(volume.operationStatus || 'IDLE') !== 'IDLE') return false;
  return normalized(volume.writeStatus) === 'STANDBY';
}

/** 整盘封存只对在线活动卷或只读卷开放，备用卷必须先显式取消备用。 */
export function canArchiveVolume(volume) {
  if (!volume || normalized(volume.tier) !== 'HOT' || normalized(volume.mountStatus) !== 'ONLINE') return false;
  const writeStatus = normalized(volume.writeStatus);
  const operationStatus = normalized(volume.operationStatus || 'IDLE');
  if (writeStatus === 'ACTIVE') return operationStatus === 'IDLE';
  return writeStatus === 'READ_ONLY' && ['IDLE', 'REPAIR_REQUIRED'].includes(operationStatus);
}

/** 前端仅暴露基础可修复状态，所有文件与任务安全条件仍由后端最终判断。 */
export function canRepairAbnormalState(volume) {
  if (!volume || normalized(volume.tier) !== 'HOT') return false;
  if (normalized(volume.mountStatus) !== 'ONLINE') return false;
  if (normalized(volume.writeStatus) !== 'READ_ONLY') return false;
  if (normalized(volume.adminStatus || 'ENABLED') !== 'ENABLED') return false;
  return normalized(volume.operationStatus) === 'REPAIR_REQUIRED';
}

/** 为需要明确完成反馈的存储卷动作提供用户可见文案。 */
export function storageVolumeActionSuccessMessage(action) {
  if (action === 'set-standby') return '存储卷已设为备用卷';
  if (action === 'cancel-standby') return '已取消备用，存储卷现为只读';
  if (action === 'repair-abnormal-state') return '异常状态已修复，存储卷已恢复为只读空闲状态';
  return '';
}

/**
 * 封存活动卷时选择在线备用卷接替；封存非活动卷时选择当前在线活动卷作为保障卷。
 * 后端仍会执行最终状态校验，此处只负责为封存预检补齐明确的目标卷参数。
 */
export function archiveGuaranteeVolumeId(source, volumes) {
  const expectedWriteStatus = normalized(source?.writeStatus) === 'ACTIVE' ? 'STANDBY' : 'ACTIVE';
  const sourceId = source?.id == null ? null : String(source.id);
  const candidate = (Array.isArray(volumes) ? volumes : []).find((volume) => (
    volume?.id != null
    && String(volume.id) !== sourceId
    && normalized(volume.tier) === 'HOT'
    && normalized(volume.mountStatus) === 'ONLINE'
    && normalized(volume.writeStatus) === expectedWriteStatus
  ));
  return candidate?.id ?? null;
}

/** 明确说明冷备盘复用不会清理历史文件，并展示新旧活动卷的状态变化。 */
export function activeVolumeConfirmation(target, currentActive) {
  const targetName = target?.displayName || target?.volumeCode || '所选存储卷';
  const currentName = currentActive?.displayName || currentActive?.volumeCode || '当前热备活动卷';
  if (normalized(target?.tier) !== 'COLD') {
    return `确认切换活动热备卷至“${targetName}”？当前热备活动卷“${currentName}”将调整为热备只读。`;
  }
  return `确认将“${targetName}”转为热备活动卷？\n\n当前热备活动卷“${currentName}”将调整为热备只读；“${targetName}”将调整为热备活动卷，后续新备份任务写入该卷。\n\n盘内已有历史冷备文件不会被移动或删除，相关订单和任务的当前存储卷状态将同步更新。`;
}

/** 将服务端按分层阈值计算的容量状态转换为紧凑、稳定的列表展示。 */
export function storageCapacityPresentation(volume) {
  const usedPercent = Number(volume?.usedPercent);
  if (volume?.usedPercent == null || !Number.isFinite(usedPercent)) {
    return { percent: '不可用', status: '容量未知', tone: 'info' };
  }
  return {
    percent: `${usedPercent.toFixed(1)}%`,
    status: volume?.capacityWarning === true ? '容量告警' : '容量正常',
    tone: volume?.capacityWarning === true ? 'danger' : 'success',
  };
}

/** 活动卷提示必须同时体现挂载健康，避免把离线 ACTIVE 台账显示为绿色可用。 */
export function activeVolumePresentation(volume) {
  if (!volume) {
    return {
      type: 'warning',
      message: '尚未配置活动热备卷；新的备份与补传任务会被安全拒绝。',
    };
  }
  const name = volume.displayName || volume.volumeCode || '活动热备卷';
  if (normalized(volume.mountStatus) !== 'ONLINE') {
    const reason = volume.lastErrorMessage || '存储卷当前不在线';
    return {
      type: 'error',
      message: `活动热备卷当前不可用：${name}（${reason}），新的备份与补传任务会被安全拒绝。`,
    };
  }
  return {
    type: 'success',
    message: `当前活动热备卷：${name}（${volume.volumeCode || '未编号'}）`,
  };
}
