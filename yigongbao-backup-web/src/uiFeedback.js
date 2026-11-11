import { ElMessage, ElMessageBox } from 'element-plus';
import { createConfirmAction, createNoticePresenter, createPromptAction } from './uiFeedbackState.js';

export const confirmAction = createConfirmAction((message, title, options) => ElMessageBox.confirm(message, title, options));
export const promptAction = createPromptAction((message, title, options) => ElMessageBox.prompt(message, title, options));
export const globalNoticePresenter = createNoticePresenter((options) => ElMessage(options));

export function showWarningMessage(message) {
  return ElMessage({ message, type: 'warning', duration: 5000, showClose: true, offset: 24 });
}
