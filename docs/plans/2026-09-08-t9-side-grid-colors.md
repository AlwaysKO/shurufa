# 九宫格四格侧栏与灰白配色实现计划

> **For Claude：** 使用 executing-plans、test-driven-development、verification-before-completion。

**目标：** 保留用户的 60f 字号，左侧四格占据三行主键总高，按 APK 浅色资产修正灰白配色；不改用户自定义符号内容。

**架构：** PrefixAdapter 增加可选固定可见高度，仅 T9 容器提供；四项精确分配高度，内容继续可滚动，不影响手写/数字/展开候选默认行高。默认搜狗主题使用读取到的 #F2F3F7 背景、#FFFFFF 主键、#C5C9D3 功能键；修正左侧占位键角色和未配置时旧主题默认值，保留显式选中的其他主题。

**技术栈：** Kotlin、RecyclerView、Robolectric。

## 证据
Sogou APK assets/theme/theme_default/1080/port/9.ini：CandidateCodeView ROWS=4、COLS=1、H=0.7477，主键 H=0.24922。layout/image_list.ini：BgCode→usersym.png，KeyBg_Default→key.png，功能键→key_func.png。res/white PNG 主色采样：bg #F2F3F7、key #FFFFFF、key_func/usersym #C5C9D3。不拷贝其位图/代码，只实现尺寸与色值。

## 步骤
1. 新增 PrefixAdapter 固定高度回归，四项高度总和等于视口，放大字号不改变行高；修改对应默认配色断言，新增实际 Loader 左栏灰/主键白角色与默认主题回归，运行确认红。
2. 实现最小可选行高逻辑、T9 接线、默认搜狗配色与左栏角色；右侧 0 的现有专门分支已经设为功能键，不重复修改。
3. 定向验证侧栏/主题/实际命中区/辅助键盘，保持字号源码 60f；修正旧字号测试预期以匹配用户值。
4. Windows 构建安装，用户截图对照；没有可用截图时不宣称像素级一致。

GIF 是独立未完成问题。本轮先查实际准备文件与发送链路，不因 commitText 返回成功或旧测试通过宣称动态发送修复。
