# 六张无字 GIF 正式登记与第二批样片 实现计划

> **For Claude：** 使用 superpowers:executing-plans、test-driven-development 与 verification-before-completion；当前分支开发，不创建 worktree。

**目标：** 用户认可首批四张，并确认蘑菇头/熊猫头拥有正式产品授权；将六张无字GIF登记为正式合成模板，继续制作四种缺失情绪样片。

**架构：** 扩展既有模板源清单中的可选 `animation` 元数据，标识已完成的动作GIF，生成器预检来源、授权记录、SHA和质量后原样复制，不走旧静态缩放器。四张原创已认可原件不改；参考形象从既有无字十二姿势母版编码无字版本，保留旧带字GIF。新四张只出隔离预览，不自动加入正式目录。

**技术栈：** TypeScript/Sharp/Vitest、内置 image_gen、既有目录导入工具。

## 已确认范围
- 2026-09-16 用户“这几张可以，放进正式图库吧”批准 cat-side-eye/man-snicker/line-exasperated/otter-smug。
- 询问蘑菇头与熊猫头参考图是否拥有正式产品使用授权，用户回答“有”。来源标 licensed，依据为用户声明，不宣称独立核验法律文件，不改历史试稿原始sourceType。
- 六张进入 AI 合成无字模板池；旧带字两张原件保留，不挪作无字模板。
- Android E盘绑定目录仍 Input/output error，不修挂载、不写替代仓库，手机同步与构建待恢复。服务端生成到独立 staging 并审计，再按现有导入流程处理；不自动重启生产或推送。

## 步骤
1. 建立六张正式接入批准/来源记录；检查两张母版无字后编码，复制四张已认可原件；记录SHA。
2. assetGenerator.test.ts 新增完整动态模板保字节与时长、来源/授权/哈希/路径/排版无效预检保护旧输出用例。运行 RED，再最小实现可选 animation 分支及预检。
3. 更新 manifest.source.json 的6条模板/计数/内置清单/版本；运行生成器到独立目录，不传不可读Android路径。验证模板SHA、回退图、原成品/Emoji数量与排序。
4. 新样片：写实狗震惊、原创小鸟委屈、极简躺平摆烂、立体小动物求饶；每张4×3真实十二姿势，约4秒、240×240、无字留安全区。动态机器检查与用户视觉审核分开。
5. 定向回归、服务端构建、独立代码审查；记录Android依赖缺口、未部署项及本轮文件位置。

## 2026-09-16 执行结果
- 六张已登记正式源及本机运行目录，版本 `2026.09.16.blank-6`；6张SHA与批准记录一致，旧素材文件逐一比对未变。未部署远端、未重启服务、未发布APK。
- 原生动作模板回归52项通过（含同ID替换字节/来源偷换的先红后绿）；批准以 id/sourceType/sha256 锁定，独立代码复审通过。
- 服务端最终构建通过。全量测试507项：500通过，7项因Android挂载EIO失败；未跳过或伪造Android通过。旧静态模板裁切测试已按源类型区分，60条旧模板规格仍全部断言。
- `git diff --check -- server assets docs artifacts` 通过。
- 第二批震惊狗、委屈鸟、求饶仓鼠审计通过、待用户动态审核；摆烂豆豆两稿循环边界检查失败，明确留在试稿区。
- 正式六张预览：`artifacts/expression-character-trials/ai-synthesis-blank-release-20260916/preview.html`。
- 新稿预览及质量标记：`artifacts/expression-character-trials/ai-synthesis-blank-02/preview.html`。
- 待验收：Android目录恢复后的资源同步、APK构建及真机叠字动态发送；摆烂图继续修订；新三张用户动态审核。无Git提交或推送。
