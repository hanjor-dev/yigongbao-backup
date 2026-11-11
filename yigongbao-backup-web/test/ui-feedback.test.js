import test from 'node:test';
import assert from 'node:assert/strict';

async function loadFeedbackState() {
  const module = await import('../src/uiFeedbackState.js').catch(() => ({}));
  assert.equal(typeof module.createConfirmAction, 'function', 'UI feedback adapter should expose confirmation behavior');
  assert.equal(typeof module.createPromptAction, 'function', 'UI feedback adapter should expose prompt behavior');
  assert.equal(typeof module.createNoticePresenter, 'function', 'UI feedback adapter should expose notice behavior');
  return module;
}

test('confirmation maps framework confirmation and cancellation to booleans', async () => {
  const { createConfirmAction } = await loadFeedbackState();
  const confirmed = createConfirmAction(async () => 'confirm');
  const cancelled = createConfirmAction(async () => { throw new Error('cancel'); });

  assert.equal(await confirmed('确认执行？'), true);
  assert.equal(await cancelled('确认执行？'), false);
});

test('prompt returns the entered value and maps cancellation to null', async () => {
  const { createPromptAction } = await loadFeedbackState();
  const entered = createPromptAction(async () => ({ value: '管理员确认' }));
  const cancelled = createPromptAction(async () => { throw new Error('cancel'); });

  assert.equal(await entered('请输入原因'), '管理员确认');
  assert.equal(await cancelled('请输入原因'), null);
});

test('notice closes after five seconds without an older notice clearing the latest one', async () => {
  const { createNoticePresenter } = await loadFeedbackState();
  const opened = [];
  const cleared = [];
  const presenter = createNoticePresenter((options) => {
    const instance = { options, close: () => options.onClose() };
    opened.push(instance);
    return instance;
  });

  presenter.show('第一条消息', (message) => cleared.push(message));
  presenter.show('第二条消息', (message) => cleared.push(message));

  assert.equal(opened[0].options.duration, 5000);
  assert.equal(opened[0].options.showClose, true);
  assert.deepEqual(cleared, []);
  opened[1].close();
  assert.deepEqual(cleared, ['第二条消息']);
});
