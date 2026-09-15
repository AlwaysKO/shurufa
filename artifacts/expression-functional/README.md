# 斗图合成功能阶段验证

代码：`9ae5dbf`、`1d69d03`、`944e0c6`（Emoji素材与异常/取消处理）；`dc8e8b2`（AI准备/重试/同词及生命周期）。两项均通过独立规格和质量审查。

- Emoji：统一cache→校验内置→标准URL下载；加载禁发、失败重试，真实文件才可选发。**非内置首次仍需联网**；内置或合法缓存可离线用。
- AI：准备期间保留查询/卡片/标签并持续提示，失败保留以重试；成功或保存相册才清结果，真实编辑/切目标/隐藏/卸载取消；同词再次提交可重新查询。
- 微信GIF：**已真机复现，尚未修复**。用户安装的20260907.17在文件传输助手发送首张谢谢后，微信保存结果是单帧PNG（扩展名.jpg）；缓存仍为16帧GIF。见[复现数据](wechat-gif/reproduction.json)。系统授权URI指向该GIF，实际交付MIME/Provider读回证据继续补查。原测试汇总保留当时状态，不代表真机验收通过。

## 测试范围与证据

[汇总及APK SHA-256](verification.json)，[成功日志](test-build-1536m.txt)。430项通过，无失败/错误/跳过。原默认512MB整套在303项时OOM退出，见[原失败日志](test-build-512m-failed.txt)，不是被计作通过或删测试。

另一项开发任务暂停后仍有6个未完成T9/采集测试无法编译，本轮用[临时精确排除脚本](exclude-unfinished-t9.gradle)排除它们，再用[测试堆脚本](test-heap.gradle)设1536MB、单worker。仅影响该次命令，不改变项目/应用配置；不是整个当前工作区全绿，另一任务完成后必须去掉排除重验。

在Android工程的Gradle命令附加：
```text
-I /home/ko/project/shurufa/artifacts/expression-functional/exclude-unfinished-t9.gradle
-I /home/ko/project/shurufa/artifacts/expression-functional/test-heap.gradle
:yuyansdk:testOfflineDebugUnitTest :app:assembleOfflineDebug
```

用户字号1.1/1.5保留未提交，并包含于测试与APK；另一任务7个文件SHA逐项核对未变；local.properties已恢复，无Gradle工作占用。APK具体路径见汇总，未提交二进制APK。
