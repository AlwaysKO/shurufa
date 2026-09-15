# 「委屈」四风格制作记录（未完成视觉验收）

日期：2026-09-12。依据：项目 AGENTS.md、assets/expression/CREATION-POLICY.md 全部现行修订、前批 production-notes.md；跨项目记忆入口及限定检索未发现新增 GIF 规则。用户“继续/你来选”授权制作独立试稿，不授权批准、发布或改旧图。

## 当前结论

三张可供用户动态预览：写实小猎犬、黏土刺猬、原创中国成年男性。兔子未达到静态要求，需返修；本批不能声称四张合格完成。preview.html 将兔子单独放入失败稿折叠区域。静态/动态审核仍 pending，publicationAllowed=false，未加入 APK/API。

## 制作与失败证据

母版、原生成来源、提示词、逐次修订及 SHA 见 manifest.json。使用 imagegen 生成/编辑；复用既有 12 姿势、20 帧、4 秒时间线，未改审计阈值。小狗前四版循环差异门禁失败；第五版重新生成调整网格位置后通过。兔子初稿耳朵过渡不足，后续修图/重生成仍有脚部被字幕遮挡、上下位置漂移、耳朵回升过渡不足，最新稿也保留 rejected，明确 needs-rework。停止同类反复尝试，不把机器通过当视觉通过。已有 CLI 全批成功后才原子写出；失败时没有发布 output。

## 本次实际验证

- 最终 hurt01 CLI：4/4 机器审计通过，均 240×240、20 帧、4000ms、无限循环、低于 250KB。
- 字节：spaniel-hurt 92461；rabbit-hurt 105144（视觉失败）；hedgehog-hurt 92988；man-hurt 78588。
- 逐 GIF 首帧解码与 lossless WebP 像素相等 4/4；母版、来源/修订、rejected 与输出 SHA 核对通过。
- motion-evidence.png 来自最终 GIF 的第 0/5/10/13/19 帧；查看抽帧发现兔子问题，报告增加独立 visualAssessment，不篡改机器审计结果。
- referenceCharacterRenderer.test.ts + sceneRich12Renderer.test.ts：24/24 通过；npm run build 成功。未跑全仓测试。出现既有 Fontconfig 缓存版本警告，未修改环境。
- 前五批 20 张 GIF 与各自既有报告 SHA 全部一致；git diff --check 通过。
- 独立审查覆盖固定清单、CLI 分支、来源哈希和禁发布边界；抽帧问题由主流程另行发现，已请求最终状态复核。

当前无实际动态播放验收工具；抽帧不能替代动态观看，三张也只是待用户确认，不能称为用户已批准。

下一步：优先解决兔子逐格位置和字幕安全区，修图后必须重跑 CLI、重新生成抽帧证据及补回预览中的失败说明；CLI 会重建 output，不能误把重建后的机器4/4当视觉合格。
