<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { cancelConfirmation, confirmation, finishConfirmation, handleConfirmationKey } from '../confirmation';

const dialog = ref<HTMLElement | null>(null);
const cancelButton = ref<HTMLButtonElement | null>(null);

function onKeydown(event: KeyboardEvent) {
  handleConfirmationKey(event);
  if (!confirmation.value || event.key !== 'Tab') return;
  const buttons = dialog.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)');
  if (!buttons?.length) return;
  const first = buttons[0];
  const last = buttons[buttons.length - 1];
  if (event.shiftKey && (document.activeElement === first || !dialog.value?.contains(document.activeElement))) {
    event.preventDefault(); last.focus();
  } else if (!event.shiftKey && (document.activeElement === last || !dialog.value?.contains(document.activeElement))) {
    event.preventDefault(); first.focus();
  }
}

watch(() => confirmation.value?.id, async (id, _previous, onCleanup) => {
  if (!id) return;
  const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const previousOverflow = document.body.style.overflow;
  document.body.style.overflow = 'hidden';
  document.addEventListener('keydown', onKeydown, true);
  onCleanup(() => {
    document.removeEventListener('keydown', onKeydown, true);
    document.body.style.overflow = previousOverflow;
    void nextTick(() => { if (!confirmation.value && previousFocus?.isConnected) previousFocus.focus(); });
  });
  await nextTick();
  if (confirmation.value?.id === id) cancelButton.value?.focus();
});
onBeforeUnmount(cancelConfirmation);
</script>

<template>
  <Teleport to="body">
    <div v-if="confirmation" class="confirmation-mask" data-testid="confirmation-mask" @click.self="finishConfirmation(false)">
      <section ref="dialog" class="confirmation-dialog" role="alertdialog" aria-modal="true" aria-labelledby="confirmation-title" aria-describedby="confirmation-message" data-testid="confirmation-dialog">
        <header class="confirmation-header">
          <span class="confirmation-icon" aria-hidden="true">!</span>
          <h2 id="confirmation-title">{{ confirmation.title }}</h2>
          <button type="button" class="confirmation-close" aria-label="取消并关闭弹窗" @click="finishConfirmation(false)">×</button>
        </header>
        <p id="confirmation-message" class="confirmation-message">{{ confirmation.message }}</p>
        <footer class="confirmation-footer">
          <span class="confirmation-shortcuts"><kbd>Enter</kbd> 确认 · <kbd>Esc</kbd> 取消</span>
          <div class="confirmation-actions">
            <button ref="cancelButton" type="button" class="confirmation-cancel" data-testid="confirmation-cancel" @click="finishConfirmation(false)">取消</button>
            <button type="button" class="confirmation-accept" data-testid="confirmation-accept" @click="finishConfirmation(true)">{{ confirmation.confirmText }}</button>
          </div>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.confirmation-mask { position: fixed; inset: 0; z-index: 2000; display: flex; align-items: center; justify-content: center; padding: 20px; background: rgba(18, 27, 44, .5); backdrop-filter: blur(3px); }
.confirmation-dialog { width: min(500px, 100%); max-height: calc(100dvh - 40px); display: flex; flex-direction: column; overflow: hidden; border: 1px solid rgba(255,255,255,.7); border-radius: 16px; background: #fff; color: #2f3542; box-shadow: 0 24px 80px rgba(16, 24, 40, .25); }
.confirmation-header { display: flex; align-items: center; gap: 12px; padding: 22px 24px 14px; }
.confirmation-icon { width: 36px; height: 36px; flex-shrink: 0; display: grid; place-items: center; border-radius: 50%; background: #fff0ef; color: #d3423b; font-size: 22px; font-weight: 700; }
.confirmation-header h2 { flex: 1; min-width: 0; margin: 0; font-size: 18px; overflow-wrap: anywhere; }
.confirmation-close { width: 30px; height: 30px; padding: 0; border: 0; border-radius: 6px; background: transparent; color: #929aaa; font-size: 26px; cursor: pointer; }
.confirmation-close:hover { background: #f3f4f7; color: #344054; }
.confirmation-message { margin: 0; padding: 0 24px 24px; overflow-y: auto; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 14px; line-height: 1.75; color: #596273; }
.confirmation-footer { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 14px; padding: 16px 24px; border-top: 1px solid #edf0f4; background: #fafbfc; }
.confirmation-shortcuts { color: #8992a2; font-size: 12px; }
.confirmation-shortcuts kbd { padding: 2px 4px; border: 1px solid #e0e4ea; border-radius: 4px; background: #fff; font-family: inherit; }
.confirmation-actions { display: flex; gap: 10px; margin-left: auto; }
.confirmation-actions button { min-width: 82px; padding: 9px 16px; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; }
.confirmation-cancel { border: 1px solid #dce1e8; background: #fff; color: #576071; }
.confirmation-cancel:hover { background: #f1f3f6; }
.confirmation-accept { border: 1px solid #d3423b; background: #d3423b; color: #fff; }
.confirmation-accept:hover { background: #bd342e; }
button:focus-visible { outline: 3px solid #a9b5ff; outline-offset: 3px; }
@media (max-width: 480px) { .confirmation-header { padding: 18px 18px 12px; } .confirmation-message { padding: 0 18px 18px; } .confirmation-footer { padding: 14px 18px; } }
</style>
