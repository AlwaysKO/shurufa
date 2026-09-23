# 生产环境自动部署

本目录归档 `/home/ubuntu/shurufa-deploy` 当前使用的部署脚本及配套配置（2026-09-16），属于原有 `AlwaysKO/shurufa` 仓库。
2026-09-23：deploy.sh 新增公共关键词推荐图库的原图校验与数据导入。仓库中的其他配置仍为线上快照，实际运行目录需单独安装更新。

## 文件

- `deploy.sh`：拉取 `origin/main`，构建前后端及表情素材，备份数据库、执行迁移、切换发布目录并检查健康状态；失败时回退应用版本。
- `stage-keyword-gifs.py`：按已入库清单从本次 Git 版本补齐成品 GIF；缺文件或 SHA 不一致时阻止发布。与 `deploy.sh` 一起安装到运行目录。
- `migrate.mjs`：在事务中执行新增 SQL 迁移，校验已执行迁移的校验和。
- `healthcheck.mjs`：检查健康接口，并使用环境变量中的管理账号登录、访问后台 API、退出会话。
- `serve.mjs`：应用启动入口，仅监听本机地址。
- `shurufa-deploy.service`、`shurufa-deploy.timer`：部署任务及定时检查。
- `shurufa.service`：应用运行服务。
- `nginx.conf`、`proxy.conf`、`reload-nginx.sh`：本站点 HTTPS、代理及证书续期重载配置快照。
- `production.env.example`：不含密钥的环境变量示例；实际凭据由服务器单独维护。

## 线上路径与自动部署行为

- 源码：`/home/ubuntu/shurufa-app`，远程 `git@github.com:AlwaysKO/shurufa.git`，生产分支 `main`。
- 正在执行的脚本及私有配置：`/home/ubuntu/shurufa-deploy`。
- Node：`/home/ubuntu/.local/node22/bin/node`。
- 发布目录：`/data/phpwww/shurufa/releases`；`current` 指向当前版本。
- 上传目录：`/data/phpwww/shurufa/shared/uploads`；数据库备份：`/data/phpwww/shurufa/backups`。
- Nginx 主配置安装路径：`/etc/nginx/sites-available/my.dog8ball.com`；代理配置：`/etc/nginx/shurufa-proxy.conf`。

systemd 在开机后约 30 秒开始检查，此后在上次任务结束 60 秒后再次检查 GitHub main。
推送到 main（包括仅修改文档或本目录）会触发现有自动部署；其他分支不会。
构建完成后脚本会将源码工作目录 `git reset --hard` 到所部署提交，因此不要在生产源码目录保留未提交修改。

当前脚本从 Git 导出 `server`、`client`、`assets`，再按 `approved-keyword-gifs.json` 补齐清单引用的成品 GIF，不导出制作原图。公共推荐图库另外根据 `server/data/sticker-library.json` 从本次提交提取上传原图；迁移后事务导入关键词、匹配说法和图片顺序。上传目录始终指向共享存储。本目录不会自动同步到 `/home/ubuntu/shurufa-deploy`。
以后修改本目录的部署逻辑，需要另外在维护时段将审核后的文件安装到运行目录；服务配置变更还需要 systemd 重新加载。
本目录不是本地开发启动脚本，路径、端口和权限均针对现有服务器。

## 运行依赖与凭据

现有环境需要 Git/SSH 只读拉取权限、Node 22/npm、Python 3、flock、PostgreSQL 客户端及数据库、Nginx/Certbot，
以及 ubuntu 用户无交互重启/停止 `shurufa.service` 的 sudo 权限。
发布、备份和共享上传目录及对应权限须事先准备好；Nginx 配置引用的证书须已存在。
`production.env.example` 仅为无密钥参考，不能直接覆盖生产配置。实际 `production.env` 同时供 shell 和 systemd 读取，值需符合两者语法。

不提交 `production.env`、`access.txt`、`htpasswd`、锁文件、日志、旧备份或数据库备份。
数据库迁移成功后不会随应用回退自动撤销；新增迁移需向后兼容，已执行 SQL 不应原地修改。
脚本清理旧发布目录和数据库备份，通常保留最近五项；当前和上一应用版本额外保留。

## 只读检查

```bash
systemctl status shurufa shurufa-deploy.timer
journalctl -u shurufa-deploy -n 100 --no-pager
cat /data/phpwww/shurufa/current/REVISION
```

以下命令会实际触发检查或强制重新部署，仅在需要发布时执行：

```bash
sudo systemctl start shurufa-deploy.service
/home/ubuntu/shurufa-deploy/deploy.sh --force
```

本地查看：执行 `git fetch origin` 后切换到包含本目录的分支，打开 `deploy/production/`。合并到 main 后可在 main 上通过 `git pull --ff-only origin main` 获取。

公共推荐图库的本地编辑、Git钩子和新服务器恢复步骤见 [SHARED_STICKER_SYNC.md](../../docs/SHARED_STICKER_SYNC.md)。
