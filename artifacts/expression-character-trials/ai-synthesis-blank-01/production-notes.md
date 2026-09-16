# AI 合成无字 GIF：首批四方向隔离样片

日期：2026-09-15。来源：本次用户同意功能与新素材一起做，先出四张动态样片，认可后再扩充。

## 交付与复现

打开本目录 `preview.html` 播放；成品在 `output/*.gif`。母版 `masters/*.png`、姿势帧 `poses/`、首帧 WebP、HTML 仅保留本地，受根 .gitignore 排除。最终 GIF、提示词、报告及必要编码脚本可进入 Git。

```bash
cd /home/ko/project/shurufa/server
npx tsx scripts/renderBlankSynthesisTrial.ts
npx vitest run src/expression/blankGifTrialRenderer.test.ts
```

工具：内置 image_gen 生成/修正 4×3 母版；本项目 Sharp 编码器切出十二真实姿势，复用 sceneRich12Timeline 编成 20 帧、4秒。透明部分在编码时按暖白底合成，保留2像素透明外边。没有字幕绘制步骤，没有在动画内进行缩放、倒放或交叉淡化。20 帧包含表情停顿，不能宣称20个独立姿势。

提示词及两次修订在 `prompts/`；源图/GIF SHA-256、机器逐帧报告在 `report.json`。人物创作设定为原创中国成年男性，不以外貌推断现实国籍，不参考明星脸。

| 样片 | 表演方向 | 字节 | 独立帧 / 输出帧 | 时长 |
|---|---|---:|---:|---:|
| cat-side-eye | 写实动物，侧眼嫌弃质疑 | 151785 | 12 / 20 | 4000ms |
| man-snicker | 原创男性，憋笑→捂嘴偷笑→回正 | 124403 | 12 / 20 | 4000ms |
| line-exasperated | 极简双角色，端杯被逗→抓狂→无奈 | 140439 | 12 / 20 | 4000ms |
| otter-smug | 原创立体动物，抬下巴→指自己→得意 | 85388 | 12 / 20 | 4000ms |

## 检查与修订证据

- 首次编码未留透明外边，机器审计指出触边；补充角像素透明回归，RED→GREEN后恢复项目既有2像素外边标准。未放宽审计阈值。
- 原极简母版第2格杯子消失，使用内置工具修正；原稿留在 `rejected/line-exasperated-missing-cup.png`。
- 原立体角色下方文字空间偏少，使用内置工具统一调整十二格构图，非动画内缩放；原稿留在 `rejected/otter-smug-small-caption-area.png`。
- 最终四张机器审计均0问题：240×240、循环、20帧、4000ms、低于250KB、12个不同解码帧与11处有效过渡；重新读取文件验证SHA一致。
- 已检查母版与解码静帧：无字、主体可辨、四种方向不同；这不等于已独立验收播放流畅度。人物手部、猫头部和极简角色位置仍可能有帧间变化，请实际循环播放判断是否突兀。

## 状态和下一步

全部 `trial-only`、`publicationAllowed=false`，未写入正式 manifest/catalog、API 或 APK。机器通过、助手抽帧检查、用户动态认可分别记录；当前用户未逐张动态认可。

当前功能代码可以使用已有运行模板联调，但原有20张动态模板不能冒充新的广覆盖动作库。待用户指出认可/需改方向后扩展偷笑、嫌弃、无语、震惊、得意、委屈、抓狂、摆烂、挑衅、求饶、敷衍、庆祝等情绪；不自动批准或批量发布。

## 2026-09-16 用户动态认可与正式接入授权

用户本次明确反馈：“这几张可以，放进正式图库吧”。适用本批四张：cat-side-eye、man-snicker、line-exasperated、otter-smug；用户动态审核状态改为 approved，并授权以无字合成模板接入正式图库。

上文 trial-only / publicationAllowed=false 与 report.json 为制作时的历史隔离记录，不再表示缺少用户认可；实际正式接入尚待实现和核验，不伪改历史审计输出。接入须复核成品SHA、保持GIF原动作、配置文字安全区，不能沿用旧静态缩放模板生成方式重新编码成缩放动画。

2026-09-16 再次检查 Android 的 E 盘绑定目录仍返回 Input/output error，无法执行 Android 同步/构建，不以授权代替已完成接入。用户同时要求找回蘑菇头及另一形象并继续生成其他GIF；历史查到 reference-01-animated 的 mushroom-think 与 panda-really，带固定字幕，来源仍记录 user-provided-reference；这两项的具体版本及正式素材来源边界需单独确认，不借本批四图授权重写其来源。
