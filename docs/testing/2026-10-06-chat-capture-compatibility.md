# 2026-10-06 微信/抖音采集兼容验收记录

## 实证与实现范围
- 抖音40.6.0已出现右侧“语音”/“更多”等布局与ID变化，旧识别拒绝；正式回归仅保留脱敏结构fixture。
- 微信8.0.78空页面树、系统截图间歇失败；旧包主动发送复测有本地持久化及线上接口有效回执，不能据此称所有发送漏采已解释或后台展示已验收。
- 新实现：按宿主版本下发规则、严格32KiB与字段校验、同来源最后有效缓存与原子读取、5分钟版本TTL、历史回滚；微信/抖音保守同层识别，未知微信页面有界探测、瞬态截图错误有限重试，fatal/secure不绕过。
- 生产与本地设置页提供页面、截图、持久化、有效消息上传回执四阶段元数据；不含姓名/正文/图片/任意异常信息，图片准备失败为-1004。配置不是采集同意开关，不改变输入/游戏优先和Wi-Fi上传。

## 最终验证
- 后端定向3文件54项通过：chatCaptureSettings、chatCaptureClient、expressionDelivery。日志 `/tmp/shurufa-chat-compat-server-final.log`。不是服务端所有测试。
- server/client构建成功；client保留既有>500KB包体警告。隔离Chromium合成mock验证菜单/规则、近期/过期/缺失诊断和非法草稿校验，未访问生产或写真实数据库。
- Android定向宽回归：131类774项，0失败/错误/跳过；`--tests '*capture*' --tests '*collect*' --offline`，日志 `/tmp/shurufa-chat-compat-android-final.log`，BUILD SUCCESSFUL 3m22s。不是全部Android测试。
- 初次宽回归7项失败已定位共享输入状态：失败类原样独立36项通过，污染源组合复现；仅在相关测试前后重置input/game，未放宽生产守卫。最终宽回归通过。
- 规范、质量与最终跨端集成均经独立审查；修复通知platform诊断遗漏、图片准备失败静默、微信混合新旧ID回退、配置跨来源竞态、诊断总超时被共享准备覆盖的问题。
- 实际编译Android内置规则与服务端默认JSON比较完全一致；独立40万次并发读取错来源为0。生产准备调用诊断5秒/普通截图300秒，纯JVM真实HTTP滴流验证总截止与未ACK保留；不替代绑定Wi-Fi真机测试。
- 原签名`:app:assembleOfflineDebug --offline`构建通过，日志 `/tmp/shurufa-chat-compat-apk.log`。Git diff --check通过，无Git提交/推送。

## 唯一推荐APK
- Windows：`E:\Projects\shurufa-android\apk\shurufa-2026-10-06-v20261006.17-2026100617-debug-27476ecf.apk`
- WSL：`/mnt/e/Projects/shurufa-android/apk/shurufa-2026-10-06-v20261006.17-2026100617-debug-27476ecf.apk`
- 包名：`com.yuyan.pinyin.offline.debug`；versionName `20261006.17`；versionCode `2026100617`；非testOnly。
- SHA256：`27476ecf23766d91a1f4695be8042c2162d540c6162920d66815293552114536`
- 签名SHA256：`a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287`
- 源与交付文件SHA256一致，签名核验通过；只删除本次已验证的WSL构建APK副本，E盘文件保留。

## 未验收与限制
- 未自动安装手机、部署后台、修改生产、提交或推送；线上新管理页/配置端点尚未生效，新包真机采集/后台入库展示与输入游戏性能仍需验收。
- 旧后台404继续保守内置/最后有效缓存，诊断失败不阻断业务队列；不因缺诊断宣称正常。
- 版本与规则刷新TTL为5分钟，输入/game会推迟；最新诊断随Collector空闲节拍落盘补传，限频为进程内每槽60秒，重启可提前再发送一次，最多8槽有界。
- 无法安全确认的页面继续拒绝并诊断。常见ID/标签/布局变化可改后台规则；全新宿主机制仍可能需要升级APK，不承诺任意升级永不漏采。
- 原始真机诊断仅本地私有`.runtime/chat-investigation-20261006/`，不作为正式fixture、不进Git。
