import { ref } from 'vue';
import type { Router } from 'vue-router';

export const navigationLoading = ref(false);
export const navigationFailure = ref<{ path: string } | null>(null);

export function trackNavigation(router: Router) {
  router.beforeEach(() => {
    navigationLoading.value = true;
    navigationFailure.value = null;
  });
  router.afterEach(() => { navigationLoading.value = false; });
  router.onError((_error, to) => {
    navigationLoading.value = false;
    navigationFailure.value = { path: to.fullPath };
  });
}

export function reloadFailedPage() {
  const path = navigationFailure.value?.path;
  if (path) window.location.assign(path);
}
