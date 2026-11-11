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

/** 为需要明确完成反馈的存储卷动作提供用户可见文案。 */
export function storageVolumeActionSuccessMessage(action) {
  return action === 'set-standby' ? '存储卷已设为备用卷' : '';
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
