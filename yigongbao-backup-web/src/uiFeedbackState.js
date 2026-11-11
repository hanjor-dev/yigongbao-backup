export function createConfirmAction(openConfirm) {
  return async function confirmAction(message, options = {}) {
    const { title = '操作确认', ...dialogOptions } = options;
    try {
      await openConfirm(message, title, {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        type: 'warning',
        autofocus: false,
        ...dialogOptions,
      });
      return true;
    } catch {
      return false;
    }
  };
}

export function createPromptAction(openPrompt) {
  return async function promptAction(message, options = {}) {
    const { title = '请输入', ...dialogOptions } = options;
    try {
      const result = await openPrompt(message, title, {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        autofocus: true,
        ...dialogOptions,
      });
      return result?.value ?? '';
    } catch {
      return null;
    }
  };
}

export function createNoticePresenter(openMessage) {
  let active = null;
  let generation = 0;

  return {
    show(message, onDismiss = () => {}) {
      const currentGeneration = ++generation;
      active?.close();
      active = openMessage({
        message,
        type: 'warning',
        duration: 5000,
        showClose: true,
        grouping: true,
        offset: 24,
        onClose: () => {
          if (currentGeneration !== generation) return;
          active = null;
          onDismiss(message);
        },
      });
    },
    dispose() {
      generation += 1;
      active?.close();
      active = null;
    },
  };
}
