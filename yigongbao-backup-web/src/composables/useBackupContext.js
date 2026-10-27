import { inject } from 'vue';

const BACKUP_CONTEXT = 'backupConsole';

/**
 * 读取应用级备份控制台上下文，页面组件不直接创建第二份业务状态。
 */
export function useBackupContext() {
  const context = inject(BACKUP_CONTEXT);
  if (!context) throw new Error('Backup console context is not available');
  return context;
}

export { BACKUP_CONTEXT };

