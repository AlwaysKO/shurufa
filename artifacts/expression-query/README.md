# 长句推荐、中国人物与迟到缓存验收（2026-09-09）

## 当前交付

- 长句采用两端一致的有限关键词/别名/语气词/意图规则，不是通用语义模型；否定和打字/打电话等反例有共享测试。
- 推荐保留原件固定文字，直接预览及发送原GIF；整句叠字只留AI合成。连续中文提交有会话缓冲，不读取宿主整段正文。
- 查询索引与SHA原件持久缓存；相同查询单飞，输入切换/删除不取消已开始的预取，不向旧UI回调；重新实例化可离线复用。128查询/7天有效/64MiB已完成原件/单件2MiB/4查询/2下载。视图销毁、scope取消及杀进程不保证续传；后续可补启动清扫杀进程遗留的.part，不承诺所有临时文件都计入64MiB。
- 原件仍按字节校验，不为缓存转码。未知远端条目要求允许来源类型、SHA及同源地址，APK已审计条目兼容；服务端仅自有catalog，不继续旧版来源不明第三方搜图。
- 用户看到“你们”高质量图片的具体来源无法仅凭外观确认。旧版可经我们的接口转发第三方搜索，也有手机现场合成慢路径，没有设备请求记录，不能断言具体那张。

## 素材预览（尚未正式接入新批次）

- [daily-03修订40张](../expression-batches/daily-03/preview.html)：难过/震惊4个人物已按原创中国人物设定替换；旧验收记录在history保留。
- [action-01新8张](../expression-batches/action-01/preview.html)：打闹/追赶各4张，分别有原创核心、动物、中国生活人物、黏土毛绒风格。
- 两批通过规格及独立质量视觉审查。每张尺寸、帧数、时长、大小与SHA在各自inventory.tsv。
- 仍按用户“积累后统一加入APK”安排：本次APK含推荐/缓存修复，未加入daily-02/daily-03/action-01评审素材。动作关键词fixture通过不等于当前APK已有动作图。
- 正式生成仅为现有52张审计预制条目传播sourceType，APK目录总数与素材未扩张；新远端来源字段需部署相应生成清单及服务端代码才在生产接口生效，本次未宣称线上已部署。

## 验证

- 服务端npm test：329/329；npm run build通过。
- npm run expression:prototype：total=12、pass=12、fail=0。
- npm run expression:generate与scripts/tests/expression-assets-test.sh通过：112 prebuilt、60 synthesis、72 GIF；48 Emoji bases、2304组合，Android内置355素材文件。测试脚本的40 static指模板静态子集，生成器的100 static包括预制静态，口径不同。
- 独立Pillow复核daily-03共40及action-01共8：240×240、16帧、1600ms、loop0、低于250KB；缩略图与SHA审计通过。
- Android全部`*Expression*`相关测试264/264，覆盖catalog、query、cache、resolver、preview、panel、发送及InputView表情集成；不是整个yuyansdk全套。
- Android测试使用临时Gradle init：Test maxHeapSize=1536m、maxParallelForks=1，未改项目构建配置。前次默认堆OOM及旧MockWebServer配置失效记录保留；旧测试通过局部test-only ServerConfig shadow修复，未删除HTTP断言。
- :app:assembleOfflineDebug通过。APK：android/YuyanIme/app/build/unix/outputs/apk/offline/debug/yuyanIme_2026090912_debug.apk。
- 没有连接手机，未安装、未录屏、未进行本次真机发送/缓存验证。

日志位于[verification](verification/)。独立规格/质量审查均通过；所有本次新增/修改与产物检查为ko:ko，保留用户提交及无关client修改。
