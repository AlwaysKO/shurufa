# 五词动态图库正式分发验证

用户验收素材：daily-01 40张，五词你好/早安/晚安/好的/对不起。代码提交：84c7409（正式生成分发）、fc7e184（Android本地优先及缓存）。

## 已完成

- 每词4张原GIF内置APK、另外4张原GIF仅服务端；完整40项索引及40张首帧WebP内置，服务器与Android catalog一致。
- 五词旧静态预制项被新GIF替代，其余短语原ID与旧三词12张原创保持。
- 初次先显示内置及SHA有效缓存，联网补齐；原GIF下载进入校验缓存，重建进程可离线发现。缓存可被系统/用户清理，届时重下载。
- 预览与发送源复用原GIF，不现场叠字/重新编码；已知APK缩略图仍本地回退，未来未知缩略图维持远端地址。

## 验证与边界

- 服务端25套253项通过，build通过，prototype total12/pass12/fail0，正式generate及shell审计通过。
- Android expression包29类198项通过（无失败/错误/跳过），assembleOfflineDebug成功；未运行完整yuyansdk套件，不宣称全SDK全绿。
- 本机运行服务真实HTTP五词各8项，前4bundled，40个GIF下载响应SHA与catalog一致（verification/local-api.json）；无需重启服务即可读取新runtime。
- APK ZIP独立核对：20张新内置GIF与验收SHA一致；20张remote GIF不在APK，40张缩略图存在。
- 无真机测试，未操作手机，也未宣称微信实际发送已修复；应用内发送源字节一致不等于微信接收验收。
- **未部署线上服务器。** Debug默认127.0.0.1:3000，手机拔线且无反向转发时需配置可达API；不能仅安装APK就期待联网另4张。线上需部署此正式清单/源GIF并执行expression:generate，核验域名/API可达后再验收。
- APK使用当前工作区构建，包含用户已有未提交修改；本轮提交只纳入图库相关文件，未覆盖或提交那些修改。

APK：android/YuyanIme/app/build/unix/outputs/apk/offline/debug/yuyanIme_2026090814_debug.apk
SHA-256：213ecaa336b99fc6d3b6a97a2e45dd02e71441e024457c23016dcae14d98b580

原始验证日志及准确测试类列表见verification。生成中一次进程SIGTERM后已完整重跑成功，测试阶段曾因旧产物/版本出现RED，交付日志记录最终成功结果。Fontconfig缓存版本及Android弃用类警告未当作不存在。
