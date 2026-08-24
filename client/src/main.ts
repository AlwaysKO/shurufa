import { createApp } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import App from './App.vue';
import Overview from './views/Overview.vue';
import Timeline from './views/Timeline.vue';
import Applications from './views/Applications.vue';
import Phrases from './views/Phrases.vue';
import WordCloud from './views/WordCloud.vue';
import Completion from './views/Completion.vue';
import Clipboard from './views/Clipboard.vue';
import ClipboardHistory from './views/ClipboardHistory.vue';
import Activity from './views/Activity.vue';
import LocationTrack from './views/LocationTrack.vue';
import Report from './views/Report.vue';
import Stickers from './views/Stickers.vue';
import UserPhrases from './views/Phrases.vue';
import DataManage from './views/DataManage.vue';
import ChatCapture from './views/ChatCapture.vue';
import Relationships from './views/Relationships.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: Overview, meta: { title: '输入总览' } },
    { path: '/timeline', component: Timeline, meta: { title: '输入时间线' } },
    { path: '/apps', component: Applications, meta: { title: 'APP 分布' } },
    { path: '/phrases', component: Phrases, meta: { title: '高频词句' } },
    { path: '/wordcloud', component: WordCloud, meta: { title: '词云' } },
    { path: '/completion', component: Completion, meta: { title: '智能补全' } },
    { path: '/clipboard', component: Clipboard, meta: { title: '剪贴板' } },
    { path: '/clipboard-history', component: ClipboardHistory, meta: { title: '剪贴板历史' } },
    { path: '/activity', component: Activity, meta: { title: '行为明细' } },
    { path: '/locations', component: LocationTrack, meta: { title: '位置轨迹' } },
    { path: '/report', component: Report, meta: { title: '输入报告' } },
    { path: '/stickers', component: Stickers, meta: { title: '表情包' } },
    { path: '/user-phrases', component: UserPhrases, meta: { title: '常用语' } },
    { path: '/data', component: DataManage, meta: { title: '数据管理' } },
    { path: '/chat-capture', component: ChatCapture, meta: { title: '聊天采集' } },
    { path: '/relationships', component: Relationships, meta: { title: '关系记忆' } },
  ],
});

createApp(App).use(router).mount('#app');
