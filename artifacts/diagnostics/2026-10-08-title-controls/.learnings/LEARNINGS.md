## LRN-20261008-TITLE

**Priority**: high
**Status**: resolved
**Area**: tests

### Summary
用户纠正群名只有“一家人”。灰色控件规则不能仅凭浅色主题的低对比度样例验收。

### Details
本机真实深色主题图中静音图标和文字同亮度，旧规则放行了群人数和图标的中文误读。缺少或不一致的字框也不应自动退回可确认标题；重复OCR仅说明一致，不能证明正确。

### Suggested Action
深浅色同亮度控件、证据缺失、旧截图回退同时回归，真实图保留本地，不写姓名硬编码映射。像素回放与真实ML Kit端到端验收分开报告。

### Metadata
- Source: user_feedback
- Related Files: WechatTitleRegion.kt, WechatScreenshotIdentity.kt
- Pattern-Key: title_ocr.control_evidence

### Resolution
最终992项通过；独立审查后要求静音铃铛形状，而非仅颜色/尺寸。前置竖线只在字符框有据且对应像素空白时删除。两张原图像素回放通过，真机OCR待验收。
