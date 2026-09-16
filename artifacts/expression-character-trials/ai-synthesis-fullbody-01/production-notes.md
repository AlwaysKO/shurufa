# 蘑菇头与熊猫头 · 全身滑稽动作试稿

日期：2026-09-16。来源：本次用户要求全身、叼烟跳舞、故意晃动、欠揍和滑稽因素。四张动作设计仅本批；长期方向写回 assets/expression/CREATION-POLICY.md 对应节。

## 来源与状态
- 用户已声明拥有两形象正式使用授权，详见 ../reference-01-animated/authorization-2026-09-16.md；本批衍生来源仍为 licensed，不声明独立核验法律合同。
- 使用内置 image_gen，以历史两张母版为角色参考生成新全身十二格母版，完整提示词保存在prompts/。
- 用既有 renderBlankGifTrial 编码；240×240、20帧、4000ms、循环、无预印文字，首次母版和纠正稿原图均本地留存。
- 蘑菇头扭胯首稿 loopClosure 失败（0.1559375 > 0.09618055555555555），保存于rejected/hip-v1；只用image_gen修复回正，不放宽审计阈值。修订稿已通过。
- 四张机器审计均无问题，详细报告与SHA见report.json。仍为trial-only，等待用户实际动态视觉确认；没有改正式图库或APK。
- 全身动作在部分格子中接近底部，正式入库前还需按最终选稿检查叠字安全区，避免遮住腿脚；不把机器报告当作排版视觉验收。

## 动态样片
- 蘑菇头·叼烟扭舞：output/mushroom-cigarette-dance.gif，77216字节。
- 熊猫头·嘚瑟晃肩：output/panda-shoulder-sway.gif，60472字节。
- 蘑菇头·滑稽扭胯：output/mushroom-hip-wiggle.gif，63065字节。
- 熊猫头·叼烟小碎步：output/panda-cigarette-strut.gif，62433字节。
