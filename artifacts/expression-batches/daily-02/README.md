# 第二批五词四十张原创动态图评审包

**状态：机器审计 40/40；开发侧逐项四姿势视觉检查完成；用户视觉验收待确认，尚未接入正式 APK / API。**

打开 `preview.html` 查看真实 GIF 播放；`contact-sheet.webp` 是总联系表，`contact-sheet-1.webp` 至 `contact-sheet-5.webp` 依次为哈哈、加油、收到、可以、再见。

每词 8 张，前 4 标记 bundled（计划内置），后 4 标记 remote（计划联网）。本次仅制作和评审素材；用户确认后，再接入现有接口及本地缓存链。未生成或安装新 APK。

## 规格与来源

- 40 张均为 240×240、16 帧、1600ms、无限循环 GIF；最大 228570 字节，低于 250KB。
- 20 张计划内置 GIF 合计 3585376 字节，20 张联网 GIF 合计 3332138 字节；不含缩略图及 APK 压缩开销。
- 每张来自内置 imagegen 生成的 4 个不同动作姿势，中文由确定性渲染器生成；不是单图缩放伪动画。
- `inventory.tsv` 列出每项尺寸、帧数、时长、字节数和 SHA-256；`report.json` 保存逐帧审计；`thumbnails/` 保存 40 张首帧 WebP。
- `../../../assets/expression/batches/daily-02/manifest.json` 保存提示词、动作脚本、sourceType=ai-original、来源路径和原件 SHA；同目录保存 40 张原件及 160 个姿势 PNG。

## 开发侧审查

逐词检查所有原件四姿势及 GIF 第 0/3/5/8 帧，覆盖全部关键动作。规格审查及质量审查未留下阻断项，但不代替用户验收，也不构成全面版权保证。

8 项通过显式 poseRects 修正原图非居中的透明分隔：haha-person-laugh、haha-ink-frog、cheer-arrow-rise、cheer-dumpling-pump、cheer-pencil-rocket、cheer-shiba-paws、cheer-runner-friend、bye-puffin-wave。全部原像素保留，没有擦除碎片或篡改 alpha；修后再次检查无邻格碎片、翼尖完整。

螃蟹经独立第二意见确认每姿势两钳，其余为侧腿。非阻断审美备注：蜗牛采用眼柄与脸部眼睛并存的拟人四眼设计，四姿势一致，可由用户决定是否喜欢。抽象图形深浅底复核未见可见矩形背景。部分视觉证据保存在 `verification/*.png`。

## 验证结果

- 完整批次生成：total=40、pass=40、fail=0、complete=true，humanReview=pending。
- 独立 Pillow 解码 40 GIF，核对大小、帧数、时长、循环、SHA，以及 40 缩略图、160 姿势、原件 hash 与每词 4+4 分配：通过。
- `npm test` 默认并行：273 通过、4 失败；失败均是已有 assetGenerator/prototypeRenderer 图像测试超过 5000ms，未改超时或质量门禁。
- `npm test -- --maxWorkers=1`：27 套、277 项全部通过；单独 expression 串行：10 套、182 项通过。上述复跑支持资源并发引起超时的判断，不能把默认并行失败写成全绿。
- `npm run build`：通过；`npm run expression:prototype`：原 12 项全部通过（total=12/pass=12/fail=0）。有 Fontconfig 缓存版本警告。
- 原始日志与独立核验脚本保存在 `verification/`。

本轮未运行正式 expression:generate、正式素材审计脚本、Android 构建或真机验证，因为素材尚待用户看样；未改正式 catalog 或 Android 素材。

复现生成：在 server 目录运行 `npx tsx scripts/render-expression-batch.ts --batch=daily-02`。完整生成会原子替换本输出目录，应事先保留 README、inventory 和 verification。独立核验可在本仓库路径执行 `python3 artifacts/expression-batches/daily-02/verification/verify.py`。
