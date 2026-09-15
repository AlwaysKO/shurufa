# scene-rich-05 生产记录

延续已认可的约3秒节奏，本批每词1张，不是每词4张已补齐，不加入APK。

| 词语 | 风格与表演 | 字节 |
|---|---|---:|
| 你猜 | 极简藏拳：逐个举拳→挑眉坏笑→放下 | 103612 |
| 听我解释 | 极简眼镜鸟：打翻杯子后心虚侧看→举翼请求发言→尴尬笑 | 75875 |
| 嫌弃 | 原创写实狸花猫：侧眼→耳朵后转→眯眼缩下巴 | 140659 |
| 困了 | 原创写实巴哥：撑眼皮→慢慢闭眼→勉强睁开 | 126975 |

每项九宫格真实不同姿势、16帧、3000ms循环，开场400ms、关键姿势700ms，不机械倒放或交叉淡化。中文由确定性字体渲染，首帧WebP来自实际GIF。所有项240×240、低于250KB，原质量门禁4/4通过。

你猜首稿loopClosure失败，经image_gen.imagegen修正末格轮廓后通过；旧稿保存于源rejected/，提示词、修正提示词、来源路径与SHA在manifest，不放宽门禁。

执行命令：`cd server && npx tsx scripts/render-scene-rich-05.ts`。最终实际GIF SHA、尺寸、延迟及逐帧审计见report.json；抽帧见motion-evidence/，动态见preview.html。

TDD RED 2失败9通过→GREEN11/11；根代理最新 `npm test -- --maxWorkers=1` 为357/357，build通过。存在既有Fontconfig缓存版本警告。实现后规格审查、独立代码质量审查及真实母版/抽帧视觉复核无阻塞。

视觉局限：你猜首末轮廓仍略有位移，闭环差异接近门限；动物头部尺度、毛发/毯子纹理有轻微漂移及128色抖色。不宣称视频级流畅或完全无缝。humanReview=pending，模型复核不能冒充用户播放验收。

归档server/images后164张、41词，52张published、112张review-only。本批4张均review-only。未修改正式catalog/API/Android/client，不操作手机；保留用户并行工作区改动。
