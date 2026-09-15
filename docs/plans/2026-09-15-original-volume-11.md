# 第十一批原创 GIF 实现计划

> **For Claude：** 使用 superpowers:executing-plans 逐任务执行。
**目标：** 自主制作「不听 / 懂了 / 紧张」各四种风格，共12张独立试稿，本地提交不推送。
**架构：** 沿用固定清单、源图追溯和原子渲染；不改节奏与质量阈值，不触碰旧素材及并行业务。
**技术栈：** imagegen、TypeScript、sharp、Vitest。

## 设计与取舍
用户已授权自主选题和连续批量生产。本轮选择新聊天意图，不修旧失败项，也不靠同义换字幕堆数量。
不听：梗犬转脸闭眼；极简鼠捂耳；黏土水獭侧目捂耳；原创中国成年男性拒听。
懂了：暹罗猫抬眉轻点头；极简猫头鹰由眯眼转恍然；黏土浣熊眉眼松开配点头；成年男性理解后轻抬食指。
紧张：写实垂耳犬左右偷瞄抿嘴；极简企鹅双翼贴胸；黏土仓鼠双爪攥紧；成年男性咬唇握拳。
各项12连续姿势，峰值在第7、8格，后4格缓慢回正。源格主体紧凑留白，固定相机。显式绝对坐标上批失败，不视作解决漂移的规则。
先一张源图及真实GIF起始/峰值/回正检查后扩大。技术合格不等同语义、动态通过。

## 任务1：固定候选清单 TDD
修改 server/src/expression/referenceCharacterRenderer.test.ts：沿既有测试添加12个固定id/字幕/来源门禁，运行 npx vitest run src/expression/referenceCharacterRenderer.test.ts -t volume11，预期未知候选失败。
最小修改 server/src/expression/referenceCharacterRenderer.ts：追加 ORIGINAL_VOLUME11_ITEMS 和验证白名单；server/scripts/render-reference-characters.ts：增加 volume11 固定路由、输出根和双待审状态。
重跑同测试预期通过；不重构其他批次。

## 任务2：图像生成与试片
创建 artifacts/expression-character-trials/original-volume-11-animated/masters/，保留完整提示词、生成路径和SHA；人物注明中国/男/成年，禁止IP与真人相似。
先生成梗犬、原renderer试渲染并检查真实GIF抽帧，再生成余11项。透明通道单独记录。
失败只用imagegen修当前新源图，旧版本留rejected。最多有限修订；反复失败则隔离needs-rework，不放宽审计。

## 任务3：复验与提交
server下 npx tsx scripts/render-reference-characters.ts volume11 原子输出；所有机器通过才标准CLI成功。
重算实际GIF尺寸240、pageHeight240、20帧、4000ms、loop0、<250KB、SHA、首帧WebP像素；核对源链与此前141张GIF哈希。
运行 npx vitest run src/expression/referenceCharacterRenderer.test.ts src/expression/prototypeAudit.test.ts 和 npm run build。
输出实际GIF抽帧、visual-review、独立预览、production-notes与verification。审查代码和静态证据，不能冒充实际播放。
机器/视觉待返工/人工pending分别如实记录。只提交本批目录、计划和三个清单代码文件，不push、不入APK/API。

## 执行中规则更新与结果
制作期间重新读取当前AGENTS.md和CREATION-POLICY.md，新增Git素材归档边界优先于原计划的笼统“提交本批目录”：只入最终导出GIF与必要代码/文字/清单。母图、帧图、预览、回退试图和废稿仅本地，不用add -f。未执行历史重置或push。
12初稿、8次源图修订；最终12/12机器通过，11候选+暹罗猫1待返工。相关测试41/41、build退出0；此前141GIF哈希不变。详见本批production-notes.md和verification.json。
