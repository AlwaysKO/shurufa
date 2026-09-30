# 线上表情素材库菜单缺失核查

时间：2026-09-30，项目 shurufa。只读读取 Git/线上公开静态资源与健康接口，不登录后台、不改生产数据。

## 已核验

- 工作区在检查开始时干净，本地 HEAD 与 `git ls-remote origin refs/heads/main` 均为 `34a3792e921bd766b6462efbb8675170f28494cd`。
- 该提交的 `client/src/App.vue` 包含“个人资产 → 表情素材库”，path `/sticker-materials`；`client/src/main.ts` 包含同名路由与页面组件。菜单已受 Git 管理，不是本机动态配置或浏览器存储。
- 对 `https://my.dog8ball.com/?check=menu` 发起新 HTTP 请求，返回 200，HTML 引用 `index-Bgl-yh6I.js` / `index-BiiGzmXc.css`。响应标明 `Cache-Control: no-cache`，Last-Modified 为 2026-09-29 14:10:46 GMT。这是服务端当前返回的旧资源，不能仅归因于用户浏览器缓存。
- 实际线上 JS 中 `/sticker-materials`、`选择图片批量上传`、`搜索已有推荐词` 均不存在；`表情素材库` 字符串出现 2 次，对应旧版页内标签，并非新版独立侧栏入口。健康接口仅返回 `{"status":"ok"}`，不能证明部署的是新版本。

## 结论与剩余检查

菜单已经随源码提交并推到远程，但当前线上前端尚未反映本次源码。生产当前 REVISION、部署任务执行阶段及错误原因仍需在服务器检查；仅凭旧 HTML 无法断言是构建失败、迁移失败、尚在构建、回退还是站点根目录未切换。

在服务器 Codex 中先只读执行：

```sh
cat /data/phpwww/shurufa/current/REVISION
systemctl status shurufa-deploy.timer shurufa-deploy.service --no-pager
journalctl -u shurufa-deploy -n 100 --no-pager
```

核对本次提交 `34a3792`，若有更晚提交先确认范围，不能强行回退。按照 `deploy/production/README.md` 核对备份、部署锁与运行脚本再处理具体失败，不盲目 force、不覆盖线上图库。部署完成后检查线上 HTML 引用新构建，展开“个人资产”可见素材库，直接打开 `/sticker-materials` 能登录并访问。

当前本地缺少有效生产 SSH 登录凭据，之前身份指纹已核验但 ubuntu 登录被拒绝；本轮未重试口令、绕过登录或声称已修复生产。菜单持久化要求已纳入项目 AGENTS.md。
