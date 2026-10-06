# 通话录音自动发现实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 开启录音上传后自动发现最近七天电话与微信系统录音，日常不要求用户选择文件夹。

**Architecture:** MediaStore.Audio 按时间和明确录音来源筛选，使用系统音频读取权限；已有 HONOR Sounds/CallRecord 持久只读授权同时用于电话和微信发现。沿用私有缓存、稳定观察、音频校验、后台内容去重和成功回执清理，系统原件不变。

**Tech Stack:** Kotlin、Android MediaStore / SAF、JobScheduler、Robolectric。

## 已确认范围

- 用户明确要改开关自动读取相关录音，保留此前最近七天、后台去重、原件保留要求。
- 线上手机通话元数据已成功收到30条；音频仅有9月30日测试记录。本次解决的是音频自动发现，不把两种数据混为一谈。
- 当前无真机连接，不能声称已验证系统音频库是否收录此手机全部录音。未入库的文件复用已授权目录；系统私有文件不会绕过Android权限读取。
- 只处理可识别的电话/微信通话录音，不扩大到音乐、普通微信语音消息及所有音频。按一次音频授权自动发现，不申请全盘管理权限。
- 本轮只改Android；不动并行的游戏、定位等修改，不自动提交、推送或部署后台。

## 实施与验证

1. SystemRecordingDocuments 回归先证明仅授权微信混合目录时电话不会被读取，再修为自动复用实际已持久授予的已知录音目录；保留来源分类，其他任意目录不自动扩展。
2. 新增 SystemRecordingMediaStore 及测试：版本权限、时间窗口、明确来源、音乐排除、pending/trashed排除、读期间取消和原件只读。
3. 新增来源聚合器，使MediaStore与SAF独立发现，单一路径失败不阻断另一可用路径，上传器对两个来源均支持复核；重复内容继续以服务器SHA256及大小去重。
4. Runtime 接入自动发现与可读状态，保留输入/游戏避让；设置页提供真实录音开关说明和一次音频权限入口，手动目录放入可选高级操作。
5. 运行录音相关测试，校验Manifest与权限分支；打非testOnly原签名APK，校验后交付Mac项目apk目录。真机未连时明确实机验收缺口。

参考：[Android 共享媒体读取](https://developer.android.com/training/data-storage/shared/media)。系统权限与文件在MediaStore中的可见性决定可读取范围，不能把音频权限等同于所有应用私有录音可读。

## 本轮实现与验证结果

- 已接入 MediaStore 自动发现、已授权荣耀混合目录复用、独立来源失败兜底和异步权限状态展示。默认操作不再打开目录选择器；设置入口为“通话录音与上传”，目录选择保留在高级可选入口。
- 新回归先确认仅微信目录授权不能读取电话的失败；再确认同原件多 URI 导致系统录音优先失效，以及首个 URI 撤权的失败。修复后按可信 SHA256/大小合并系统候选并选择可读来源，仍保留清理前的来源和持久回执复核。
- `:yuyansdk:testOfflineDebugUnitTest --tests '*callrecording*' --tests '*calllog*'`：102 项通过，0 失败/错误/跳过。MediaStore 测试覆盖 API 28/33 共16项；包括权限、七天、来源分类、只读、查询取消、无目录配置导入和扫描上限。
- `:app:packageOfflineDebug -Pandroid.injected.testOnly=false` 构建通过；APK实际版本 `20261005.17` / `2026100517`，包名 `com.yuyan.pinyin.offline.debug`。API23/27/28/32/36原证书验证通过，非testOnly；实际Manifest含READ_MEDIA_AUDIO与maxSdk32的READ_EXTERNAL_STORAGE。
- Mac交付：`apk/shurufa-2026-10-05-v20261005.17-2026100517-debug-acb48f9b.apk`，源/交付SHA256均为 `acb48f9b667d5f2d3bb53e1a2a43d6cbde2c6dd8dbde059efac96056c08b9eae`。
- 独立只读审查未发现阻断问题。容量边界：单轮最多检查5000条最近音频元数据，含最后会被排除的音乐；超限标记未完整扫描，仍尝试已有SAF目录，不伪报成功。
- ADB无已连接设备，因此未安装本轮包、未确认荣耀实际媒体库覆盖及新录音到线上补传。只读线上核对不等同于新版实机验收。本轮未提交、推送或部署后台。
