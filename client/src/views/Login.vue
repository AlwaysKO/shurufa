<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { login } from '../auth';
const router = useRouter();
const username = ref(''); const password = ref(''); const busy = ref(false); const error = ref('');
async function submit() {
  if (busy.value) return;
  busy.value = true; error.value = '';
  try { await login(username.value, password.value); password.value = ''; await router.replace('/'); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '网络连接失败'; }
  finally { busy.value = false; }
}
</script>
<template>
  <main class="login-page">
    <form class="login-card" @submit.prevent="submit">
      <div class="login-icon">⌨</div><h1>我的输入法</h1><p>登录管理后台，查看个人输入数据</p>
      <label for="username">账号</label><input id="username" v-model="username" autocomplete="username" required maxlength="100" autofocus />
      <label for="password">密码</label><input id="password" v-model="password" type="password" autocomplete="current-password" required maxlength="512" />
      <p v-if="error" class="login-error" role="alert">{{ error }}</p>
      <button type="submit" :disabled="busy">{{ busy ? '登录中…' : '登录' }}</button>
      <small>仅授权用户可访问 · 会话有效期 8 小时</small>
    </form>
  </main>
</template>
<style scoped>
.login-page{min-height:100vh;display:grid;place-items:center;padding:24px;background:linear-gradient(140deg,#eef0ff,#f5f6fa 65%)}
.login-card{width:min(400px,100%);padding:36px;background:white;border:1px solid #e8ebf4;border-radius:18px;box-shadow:0 16px 50px #28354d12;display:flex;flex-direction:column;gap:12px}.login-icon{font-size:34px;color:#4451e8}.login-card h1{font-size:24px}.login-card p{font-size:13px;color:#747d8c;margin-bottom:12px}.login-card label{font-size:13px}.login-card input{padding:12px;border:1px solid #dfe4ea;border-radius:8px;outline-color:#4451e8}.login-card button{margin-top:10px;padding:12px;border:0;border-radius:8px;background:#4451e8;color:white;cursor:pointer}.login-card button:disabled{opacity:.6}.login-card small{font-size:11px;text-align:center;color:#8a94a3;margin-top:12px}.login-card .login-error{color:#c43636;margin:0}
</style>
