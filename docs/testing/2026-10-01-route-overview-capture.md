# 选好路线即截图与持久去重验收（2026-10-01）

## 本轮规则

- 用户明确要求选好路线即截图、不等开始导航，同一路线不能重复截图。识别总览页含起终点、路线耗时/距离及开始导航入口后，直接保存当前路线图。
- 已询问去重范围，截至实施尚无答复，采用告知过的默认口径：手机当地同一自然日、同一地图、相同起终点只记一次。同起终点备选路径也按一条处理；“我的位置”按地图提供的文字保存，不伪造实际起点地址。
- 同一记录在截图调用前判重；切换页面、再次搜索、上传后或应用重启仍判重。待传文件与成功上传后的小回执标记配合，回执标记写入并校验成功后才删除截图；七天后仅清理过期回执，不删离线待传图。
- 截图/存储/同意撤回失败不记成功，保留重试机会。沿用输入避让、地图窗口裁剪、前后窗口/节点校验、Wi-Fi验证和熄屏上传限制。
- 后台改为“路线总览截图”，显示 overview_at。保留旧服务端要求的 started_at 字段，新记录填截图时间，仅为协议兼容，不能用于断言已开始导航。无数据库结构变更、无须 SQL 迁移。

## 验证证据

- 先在旧实现新增“选好路线无需开始即可保存”用例，断言失败；再替换旧导航确认状态机并覆盖新行为。
- 初次回归发现 AtomicFile.finishWrite 在标记目标异常时可能不抛异常；失败用例证明会错误删除待传图。现加落盘校验，失败保留待传图。
- 导航、截图、输入优先和 Wi-Fi/熄屏守卫定向测试通过；最终数量与打包信息见下方执行结果。
- client 生产构建通过，存在既有 >500 kB 分块提醒；Android 编译通过。
- 独立只读审查检查截图前去重、重启/上传后的持久性、失败回执、窗口/input/upload守卫和旧接口兼容。审查中的时区缓存边界已加入失败测试并修复为每次取手机当前时区。

## 真机检查与限制

- 只读检查连接的荣耀手机（百度地图22.0.0）：导航/同步开关启用，无障碍服务启用，Wi-Fi验证可用。实际路线总览提供 route_search_input_start_text / route_search_input_end_text / route_tab_item_time / route_tab_item_distance / to_pro_nav；268节点，端点位于223/225，处于读取上限内。
- 脱敏结构进入 NavigationPageTest；真实页面 XML 仅保存在 Git 忽略的 artifacts/diagnostics/2026-10-01-route-overview/，不提交真实地址或图片。
- 新 APK 尚未安装，不能将页面结构和自动化测试声称为新包真机端到端通过；高德实际页面未验收，整机输入/其他应用性能亦未作量化验收。
- OtherSettingsFragment 出现并发修改，把导航开关标题改为“输入记录”、覆盖本轮说明。本轮保留该标题，仅恢复准确的百度/高德路线截图说明和授权弹窗。其他通话记录/录音开发文件保留，未替它们宣称功能验收。
- 未安装手机、未部署后台、未提交或推送 Git。手机采集行为须安装本轮新包；后台文字改动须部署后可见。

## 可重复命令

```bash
source .runtime/macos/android-env.sh
android/YuyanIme/gradlew -p android/YuyanIme :yuyansdk:testOfflineDebugUnitTest --tests '*Navigation*' --tests '*WindowScreenshotter*' --tests '*GuardedChatBody*' --tests '*ImageUpload*' --tests '*InputPriorityCancellation*' --tests '*ScreenshotUploadDeviceIdleTest' :app:compileOfflineDebugKotlin --console=plain
(cd client && npm run build)
git diff --check
bash .runtime/macos/build-apk.command
```

## 最终执行结果

- 定向 Android 测试：19个类49项，0失败/错误/跳过；包含时区变更回归。Android 编译通过，client 构建通过，git diff --check通过。
- 最终唯一推荐 APK：`/Users/pj/project/shurufa/apk/shurufa-2026-10-01-v20261001.00-2026100100-debug-1d8fc935.apk`。
- versionName `20261001.00`，versionCode `2026100100`，包名 `com.yuyan.pinyin.offline.debug`，非 testOnly。API23/27/28/32/36 原签名验证通过。
- SHA256：`1d8fc935666d868a2ad8d441de244291f9427197a27ffdc25bfacdcb4d38e5b3`。核验交付文件哈希与实际APK含本轮去重入口及新说明。
- 本轮较早生成的 `e276d303` 被上述最终包取代，不删除原文件；必须使用 `1d8fc935` 安装验收。本机为 Mac，按当前 AGENTS 交付 apk/，构建脚本的 Windows E 盘缺口提示不适用。
