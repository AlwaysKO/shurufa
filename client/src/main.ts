import { createApp } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import App from './App.vue';
import Overview from './views/Overview.vue';
import Timeline from './views/Timeline.vue';
import Applications from './views/Applications.vue';
import Phrases from './views/Phrases.vue';
import Completion from './views/Completion.vue';
import Clipboard from './views/Clipboard.vue';
import Activity from './views/Activity.vue';
import LocationTrack from './views/LocationTrack.vue';
import Report from './views/Report.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: Overview, meta: { title: '输入总览' } },
    { path: '/timeline', component: Timeline, meta: { title: '输入时间线' } },
    { path: '/apps', component: Applications, meta: { title: 'APP 分布' } },
    { path: '/phrases', component: Phrases, meta: { title: '高频词句' } },
    { path: '/completion', component: Completion, meta: { title: '智能补全' } },
    { path: '/clipboard', component: Clipboard, meta: { title: '剪贴板' } },
    { path: '/activity', component: Activity, meta: { title: '行为明细' } },
    { path: '/locations', component: LocationTrack, meta: { title: '位置轨迹' } },
    { path: '/report', component: Report, meta: { title: '输入报告' } },
  ],
});

createApp(App).use(router).mount('#app');
