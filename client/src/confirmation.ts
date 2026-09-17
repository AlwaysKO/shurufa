import { onBeforeUnmount, readonly, shallowRef } from 'vue';

type ConfirmationOptions = { title?: string; confirmText?: string };
type Confirmation = { id: number; title: string; message: string; confirmText: string };
const current = shallowRef<Confirmation | null>(null);
export const confirmation = readonly(current);
let nextId = 0;
let contextVersion = 0;
let resolveCurrent: ((accepted: boolean) => void) | null = null;
let currentOwner: symbol | undefined;

export async function requestConfirmation(message: string, options: ConfirmationOptions = {}, owner?: symbol): Promise<boolean> {
  if (current.value) return false; // 不排队执行另一条删除。
  const version = contextVersion;
  const result = new Promise<boolean>(resolve => { resolveCurrent = resolve; });
  currentOwner = owner;
  current.value = { id: ++nextId, title: options.title ?? '确认删除', message, confirmText: options.confirmText ?? '确认删除' };
  return await result && version === contextVersion;
}

export function finishConfirmation(accepted: boolean) {
  const resolve = resolveCurrent;
  resolveCurrent = null;
  currentOwner = undefined;
  current.value = null;
  resolve?.(accepted);
}

/** 用户/路由/登录状态变化时，即使刚按下确认也不得继续使用旧上下文删除。 */
export function cancelConfirmation() {
  contextVersion += 1;
  finishConfirmation(false);
}

export function handleConfirmationKey(event: KeyboardEvent) {
  if (!current.value || (event.key !== 'Enter' && event.key !== 'Escape')) return;
  event.preventDefault();
  event.stopImmediatePropagation();
  if (event.isComposing || event.keyCode === 229 || event.repeat) return;
  finishConfirmation(event.key === 'Enter');
}

/** 组件内使用：卸载自动取消，防止异步弹窗确认后操作已离开的页面。 */
export function useConfirmation() {
  const owner = Symbol('confirmation-owner');
  let alive = true;
  let waiting = false;
  onBeforeUnmount(() => {
    alive = false;
    if (currentOwner === owner) cancelConfirmation();
  });
  return async (message: string, options?: ConfirmationOptions): Promise<boolean> => {
    if (!alive || waiting) return false;
    waiting = true;
    const version = contextVersion;
    try { return await requestConfirmation(message, options, owner) && alive && version === contextVersion; }
    finally { waiting = false; }
  };
}
