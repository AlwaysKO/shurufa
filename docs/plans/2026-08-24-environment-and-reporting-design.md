# 环境隔离与手机上报可观测性设计

## 目标

为项目提供本地、线上两套互不混用的配置，并增加根目录一键启动和手机上报检查能力。

## 环境边界

- 本地环境：Node 服务监听 `3000`，管理后台由 Vite 启动；Android Debug 包默认通过 USB `adb reverse` 访问 `http://127.0.0.1:3000`。
- 线上环境：Android Release 包固定访问 `https://myapi.dog8ball.com`；管理后台部署在 `https://mymanage.dog8ball.com`，由 Nginx 将 `/api` 请求代理到线上 API。
- `.env.local`、`.env.production` 保存真实配置并被 Git 忽略；仓库仅提交对应的 `.example` 模板。
- Release 包不接受手机设置页中的服务器地址覆盖，避免正式包误连本地；Debug 包保留覆盖能力，方便特殊网络环境调试。

## 启动与部署

根目录 `start.sh` 接受 `local`（默认）和 `production`。本地模式加载 `.env.local`、执行数据库迁移、尝试配置 ADB 端口反向代理，然后同时启动服务端和 Vite。线上模式加载 `.env.production`、迁移数据库、构建前后端并启动编译后的 Node 服务；前端静态文件由 Nginx 托管。

提供 Nginx 配置模板，分别声明 API 反向代理站点和管理后台静态站点。TLS 证书仍由线上主机的证书工具管理，不把证书路径或密钥写死到项目。

## 上报可观测性

Android 采集器使用固定日志标签记录设备注册、事件批次、位置上报的成功状态码和失败原因，但不输出输入文字、坐标或请求正文。`scripts/watch-reporting.sh` 展示该标签的实时日志；`scripts/report-status.sh` 检查后端健康状态、ADB 设备、端口反向代理、最近设备和最近事件。

成功标准：Debug/Release URL 解析测试通过；服务端测试、客户端构建和 Android 单元测试通过；本地启动脚本能清楚报告手机目前是否可被 ADB 识别。
