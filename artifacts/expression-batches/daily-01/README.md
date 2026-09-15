# 五词四十张原创动态素材评审包

状态：机器审计 40/40；开发侧逐项四姿势视觉检查已完成；**用户视觉验收待确认，尚未接入正式 APK / API**。

- 打开 `preview.html` 查看40张真实GIF播放。`contact-sheet.webp`总表，`contact-sheet-1.webp`至`contact-sheet-5.webp`依次为你好、早安、晚安、好的、对不起。
- 每词8张：前4标记内置（bundled），后4标记联网（remote）；只是计划分发标记，本轮未实现或部署远端缓存链。
- 每张240×240、16帧、1600ms、无限循环，最大248475字节；20张计划内置GIF合计3838188字节，不含缩略图和APK压缩开销。
- `inventory.tsv`是40项尺寸、帧数、时长、大小及SHA-256；`report.json`含完整机器审计和逐帧数据；`thumbnails/`为40张首帧WebP。
- 生成使用内置 imagegen。完整提示词、来源路径、原件SHA及动作脚本见 `../../../assets/expression/batches/daily-01/manifest.json`，原件和160个关键姿势保存在该批次目录。

## 检查与返工

开发侧检查每张GIF第0/3/5/8帧覆盖四姿势。你好3项、早安6项、晚安3项通过显式poseRects修正分隔错位，全部原像素保留，不涂抹碎片。晚安阅读人物首版无法安全矩形分割，第二版为伪透明棋盘RGB而拒收，采用第三版重新生成的真实透明原件；未采用原件在源码批次rejected目录。

五词全部已复查：未发现阻断性邻格污染、切头或乱码；文字和动作可读。不宣称完成全面版权清查，仍需用户最终视觉确认。

## 验证

`verification/`保存原始日志：服务端25套/239项测试通过，TypeScript构建通过；旧prototype仍12/12通过。本次有Fontconfig缓存版本警告，不影响上述退出成功结果。独立Pillow解码核对40 GIF的尺寸、帧数、时长、循环及大小。

复现：在server目录执行 `npx tsx scripts/render-expression-batch.ts`、`npm test`、`npm run build`、`npm run expression:prototype`。完整渲染会原子替换输出目录，应先保留本README/inventory/verification等交付记录。

本轮没有运行正式expression:generate、Android构建或真机验证，也未改正式catalog或Android素材。下一步等用户看样确认后，再落实每词4张内置、其余接口分发及本地缓存。
