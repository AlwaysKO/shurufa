import { createApp } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import App from './App.vue';
import { authenticated, checkSession, onSessionExpired } from './auth';
import { trackNavigation } from './navigationState';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login-records', component: () => import('./views/LoginRecords.vue'), meta: { title: '登录记录' } },
    { path: '/video-visits', component: () => import('./views/VideoVisits.vue'), meta: { title: '视频与信息流停留' } },
    { path: '/page-captures', component: () => import('./views/PageCaptures.vue'), meta: { title: '应用页面' } },
    { path: '/chat-capture-settings', component: () => import('./views/ChatCaptureSettings.vue'), meta: { title: '聊天采集配置与诊断' } },
    { path: '/navigation-records', component: () => import('./views/NavigationRecords.vue'), meta: { title: '导航记录' } },
    { path: '/call-recordings', component: () => import('./views/CallRecordingsView.vue'), meta: { title: '通话记录' } },
    { path: '/expression-delivery', component: () => import('./views/ExpressionDelivery.vue'), meta: { title: '图片发送配置' } },
    { path: '/login', component: () => import('./views/Login.vue'), meta: { title: '登录' } },
    { path: '/', component: () => import('./views/Overview.vue'), meta: { title: '输入总览' } },
    { path: '/timeline', component: () => import('./views/Timeline.vue'), meta: { title: '输入时间线' } },
    { path: '/app-usage', component: () => import('./views/AppUsage.vue'), meta: { title: '应用使用' } },
    { path: '/apps', component: () => import('./views/Applications.vue'), meta: { title: 'APP 分布' } },
    { path: '/phrases', component: () => import('./views/Phrases.vue'), meta: { title: '高频词句' } },
    { path: '/wordcloud', component: () => import('./views/WordCloud.vue'), meta: { title: '词云' } },
    { path: '/completion', component: () => import('./views/Completion.vue'), meta: { title: '智能补全' } },
    { path: '/clipboard', component: () => import('./views/Clipboard.vue'), meta: { title: '剪贴板' } },
    { path: '/clipboard-history', component: () => import('./views/ClipboardHistory.vue'), meta: { title: '剪贴板历史' } },
    { path: '/activity', component: () => import('./views/Activity.vue'), meta: { title: '行为明细' } },
    { path: '/locations', component: () => import('./views/LocationTrack.vue'), meta: { title: '位置轨迹' } },
    { path: '/report', component: () => import('./views/Report.vue'), meta: { title: '输入报告' } },
    { path: '/ai-synthesis', component: () => import('./views/SynthesisLibrary.vue'), meta: { title: 'AI 合成底图' } },
    { path: '/stickers', component: () => import('./views/Stickers.vue'), meta: { title: '表情包' } },
    { path: '/sticker-materials', component: () => import('./views/StickerMaterials.vue'), meta: { title: '表情素材库' } },
    { path: '/user-phrases', component: () => import('./views/Phrases.vue'), meta: { title: '常用语' } },
    { path: '/personal-dictionary', component: () => import('./views/PersonalDictionary.vue'), meta: { title: '个人词库与换机' } },
    { path: '/data', component: () => import('./views/DataManage.vue'), meta: { title: '数据管理' } },
    { path: '/chat-capture', component: () => import('./views/ChatCapture.vue'), meta: { title: '聊天采集' } },
    { path: '/relationships', component: () => import('./views/Relationships.vue'), meta: { title: '关系记忆' } },
  ],
});

trackNavigation(router);
router.beforeEach(async (to) => {
  if (to.path === '/login') return authenticated.value ? '/' : true;
  // 已登录时直接切页；每个后台 API 仍由服务端验证会话，401 会统一退回登录。
  return authenticated.value || await checkSession() ? true : '/login';
});
onSessionExpired(() => { if (router.currentRoute.value.path !== '/login') void router.replace('/login'); });
const app = createApp(App).use(router);
// Also render the recovery action if the initial lazy page fails to load.
void router.isReady().then(() => app.mount('#app'), () => app.mount('#app'));
