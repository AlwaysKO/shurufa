# 生产环境自动部署

本目录归档 `/home/ubuntu/shurufa-deploy` 当前使用的部署脚本及配套配置（2026-09-16），属于原有 `AlwaysKO/shurufa` 仓库。
2026-09-23：deploy.sh 新增公共关键词推荐图库的原图校验与数据导入。仓库中的其他配置仍为线上快照，实际运行目录需单独安装更新。
2026-10-01：已在部署锁保护下，将后台输入比较工具和跳过判断安装至实际运行目录，保留线上图库导入opt-in等原有规则。Android/文档单独变化不再重新部署后台；本地和线上脚本副本各14项隔离测试通过，安装未重启应用或切换发布版本。
2026-09-23：已在线上手机 API 的 location 启用 JSON gzip，避免约 1.23MB 的表情目录超过客户端 30 秒总超时。当前设备目录压缩后约 202KB；原始与解压后内容逐字节一致，不支持 gzip 的客户端仍返回原始 JSON。本站点配置已备份、通过 `nginx -t` 并平滑重载，同步归档在 `nginx.conf`。

## 文件

- `deploy.sh`：拉取 `origin/main`，构建前后端及表情素材，备份数据库、执行迁移、切换发布目录并检查健康状态；失败时回退应用版本。
- `deploy-inputs-changed.py`：比较当前线上REVISION与目标提交的发布输入，Android/文档等无关变更直接跳过；须与deploy.sh一起安装到运行目录。
- `stage-keyword-gifs.py`：按已入库清单从本次 Git 版本补齐成品 GIF；缺文件或 SHA 不一致时阻止发布。与 `deploy.sh` 一起安装到运行目录。
- `migrate.mjs`：在事务中执行新增 SQL 迁移，校验已执行迁移的校验和。
- `ensure-call-recording-key.py`：迁移后、切换发布前检查录音密钥；首次空库自动生成，共享目录持久保存，后续部署复用。
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
推送到 main 后定时任务检查当前线上REVISION与目标提交的实际差异：仅Android、文档、诊断或本目录快照变化时跳过后台部署，不构建、不备份数据库、不迁移、不切换版本、不重启服务；其他分支不会触发。
发布输入包括`server/`、`client/`、`assets/`，以及`assets/expression/approved-keyword-gifs.json`引用的外部成品GIF。其中任一变化仍正常发布，包含依赖锁文件、SQL和素材。比较基准始终是当前线上版本，因此不会漏掉前几次提交中尚未发布的后台变化；跳过时不改写REVISION冒充部署成功。
首次部署和显式`--force`仍按原流程执行；Git历史缺失、比较或清单解析失败时中止，保留当前版本。新筛选规则需安装更新实际运行脚本后才生效，单独推送仓库快照不会更新运行目录。
构建完成后脚本会将源码工作目录 `git reset --hard` 到所部署提交，因此不要在生产源码目录保留未提交修改。

当前脚本从 Git 导出 `server`、`client`、`assets`，再按 `approved-keyword-gifs.json` 补齐清单引用的成品 GIF，不导出制作原图。公共推荐图库另外根据 `server/data/sticker-library.json` 从本次提交提取上传原图；迁移后事务导入关键词、匹配说法和图片顺序。上传目录始终指向共享存储。本目录不会自动同步到 `/home/ubuntu/shurufa-deploy`。
以后修改本目录的部署逻辑，需要另外在维护时段将审核后的文件安装到运行目录；服务配置变更还需要 systemd 重新加载。
本目录不是本地开发启动脚本，路径、端口和权限均针对现有服务器。

## 运行依赖与凭据

现有环境需要 Git/SSH 只读拉取权限、Node 22/npm、Python 3、flock、PostgreSQL 客户端及数据库、Nginx/Certbot，
以及 ubuntu 用户无交互重启/停止 `shurufa.service` 的 sudo 权限。
发布、备份和共享上传目录及对应权限须事先准备好；Nginx 配置引用的证书须已存在。
`production.env.example` 仅为无密钥参考，不能直接覆盖生产配置。实际 `production.env` 同时供 shell 和 systemd 读取，值需符合两者语法。

### 录音密钥自动初始化

部署运行目录须同时安装更新后的 `deploy.sh` 和 `ensure-call-recording-key.py`。仅把源码推送到 main 不会更新运行目录中的部署脚本。新流程在 SQL 迁移完成后、应用切换与重启前执行密钥检查；同一版本提前退出时不会重复初始化。

安装前比较实际运行脚本，保留已有运维规则。2026-09-30 线上脚本另有图库导入显式开关，本次仅在原脚本的迁移后加入录音检查和环境重载，未用仓库快照覆盖这些线上保护。

首次部署若未配置密钥且 `call_recording` 没有任何记录，脚本使用系统安全随机源生成 32 字节密钥，写入 `/data/phpwww/shurufa/shared/private/call-recording.key`。目录权限 700、文件权限 600，位于共享持久目录，不随发布清理。文件完整写入并同步后才发布，已有同名文件绝不覆盖。已有合法密钥只复用；已配置其他合法密钥路径时保持原路径，不迁移、不轮换。权限过宽时只收紧权限，不改变密钥内容。

脚本在需要添加 `CALL_RECORDING_KEY_FILE` 时先创建 `production.env.before-call-recording-*` 私有备份，再原子更新环境文件；备份和环境文件均为 600，内容和密钥均不输出到日志。初始化可重复执行，正常重跑不会再次生成密钥或备份配置。环境变量只保存文件路径，不保存密钥内容；路径必须为绝对路径，不支持变量或命令展开。

若已有录音记录但密钥缺失，或者现有密钥长度错误、文件类型错误、数据库检查失败，部署会停止，保留当前应用版本。必须恢复原始密钥后重试，不能生成新密钥冒充修复，否则历史录音无法解密。密钥须另行纳入受控私有备份；数据库备份不包含密钥，不能单靠数据库备份恢复录音。

本地回归验证（临时目录和模拟数据库命令，不访问线上）：

```bash
python3 deploy/production/test_call_recording_key.py
python3 deploy/production/test_deploy_inputs.py
bash -n deploy/production/deploy.sh
```

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
