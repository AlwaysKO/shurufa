# miaoyan 签名迁移与保留数据打包

用户授权：后续使用新建 miaoyan.jks；延续不卸载、不清除本地数据要求。旧密钥仍存在，手机 Android API 36；新密钥已验证可读取，不记录密码。

## 实现与验收步骤
1. 私有配置/密钥不入 Git；保存旧→新签名 lineage，授权 installed-data，不开放旧密钥回滚。
2. 在 app 的 debug/release 打包结束阶段按 lineage 重签。Android 9+ 使用新密钥；Android 6–8 保留旧签名兼容，不修改 minSdk。保留包名差异，不冒充 debug→release 数据迁移。
3. 新配置缺失时构建失败并提示配置，不静默输出旧签名包。普通 Assemble/IDE Run/手动签名最终均检查新旧签名链；Run包仍有testOnly，不能交付。
4. 真实 APK 证书回归先红后绿：API23/27旧证书，API28/32/36新证书；非testOnly、debug/release签名通过。
5. 安装前暂停应用并备份私有目录；只以 install -r 覆盖 debug；保持安装身份与既有本地文件，再以新签名包第二次覆盖验证后续更新。异常不卸载，不擅自恢复覆盖新数据。
6. 输出日期/真实版本/SHA命名APK至 E:\Projects\shurufa-android\apk；记录该机真实迁移结果。密码不入源码、日志或提交；不 git commit/push。

## 已完成本地验证 / 尚未进行手机迁移
- 旧证书SHA256：a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287；新证书：03066813801f4254a3aff3b5835ac8bafff59dc38fd452989611ecd0f8e03ab9。
- lineage生成成功，旧证书installed-data授权开启，rollback关闭；未修改或删除任何私钥。配置在本机私有文件，密码仅进子进程环境变量。
- 真实旧APK在API28期望新证书时先红；接入后debug/release均通过API23/27旧证书、API28/32/36新证书验签，且非testOnly。
- 修正审查发现的WSL正反斜杠路径转换；真实Gradle路径回归通过。重签后删除旧v4 .idsig侧车，避免IDE使用不匹配的签名。
- 实测缺rotation.properties时构建失败；恢复配置后正常。实测IDE注入testOnly时仍追加签名链，但不向分享目录交付；恢复普通assemble后成功。
- Windows debug/release构建成功，原有应用代码未修改。交付至固定E盘目录：debug-a33a835e / release-e9ae4535，均20260918.18 / 2026091818。
- 准备新备份前Windows ADB发现手机已断开，因此此次没有安装迁移包、没有卸载/清数据、没有更改手机签名。已询问用户重新连接；旧同签名覆盖安装的备份记录不能代替本次新签名迁移验收。
- 没有Git提交或推送；本地验证日志在 .runtime/signing-recovery/rotation-*.log 等，隐私备份不得分享。

## 已取消（用户最终决定）
用户确认旧签名包988f16db已安装成功，决定不更换证书。新建密钥已备份，miaoyan.jks替换为原密钥的相同副本；轮换脚本/lineage归档本机.runtime，不再参与构建。现行实现为tools/signing-delivery.gradle，文档tools/SIGNING.md。本页前述轮换计划仅为历史，未在手机执行迁移。
