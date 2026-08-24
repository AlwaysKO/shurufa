# 离线诗文与日常用语连续联想实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 在不联网、不修改 Rime 原生词库的前提下，提供“关山 → 难越 → ，/谁悲失路之人”式连续联想，并补充常用日常表达。

**架构：** 构建时从固定版本的 MIT `chinese-poetry` 精选语料生成排序后的压缩 TSV 索引，并合并项目自有的日常短语。Android 启动后台线程加载不可变索引；查询使用最长文本后缀匹配，候选合并层保留 Rime 原生候选索引，确保选择离线候选时不会误调用原生关联候选。

**技术栈：** Kotlin、JUnit 4、Android AssetManager、GZIP、Python 3 生成脚本、Rime 现有联想接口。

---

### 任务 1：定义离线索引查询行为

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/completion/OfflineAssociationIndexTest.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/completion/OfflineAssociationIndex.kt`

**步骤：**
1. 先写测试：验证最长后缀、尾部标点归一化、部分句与下一句两种候选、去重和最多候选数。
2. 运行指定测试并确认因类不存在而失败。
3. 实现只满足测试的不可变排序数组和二分查询。
4. 再运行指定测试并确认通过。

### 任务 2：生成精选诗文和日常短语索引

**文件：**
- 创建：`android/YuyanIme/tools/generate_offline_associations.py`
- 创建：`android/YuyanIme/tools/data/common_association_phrases.txt`
- 创建：`android/YuyanIme/yuyansdk/src/main/assets/completion/offline_associations.tsv.gzip`
- 创建：`android/YuyanIme/yuyansdk/src/main/assets/completion/NOTICE.txt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/completion/OfflineAssociationAssetTest.kt`

**步骤：**
1. 先写资源测试：断言索引包含“关山→难越”“关山难越→谁悲失路之人”“床前→明月光”和日常短语。
2. 运行测试并确认资源缺失导致失败。
3. 编写生成脚本，固定上游提交 `b8594f81a89752241442f2ce267d6f66f96704ee`，只取《古文观止》《唐诗三百首》《宋词三百首》《诗经》《千家诗》，转为简体并生成部分句/下一句记录。
4. 加入项目自有日常短语，生成排序、去重、每键最多三个候选的 gzip TSV。
5. 写入 MIT 许可来源和生成说明，再运行资源测试。

### 任务 3：接入启动与候选合并

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/application/Launcher.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/inputmethod/RimeEngine.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/inputmethod/AssociationCandidateMergerTest.kt`

**步骤：**
1. 先写失败测试：部分句补全优先；未输入标点时逗号先于下一句；已输入标点时下一句优先；跨来源去重。
2. 实现纯候选合并函数，同时保存每个候选对应的 Rime 原生索引。
3. 在启动后台线程加载资源，在 `predictAssociationWords()` 注入离线候选。
4. 修改 `selectAssociation()`：只有 Rime 候选才调用 `Rime.chooseAssociate()`，离线/标点/服务端候选直接提交显示文本。
5. 运行新增测试及现有联想相关测试。

### 任务 4：完整验证与真机验收

**步骤：**
1. 运行 `:yuyansdk:testOfflineDebugUnitTest`。
2. 运行 `:app:assembleOfflineDebug` 并检查 APK 中资源存在。
3. 覆盖安装，不卸载、不清数据。
4. 真机验证：`关山 → 难越 → ， → 谁悲失路之人`、`床前 → 明月光`、日常短语联想、九宫格中文候选和微信连续返回。
