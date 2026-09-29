# 只读微信表情采集器来源说明

采集器为本项目独立实现，未复制 `liusheng22/export-wechat-emoji` 源码（未取得其许可证）。其公开描述仅用于核对微信掩码密钥 blob 的格式事实；本项目此前独立编写的只读兼容探针提供了掩码匹配验证逻辑。

未复制 `zhangaiming/xwechat-fav-emoticon-exporter` 源码。Windows API 调用与 SQLCipher/WAL 解析依据公开接口及以下官方格式文档独立实现：

- SQLite WAL、提交帧与校验和：https://www.sqlite.org/fileformat.html#walformat
- SQLite WAL 生命周期：https://www.sqlite.org/walformat.html
- SQLCipher 页面、AES 和 HMAC：https://www.zetetic.net/sqlcipher/design/

运行时依赖通过 pip 安装，不把依赖源码或二进制打入本仓库：

- PyCryptodome 3.23.0：BSD-2-Clause / 部分公有领域，https://www.pycryptodome.org/src/license
- Pillow 11.3.0：MIT-CMU，https://github.com/python-pillow/Pillow/blob/11.3.0/LICENSE
- psutil 7.2.2：BSD-3-Clause，https://github.com/giampaolo/psutil/blob/release-7.2.2/LICENSE

采集仅限用户配置账号的收藏表情原图，不获得、记录或传输聊天数据、联系人、数据库密钥、内存转储或原图下载 URL。表情内容自身的权利不因采集器许可证而变化。

## 2026-09-29 本机来源兼容验证

微信 4.1.13.65 收藏表存在历史 `http://` CDN 地址：采集器只在联网前升级为 HTTPS，绝不发送 HTTP 请求。当前 `vweixinf.tc.qq.com` HTTPS 证书主机名校验失败；经本次任务中的有界兼容性试验，将其初始 URL 的主机精确替换为已允许的 `wxapp.tc.qq.com`、保留路径与查询，抽样 3 张全部通过严格 TLS 且原字节 MD5 与收藏记录一致。因此使用此唯一固定映射作为兼容策略，**不宣称这是官方通用别名**。源地址先通过域名/端口/userinfo 检查，重定向不做此改写且不能降为 HTTP。最终仍逐张强制匹配源 MD5；不匹配即拒绝，不尝试其他域名、不绕过证书校验。
