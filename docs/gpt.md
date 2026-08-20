我想用ai帮我做一个手机用的输入法软件，模仿搜狗输入法，就我自己用，这个好实现吗？我不发布应用市场

可以，而且如果目标是**“自己用、不上应用市场、做一个类似搜狗输入法但只保留自己需要的功能”**，我认为非常适合用 AI 辅助开发。

关键区别在于你用 **Android 还是 iPhone**。

### 如果你是 Android：非常适合做

Android 原生就提供了完整的 IME（Input Method Editor）框架。输入法本质上也是一个 Android App，只需要实现 `InputMethodService`，安装 APK 后，在系统设置里启用，就可以在微信、浏览器、记事本等其他 App 中作为系统输入法使用。Android 官方 API 本身就支持键盘界面、候选词区域、拼写组合、向当前输入框提交文字等能力。([Android Developers][1])

所以完全可以做成：

> 键盘
> ↓
> 拼音输入
> ↓
> 候选词
> ↓
> AI 联想 / AI 改写
> ↓
> 输入到微信、浏览器、邮件等 App

而且因为你**不发布应用市场**，可以直接编译 APK 安装到自己的 Android 手机上。

---

### 真正难的不是“键盘”，而是“搜狗”

如果只是做：

* 26 键
* 数字 / 符号
* 中英文切换
* 删除 / 回车 / 空格
* 拼音输入
* 候选词
* 常用词学习
* AI 润色
* AI 续写
* AI 翻译
* 自定义快捷短语
* 剪贴板

这些都完全现实。

但如果目标是：

> “做到跟搜狗输入法的中文输入体验差不多”

难度就会大很多。

因为搜狗真正厉害的并不是键盘 UI，而是背后的：

**拼音 → 汉字转换模型 + 超大词库 + 候选词排序 + 用户词频学习 + 云词库 + 上下文预测。**

比如：

```text
输入：
wojintianxiangquchifan

普通实现：
我 今天 想 去 吃饭

成熟输入法：
我今天想去吃饭
```

再例如：

```text
输入：
woqubj

候选：
1. 我去北京
2. 我去吧
3. 我去北京了
```

这种候选排序才是输入法比较有技术含量的部分。

不过你自己用的话，**完全没必要自己从零实现这个引擎**。比较合理的架构是：

```text
┌────────────────────────┐
│       我的输入法        │
├────────────────────────┤
│ 键盘 UI                 │
│ Q W E R T Y ...         │
├────────────────────────┤
│ 候选词                  │
│ 我今天 | 我今天去 | ... │
├────────────────────────┤
│ 拼音输入引擎            │
│ 拼音 → 汉字             │
├────────────────────────┤
│ 本地词库                 │
├────────────────────────┤
│ 用户词库                 │
├────────────────────────┤
│ AI                      │
│ 润色 / 翻译 / 续写      │
└────────────────────────┘
```

拼音转换部分可以直接接成熟的开源输入法引擎，而你主要开发自己的 UI 和 AI 功能，这样项目难度会下降非常多。

### AI 反而是这个输入法最好玩的地方

例如键盘候选栏可以设计成：

```text
┌─────────────────────────────┐
│ 我今天 | 我觉得 | 我们可以   │
├─────────────────────────────┤
│ ✨润色   🌐翻译   📝续写     │
├─────────────────────────────┤
│ Q W E R T Y U I O P          │
│  A S D F G H J K L           │
│   Z X C V B N M              │
│ 🌐 123      空格       ←     │
└─────────────────────────────┘
```

比如你输入：

```text
这个方案我觉得不太行，你重新改一下
```

点：

**「商务」**

AI 返回：

```text
这个方案目前可能还存在一些需要调整的地方，
麻烦根据上述意见再优化一下。
```

或者点：

**「English」**

直接：

```text
I think this proposal still needs some adjustments.
Could you please revise it based on the feedback above?
```

这部分实现其实比拼音输入引擎简单很多。你可以把 AI 调用设计成**必须手动点击按钮才发送文字**，而不是把所有键盘输入都上传服务器，这样隐私风险也小很多。

---

## 如果你是 iPhone：也能做，但麻烦一些

Apple 官方允许做 **Custom Keyboard Extension**。安装后可以在系统设置里启用，然后在支持第三方键盘的 App 里使用。([Apple Developer][2])

如果 AI 功能需要联网，需要为 Keyboard Extension 配置 `RequestsOpenAccess`。([Apple Developer][2])

但 iOS 有一些系统限制。例如密码输入框一定会切回 Apple 系统键盘；某些电话号码输入框也不会允许第三方键盘。([Apple Developer][3])

另外，“不发布 App Store”在 iPhone 上并不等于随便装。你可以通过 Xcode 把自己开发的 App 安装到自己的 iPhone，但如果只是免费的 Personal Team，Apple 当前规定相关 provisioning profile **7 天会过期，需要重新构建安装**。([Apple Developer][4])

所以从“自己长期使用”的角度，我会明显更推荐：

**Android > iPhone**

---

## 我会建议你不要一开始就“复刻搜狗”

第一版做成下面这样就非常实用了：

| 功能     | 建议   |
| ------ | ---- |
| 26键键盘  | ✅    |
| 中文拼音   | ✅    |
| 拼音候选词  | ✅    |
| 中英文切换  | ✅    |
| 数字/符号  | ✅    |
| 用户词库   | ✅    |
| 自定义短语  | ✅    |
| 剪贴板    | ✅    |
| AI 润色  | ✅    |
| AI 续写  | ✅    |
| AI 翻译  | ✅    |
| AI 回复  | ✅    |
| 语音输入   | 后面加  |
| 手写输入   | 暂时不要 |
| 搜狗级云词库 | 暂时不要 |
| 皮肤商城   | 完全不要 |
| 表情包系统  | 完全不要 |

这样实际上已经能覆盖你个人使用的大部分需求。

而且你甚至可以做一些搜狗没有、但对你自己特别有价值的东西，比如：

```text
[工作]
客户报价
回复客户
Google Ads
技术回复
邮件润色

[日常]
普通聊天
简短回复
英文翻译
繁体转换
```

AI 可以根据不同按钮使用不同 Prompt。

---

### 技术上我会这样选

如果是 **Android**，我推荐：

```text
语言：
Kotlin

输入法框架：
InputMethodService

UI：
Android View / Compose

中文输入：
成熟开源拼音引擎

数据：
Room / SQLite

用户设置：
DataStore

AI：
OpenAI API
   ↓
你自己的服务器
   ↓
输入法

本地：
用户词库
快捷短语
输入历史
AI Prompt
```

其中 AI 最好不要：

```text
Keyboard → OpenAI
```

而是：

```text
Keyboard
   ↓
你的 API
   ↓
OpenAI
```

这样 API Key 不需要直接塞进 APK。

---

**所以我的结论是：如果你用的是 Android，这个项目非常值得做，而且 AI 完全可以帮你把绝大部分代码写出来。** 不需要真的造一个搜狗输入法，而是做成一个“**搜狗基础输入能力 + 你自己的 AI 工作流**”的私人输入法，实际可行性很高。

如果你的手机是 **Android**，我可以直接从零给你设计这个项目的**目录结构、技术方案、第一版界面和可运行的 Kotlin 输入法代码**，从一个能在手机系统里真正切换出来的输入法开始。

[1]: https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method?utm_source=chatgpt.com "Create an input method | Views | Android Developers"
[2]: https://developer.apple.com/documentation/uikit/creating-a-custom-keyboard?utm_source=chatgpt.com "Creating a custom keyboard | Apple Developer Documentation"
[3]: https://developer.apple.com/documentation/uikit/configuring-a-custom-keyboard-interface?utm_source=chatgpt.com "Configuring a custom keyboard interface | Apple Developer Documentation"
[4]: https://developer.apple.com/help/account/basics/about-your-developer-account?utm_source=chatgpt.com "Developer account overview - Basics - Account - Help - Apple Developer"


有成熟的开源项目吧？可以在此基础上开发，你帮我找找

我查了一圈。**有，而且比我刚才预想的选择还多。** 如果你的目标是“像搜狗一样正常中文输入 + 自己加 AI 功能”，我建议重点看下面 4 个。

| 项目                     |  中文输入 | UI 完整度 |  二开难度 | AI 改造价值 | 我的建议          |
| ---------------------- | ----: | -----: | ----: | ------: | ------------- |
| **Fcitx5 for Android** | ★★★★★ |  ★★★★☆ | ★★★☆☆ |   ★★★★★ | **首选底座**      |
| **Trime / 同文输入法**      | ★★★★★ |  ★★★★☆ | ★★★☆☆ |   ★★★★☆ | 第二选择          |
| **语燕输入法 YuyanIme**     | ★★★★★ |  ★★★★★ | ★★★☆☆ |   ★★★★☆ | UI 很值得参考      |
| **Vertick IME**        | ★★★★☆ |  ★★★★☆ | ★★★☆☆ |   ★★★★★ | **AI 功能参考首选** |

### 1. Fcitx5 for Android —— 我目前最推荐

GitHub 项目名：

`fcitx5-android/fcitx5-android`

它不是简单的键盘 Demo，而是把完整的 **Fcitx5 输入法框架和输入引擎移植到了 Android**。中文已经内置支持拼音、双拼、五笔、仓颉和自定义码表，而且还有候选词展开、剪贴板、主题、Emoji、符号键盘、长按键等功能。项目本身还有 Plugin System。 ([GitHub][1])

它的中文拼音来自成熟的 `fcitx5-chinese-addons`，里面已经有候选词、用户历史、模糊拼音、自定义短语、Cloud Pinyin 等成熟功能，不需要我们自己重新写“拼音 → 中文”。 ([GitHub][2])

代码层面是 Android/Kotlin + Native C/C++，项目当前构建里直接包含 `fcitx5`、`libime`、`fcitx5-chinese-addons` 等核心组件。 ([GitHub][3])

而且官方甚至有扩展 Addon 的开发方式。 ([GitHub][4])

对你的项目，我可以直接在它上面加：

```text
┌────────────────────────────┐
│ 你好 │ 你好啊 │ 你好，请问  │ ← 原有候选
├────────────────────────────┤
│ ✨润色 │ 🌐翻译 │ 💬回复 │ AI │ ← 我们新增
├────────────────────────────┤
│ Q W E R T Y U I O P        │
│  A S D F G H J K L         │
│   Z X C V B N M            │
│ 中/英       空格       ←    │
└────────────────────────────┘
```

**这一套比较符合软件工程上的长期二开。**

---

### 2. Trime / 同文输入法 —— 中文生态非常成熟

项目：

`osfans/trime`

这是非常成熟的 Android Rime 前端，底层直接使用 `librime`，Java/Kotlin + JNI。Rime 本身支持各种拼音、双拼、方言、五笔、自定义输入方案等。 ([GitHub][5])

Rime 最大的优势就是：

**词库和输入方案生态特别强。**

比如：

```text
雾凇拼音
白霜拼音
小鹤双拼
自然码
搜狗词库转换
自定义词库
用户词频
```

这种东西 Rime 社区已经积累很多。

`librime` 本身采用比较宽松的 BSD 3-Clause License，但 **Trime Android App 本身是 GPL v3**。你只是自己修改自己使用问题不大；如果以后要发布，就要认真遵守 GPL 的分发要求。 ([GitHub][6])

如果你的重点是：

> **“我要一个特别强的中文输入法，然后往上面叠 AI。”**

Trime 非常合适。

---

### 3. 语燕输入法 YuyanIme —— “长得最像我们想做的东西”

这个项目很值得你看看：

`gurecn/YuyanIme`

它本身就是：

> **基于 Rime 二次开发的现代 Android 中文输入法**

而且功能非常接近搜狗/微信输入法：

* 九宫格拼音
* 26 键全拼
* 小鹤双拼
* 自然码
* 搜狗双拼
* 中英文混输
* Emoji
* 数字行
* 剪贴板
* 常用语
* 单手键盘
* 悬浮键盘
* 深色主题
* 自定义菜单栏
* 自定义主题

官方 README 就列出了这些能力。 ([GitHub][7])

并且它**目前仍然比较活跃**。GitHub 当前显示项目约 3.6k stars，近期仍有提交；最近列出的正式版本是 **v20260530.10，于 2026 年 5 月 29 日发布**。 ([GitHub][8])

它的界面和设计思路，其实比 Fcitx5 更接近：

**“国产主流手机输入法”。**

不过这里有一个重要问题。

它现在把输入核心拆成：

`YuyanIme`
+
`yuyansdk`

而 `yuyansdk` 当前 README 明确说明，仓库里没有包含作者自己修改优化后的完整 librime 核心源代码，提供的某些预编译核心库存在包名/授权相关技术限制。 ([GitHub][9])

所以我的判断是：

> **YuyanIme 非常值得抄它的 UI、交互和功能设计，但我暂时不会把它作为我们最终项目最底层的核心。**

否则以后改 applicationId、彻底重构输入引擎的时候，可能会给自己埋坑。

---

### 4. Vertick IME —— 这个项目和你的想法几乎撞车了

我还找到一个非常有意思的新项目：

`BurgerK1ng16/Vertick-IME`

它直接定位成：

> **“开源的语音、离线拼音与智能润色输入法”**

已经做了：

* 离线拼音
* Rime-Ice / 雾凇相关词库
* 本地学习
* 英文候选
* 语音输入
* AI 文本润色
* AI 问答
* 剪贴板
* 可配置 AI Model Provider

也就是说：

```text
普通输入法
+
Rime
+
AI
+
语音
```

**跟你刚才描述的方向几乎一样。** ([GitHub][10])

但是它有一个明显问题：

**非常新。**

GitHub 显示它大约是最近一个月才首次开源，而且 README 自己也写明项目处于快速迭代阶段，并且当前针对 Android 15+。所以我不会现在就把我们的项目完全建立在它上面。 ([GitHub][10])

但是：

> **AI 功能怎么跟输入法结合，非常值得参考这个项目。**

---

## 所以我现在会这样选

如果这是我们俩真正准备开始做的项目，我建议不是简单地 Fork 一个项目改名字，而是：

```text
             我们的私人 AI 输入法
                      │
        ┌─────────────┼─────────────┐
        ↓             ↓             ↓
   输入法底层       UI设计参考      AI设计参考
        │             │             │
 Fcitx5 Android    YuyanIme      Vertick IME
        │
        ↓
 fcitx5-chinese-addons
        │
        ↓
     拼音/双拼
     用户词库
     候选排序
```

### 我的第一方案

**底座：Fcitx5 for Android**

保留：

```text
拼音引擎
候选词
用户词库
Emoji
符号
剪贴板
中英文
输入法 Service
```

然后把 UI 往：

**搜狗 / 微信输入法 / 语燕**

这个方向修改。

再增加：

```text
AI Toolbar

✨ 润色
🌐 翻译
💬 回复
📝 续写
💼 商务化
✂ 精简
😊 更友好
🤖 自定义 Prompt
```

AI 部分参考 Vertick，但是我们自己实现。

这样架构最干净。

---

## 甚至可以做到这种使用体验

例如你在微信输入：

```text
你这个报价太贵了能不能便宜一点
```

不直接发送。

按：

**「商务」**

变成：

> 感谢您的报价。目前这个价格略高于我们的预算，请问价格方面是否还有进一步调整的空间？

或者输入：

```text
订单什么时候发货
```

按：

**「English」**

直接出现：

> When will the order be shipped?

再点：

**「插入」**

放回微信输入框。

这才是我觉得我们这个私人输入法真正值得做的地方。

---

**所以目前我的建议非常明确：先 Fork `fcitx5-android/fcitx5-android`，不要从零做。** 我下一步可以继续帮你把 **Fcitx5 Android 的源码结构扒开**，具体告诉你：

**键盘 UI 在哪里、候选栏在哪里、按键事件在哪里、文字提交在哪里，以及“AI 工具栏”最适合插到哪一层。**

这样我们就可以真正开始改代码了。

[1]: https://github.com/fcitx5-android/fcitx5-android/blob/master/README.md?utm_source=chatgpt.com "fcitx5-android/README.md at master · fcitx5-android/fcitx5-android · GitHub"
[2]: https://github.com/fcitx/fcitx5-chinese-addons/blob/master/po/fcitx5-chinese-addons.pot?utm_source=chatgpt.com "fcitx5-chinese-addons/po/fcitx5-chinese-addons.pot at master · fcitx/fcitx5-chinese-addons · GitHub"
[3]: https://github.com/fcitx5-android/fcitx5-android/blob/master/app/build.gradle.kts?utm_source=chatgpt.com "fcitx5-android/app/build.gradle.kts at master · fcitx5-android/fcitx5-android · GitHub"
[4]: https://github.com/fcitx5-android/fcitx5-android/wiki/Create-a-fcitx-addon?utm_source=chatgpt.com "Create a fcitx addon · fcitx5-android/fcitx5-android Wiki · GitHub"
[5]: https://github.com/osfans/trime/blob/develop/README.md?utm_source=chatgpt.com "trime/README.md at develop · osfans/trime · GitHub"
[6]: https://github.com/rime/librime/blob/master/LICENSE?utm_source=chatgpt.com "librime/LICENSE at master · rime/librime · GitHub"
[7]: https://github.com/gurecn/YuyanIme/blob/master/README.md?utm_source=chatgpt.com "YuyanIme/README.md at master · gurecn/YuyanIme · GitHub"
[8]: https://github.com/gurecn/YuyanIme/tree/master?utm_source=chatgpt.com "GitHub - gurecn/YuyanIme: 语燕输入法-一款基于Rime定制开发的九键、全拼、双拼、手写、火星文等方案、支持悬浮、单手、数字行等键盘模式的中文输入法 · GitHub"
[9]: https://github.com/gurecn/yuyansdk?utm_source=chatgpt.com "GitHub - gurecn/yuyansdk: 语燕输入法引擎-一款基于Rime定制开发的九键、全拼、双拼、手写、火星文等方案、支持悬浮、单手、数字行等模式的中文输入法引擎 · GitHub"
[10]: https://github.com/BurgerK1ng16/Vertick-IME?utm_source=chatgpt.com "GitHub - BurgerK1ng16/Vertick-IME: Open-source voice, offline Pinyin and intelligent polishing input method开源的语音、离线拼音与智能润色输入法 · GitHub"

为什么你推荐fcitx5-android  ，我更倾向于语燕输入法  

你倾向**语燕输入法**的话，我反而建议我们就以语燕为主。重新结合你的真实目标——**Android、自用、不上架、想接近搜狗的体验，再增加 AI**——语燕可能比 Fcitx5-Android 更合适。

我之前推荐 Fcitx5-Android，主要是站在“底层工程”的角度。它是真正把 Fcitx5 框架和输入引擎完整移植到 Android，拼音、双拼、五笔、仓颉、Rime Plugin、候选词、剪贴板、主题、插件系统等都已经具备，而且底层和 Android UI 的层次比较清楚。项目采用 LGPL-2.1，输入引擎也没有语燕后面要讲的包名绑定问题。([GitHub][1])

但是它有个对我们非常现实的缺点：**它更像一个“输入法框架”，而不是一款“中国 Android 用户习惯的成品输入法”。** 甚至它目前 README 还明确把“Customizable keyboard layout”列在 Planned Features 里。([GitHub][1])

而语燕已经把大量我们原本需要自己做的东西做好了。

比如它现在已经有全拼 26 键、九宫格、搜狗双拼等多种双拼、手写、英文联想、数字行、候选词、Emoji、剪贴板、常用语、自定义菜单栏、主题、单手键盘、悬浮键盘、深色模式等等。([GitHub][2])

这恰好就是你的目标：

**不是研究输入法框架，而是拿一个已经很好用的中文输入法，加 AI。**

所以按照我们的需求重新评分，我会改成：

| 项目           | Fcitx5 Android |        语燕 |
| ------------ | -------------: | --------: |
| 中文输入成熟度      |          ★★★★★ |     ★★★★★ |
| 接近搜狗使用习惯     |            ★★★ | **★★★★★** |
| 26键拼音        |          ★★★★★ |     ★★★★★ |
| 九宫格          |             一般 | **★★★★★** |
| UI 完成度       |           ★★★★ | **★★★★★** |
| 自定义菜单栏       |             一般 |    **已有** |
| 剪贴板          |             已有 |        已有 |
| 常用语          |           需要扩展 |    **已有** |
| 单手键盘         |           需要开发 |    **已有** |
| 悬浮键盘         |           部分能力 |    **已有** |
| 手写           |          需其他方案 |    **已有** |
| 加 AI UI 的工作量 |              中 |     **低** |
| 底层完全可控       |      **★★★★★** |       ★★★ |
| 适合我们现在的项目    |           ★★★★ | **★★★★★** |

而且语燕不是一个已经没人维护的老项目。GitHub 当前约 3.6k stars，2026 年仍然有版本更新；目前 GitHub Releases 显示最新正式版本 `v20260530.10` 是 **2026 年 5 月 29 日**发布的。([GitHub][3])

所以**从产品完成度来看，我现在更支持你选语燕。**

不过，有一个问题我们必须提前知道。

## 语燕最大的坑不是 UI，而是 yuyansdk

语燕现在大致可以理解成：

```text
YuyanIme
│
├── Android 输入法 App
│   ├── UI
│   ├── 设置
│   ├── 键盘
│   ├── 菜单
│   └── InputMethodService
│
└── yuyansdk
    │
    ├── 输入法上层接口
    └── libyuyanime.so
          ↓
       修改版 Rime
```

YuyanIme 主仓库和 yuyansdk 的公开代码是 BSD 3-Clause，这其实非常宽松。([GitHub][2])

但是作者在 yuyansdk README 中明确说明：

**预编译的 `libyuyanime.so` 不属于 BSD 开源部分。**

而且没有商业授权时，这个二进制库存在**包名绑定**：

> 修改 Package Name 会导致核心验证失败。

同时，作者优化过的 librime 核心源码并没有完整公开在仓库里；公开的是上层框架和接口。如果想完全摆脱这个限制，要么自己基于上游 librime 构建核心，要么获得作者授权。([GitHub][4])

这就是我之前为什么对语燕稍微保守了一点。

---

## 但对你来说，这个问题其实没那么严重

因为你明确说：

> **我自己使用，不上应用市场。**

那第一阶段完全可以：

```text
Fork YuyanIme
       ↓
保持原 Package Name
       ↓
修改 App 名称 / Logo / UI
       ↓
增加 AI 功能
       ↓
自己编译 APK
       ↓
安装自己手机
```

只不过手机上不能同时安装官方语燕和我们修改的语燕，因为 package name 相同。

但你本来就是要自己做一个输入法来用，这几乎没影响。

所以我们**没必要一开始为了所谓“架构完美”就跑去重写输入引擎**。

---

# 我现在更推荐这样的路线

直接把：

**语燕 → 你的私人 AI 输入法**

第一阶段甚至不要动 Rime。

把输入法核心当成黑盒：

```text
                语燕现有能力
                     │
      ┌──────────────┴──────────────┐
      ↓                              ↓
    Rime                           UI
      ↓                              ↓
 拼音/词库                      26键/九宫格
 候选排序                      候选栏
 用户词频                      工具栏
      │                              │
      └──────────────┬──────────────┘
                     ↓
               我们增加 AI
```

最开始只增加：

```text
┌──────────────────────────────┐
│ 我今天 │ 我觉得 │ 我们可以   │
├──────────────────────────────┤
│ ✨AI  🌐翻译  💼商务  ✂精简 │
├──────────────────────────────┤
│ Q W E R T Y U I O P          │
│  A S D F G H J K L           │
│   Z X C V B N M              │
│ 中/英       空格       ←      │
└──────────────────────────────┘
```

比如：

```text
这个价格太贵了，你能不能便宜点
```

选择这句话，点：

**💼 商务**

AI 返回：

```text
感谢您的报价。目前这个价格略高于我们的预算，
请问价格方面是否还有进一步调整的空间？
```

然后：

```text
[替换原文]    [插入]
```

这样第一版已经非常有用了。

---

## 而且语燕有个地方特别适合我们

它自己已经支持：

**自定义菜单栏。** ([GitHub][2])

所以我们甚至可以把 AI 入口自然地塞进原来的 Toolbar：

```text
现在：

剪贴板 | 常用语 | 设置 | Emoji

↓

我们：

AI | 剪贴板 | 常用语 | 翻译 | 设置
```

点击 AI：

```text
┌────────────────────────────┐
│ AI 助手                    │
├────────────────────────────┤
│ ✨ 润色                    │
│ 🇺🇸 翻译英文               │
│ 🇨🇳 翻译中文               │
│ 💼 商务表达                │
│ ✂️ 精简                    │
│ 😊 更友好                  │
│ 💬 帮我回复                │
│ ⚙️ 自定义 Prompt           │
└────────────────────────────┘
```

**这个开发路线比基于 Fcitx5 做舒服得多。**

因为我们不是先花大量时间：

> “怎么让这个输入法变得像搜狗？”

而是一开始它就已经比较像了。

---

## 长期再解决 yuyansdk

如果这个输入法你用了半年以后觉得：

> “这个东西很好，我准备长期维护。”

那时候再做第二阶段：

```text
YuyanIme UI
     │
     ├── 我们自己的 AI
     │
     ├── 我们自己的设置
     │
     └── Rime Adapter
              ↓
        官方 librime
              ↓
        rime-ice / 白霜
```

也就是把：

```text
libyuyanime.so
```

慢慢换掉。

因为官方 `librime` 本身就是 BSD 3-Clause，语燕作者的 README 也明确指出可以自己从上游 librime 构建不受包名限制的核心。([GitHub][4])

这样最终我们得到：

```text
你的 AI 输入法
│
├── 语燕优秀的 Android UI
├── 我们自己的功能
├── 我们自己的 AI
├── 上游 Rime
├── 雾凇拼音
└── 完全属于自己的 Package Name
```

那就彻底自由了。

**所以，如果现在让我重新选一次：你的项目我会选语燕，而不是 Fcitx5-Android。**

Fcitx5 是“做输入法平台”更好的基础；**语燕是“做一个自己真正每天使用、接近搜狗体验的 AI 中文输入法”更好的基础。**

下一步最有价值的事情，是我直接帮你**分析 YuyanIme 的源码目录**，找出四个位置：`InputMethodService`、键盘 Toolbar、候选栏、文字上屏/读取输入框的代码。把这四处找到，我们基本就知道 **AI 功能具体应该加在哪里** 了。

[1]: https://github.com/fcitx5-android/fcitx5-android "GitHub - fcitx5-android/fcitx5-android: Fcitx5 input method framework and engines ported to Android · GitHub"
[2]: https://github.com/gurecn/YuyanIme "GitHub - gurecn/YuyanIme: 语燕输入法-一款基于Rime定制开发的九键、全拼、双拼、手写、火星文等方案、支持悬浮、单手、数字行等键盘模式的中文输入法 · GitHub"
[3]: https://github.com/gurecn/YuyanIme/releases?utm_source=chatgpt.com "Releases · gurecn/YuyanIme"
[4]: https://github.com/gurecn/yuyansdk/tree/master "GitHub - gurecn/yuyansdk: 语燕输入法引擎-一款基于Rime定制开发的九键、全拼、双拼、手写、火星文等方案、支持悬浮、单手、数字行等模式的中文输入法引擎 · GitHub"


我看中语燕 ，只是因为它的功能比较完善，不需要我过多的开发。你说的换libyuyanime.so，用好换吗？我最开始想要的不是ai功能，这个后面再加。我想要的是能够记住我的所有输入，包括复制粘贴，把数据上传到后端，然后形成一个个人输入习惯，可以根据用户的高频输入习惯补全。所以要有自己的后端和数据库，还有数据分析。然后我还想做几个功能：1.我需要输入文字，能够出现相应的表情包，供我斗图用；2.能够语音输入，然后转换成文字；3.能够随意更换皮肤，因为搜狗的皮肤要收钱；

对，你现在这个目标一明确，我对整个项目的判断就变了：

**语燕非常适合你，而且现阶段完全没必要换 `libyuyanime.so`。**

你真正想做的是一个“**个人数据驱动的私人输入法**”，Rime/`libyuyanime.so` 负责的只是最底层的“拼音怎么变成汉字、候选词怎么产生”。你列出来的 4 大类功能，基本全部可以在它上面再加一层完成。

---

## 先回答你最关心的：`libyuyanime.so` 好换吗？

**不好直接换，但能换。**

它不是：

```text
删掉 libyuyanime.so
↓
放进去 librime.so
↓
重新编译
↓
完成
```

这么简单。

语燕作者已经明确说明，仓库公开的是**上层调用框架和接口层**，而他自己优化修改后的 librime 核心源码并没有公开；官方给出的合法替代方案也是“自己基于上游 librime 重新构建核心”。([GitHub][1])

也就是说，如果以后真换，我们实际上要做：

```text
YuyanIme
   ↓
yuyansdk 现有 Kotlin/Java API
   ↓
【我们自己写 JNI Adapter】
   ↓
官方 librime
   ↓
Rime Schema / Dictionary
```

麻烦的地方不是编译出一个 `librime.so`，而是要让它**完全兼容语燕当前期待的接口和行为**。

特别是语燕自己在 Rime 上做过九宫格拼音选择、超级简拼、词语联想等优化。([GitHub][1])

所以我给替换难度大概：

**7/10。**

能做，但现在做属于：

> 花很多时间解决一个目前根本没有影响你需求的问题。

你只是自己使用的话，当前 `libyuyanime.so` 可以继续用，只要保持它要求的 Package Name 即可。作者明确说明，未商业授权情况下，预编译 `.so` 有包名绑定；改 Package Name 会导致核心初始化失败。([GitHub][1])

---

# 更重要的是：你的核心功能根本不依赖换 `.so`

我现在会把整个项目设计成这样：

```text
                 你的私人输入法
                        │
        ┌───────────────┼────────────────┐
        ↓               ↓                ↓
    语燕现有UI       个人数据层        扩展功能层
        │               │                │
  键盘/候选/设置     输入记录          表情包
  九宫格/双拼       剪贴板记录        语音
  剪贴板/主题       本地数据库        皮肤
        │               │                │
        └───────┬───────┴────────┬───────┘
                ↓                ↓
        libyuyanime.so         后端 API
                │                │
              Rime           PostgreSQL
                              数据分析
                              用户模型
                              表情包库
```

**把 Rime 当黑盒。**

这样最好。

---

# 你真正的第一核心功能：记录自己的输入习惯

这个我非常支持，而且技术上可行。

但我建议不要做成传统意义上的：

> “记录每一次按键的 KeyLogger”

而是记录**最终产生的输入行为**。

例如你输入：

```text
wo jue de zhe ge
```

没有必要记录：

```text
w
o
j
u
e
d
e
...
```

我们应该记录：

```json
{
  "type": "commit",
  "text": "我觉得这个",
  "timestamp": "...",
  "app": "com.tencent.mm"
}
```

因为 Android IME 本来就是通过 `InputConnection.commitText()` 等接口把文字提交到当前 App，所以我们控制输入法之后，完全可以在自己的提交链路上增加记录层。Android 官方也提供了 `getTextBeforeCursor()`、`getTextAfterCursor()`、`commitText()` 等 IME 接口。([Android Developers][2])

于是你的输入历史就可以变成：

```text
08:13 微信
你好，请问这个产品什么时候可以发货

09:26 邮件
Please let me know if you have any questions.

10:15 微信
好的，没问题

10:17 微信
好的，没问题

11:45 微信
好的，没问题
```

后端慢慢就知道：

```text
你的高频短语：

好的，没问题              328 次
麻烦帮我确认一下          186 次
请问什么时候可以发货      143 次
Please let me know       96 次
我这边确认一下            91 次
```

这才是你真正需要的数据。

---

# 然后做“私人补全”

这里甚至**不需要 AI**。

例如你经常输入：

```text
麻烦帮我确认一下这个订单什么时候可以发货
```

后台发现：

```text
"麻烦" →
麻烦帮我确认一下这个订单什么时候可以发货
75 次
```

以后输入：

```text
麻烦
```

候选栏：

```text
┌─────────────────────────────────┐
│ 麻烦帮我确认一下 │ 麻烦看一下 │
├─────────────────────────────────┤
│ q w e r t y u i o p             │
```

输入：

```text
麻烦帮
```

直接：

```text
麻烦帮我确认一下这个订单什么时候可以发货
```

这其实就是：

**Personal Autocomplete Engine**

第一阶段甚至一个 Trie + 频率模型就够了。

比如评分：

```text
候选分数 =
使用次数
× 最近使用权重
× 当前 App 权重
× 当前时间权重
```

这样还能学出很有意思的东西：

```text
微信：
好的
收到
没问题

Gmail：
Thank you for your email.
Please let me know...
Best regards,

工作 App：
麻烦确认一下
这个问题处理了吗
已经更新
```

所以甚至可以做到：

**不同 App，不同语言习惯。**

---

# 我建议后端不要实时查询

这点非常重要。

不要：

```text
每输入一个字符
↓
请求服务器
↓
返回候选词
```

否则网络一差，输入法就废了。

应该：

```text
手机
│
├── 本地 Room 数据库
│       ↓
│   高频词
│   高频句
│   Prefix Trie
│   下一句预测
│
├── 上传输入记录
│
↓
后端
│
├── 数据分析
├── 高频统计
├── Pattern Mining
├── Prefix 模型
└── 个性化词库
        ↓
     定期同步
        ↓
手机本地
```

所以输入的时候：

**100% 本地完成补全。**

联网只负责：

**同步和训练。**

这样体验才会好。

---

# 数据库我会设计两层

第一层是原始事件：

```text
input_event

id
device_id
timestamp
package_name
event_type
text
context_before
input_mode
```

`event_type`：

```text
typed
candidate
clipboard
paste
voice
shortcut
```

第二层是分析结果：

```text
phrase_stat

phrase
count
last_used_at
app
score
```

再加：

```text
completion_stat

prefix           completion
麻烦              麻烦帮我确认一下
麻烦帮            麻烦帮我确认一下
好的              好的，没问题
please            please let me know
```

后面数据多了，再升级算法。

完全不需要一开始上 LLM。

---

# 复制粘贴也可以记录

这个反而语燕已经帮我们打好了基础，因为它自己就已经有：

**剪贴板历史 + 剪贴板联想。** ([GitHub][1])

Android 10 以后普通后台 App 不能随意读取剪贴板，但是**默认输入法是明确的例外之一**。([Android Developers][3])

所以：

```text
复制：

https://xxx.com/product/123
↓
ClipboardManager
↓
输入法监听
↓
本地记录
↓
上传服务器
```

完全能做。

不过这里有一个区别：

### “复制了什么”很好记录

例如：

```text
微信复制：
王总您好，报价单请查收
```

我们可以记录。

### “通过目标 App 自己的长按菜单粘贴”不一定能 100% 准确识别

如果是：

```text
输入法自己的剪贴板
↓
点击
↓
粘贴
```

当然知道这是一次 `paste`。

但如果你：

```text
微信输入框长按
↓
微信自己的「粘贴」
```

这是目标 App 自己操作 InputConnection，输入法不一定能可靠得到一个明确的：

```text
PASTE_EVENT
```

不过对你的需求没有太大影响。

因为我们已经知道：

```text
Clipboard：
xxx
```

对于个人习惯分析已经足够。

---

# 但是“所有输入”我建议加一个安全过滤层

这个一定要做。

Android 官方对 IME 明确提醒：

**密码不要存储。** ([Android Developers][2])

所以我们应该自动识别：

```text
password
web password
visible password
numeric password
```

这些输入框：

```text
不记录
不上传
不缓存
不联想
```

我甚至建议再做：

```text
黑名单 App

银行 APP
密码管理器
Authenticator
支付宝支付界面
Google Authenticator
```

以及一个键盘按钮：

```text
🕶 隐私模式
```

点一下：

```text
本次输入全部不记录
```

因为你的数据库最终可能包含非常大量的私人内容。

---

# 1. 输入文字 → 自动出现表情包

这个功能**非常适合做，而且很有意思**。

不是 Emoji：

```text
😂 😭 🤣
```

而是真正的：

**斗图图片 / GIF / 表情包。**

架构：

```text
输入：
你礼貌吗
     ↓
表情包搜索
     ↓
┌────────┬────────┬────────┐
│ 熊猫头 │ 狗头   │ 猫猫   │
│礼貌吗  │？？？  │离谱    │
└────────┴────────┴────────┘
```

后台建立：

```text
sticker

id
url
thumbnail
keywords
description
format
width
height
```

比如：

```text
image_001.gif

keywords:
礼貌
你礼貌吗
无语
质疑
问号
```

输入：

```text
礼貌
```

马上返回相关表情。

甚至以后可以：

```text
文字语义
↓
Embedding
↓
向量搜索
↓
最匹配的表情包
```

这时候 AI 才开始真正有价值。

---

# 更妙的是 Android 原生支持输入法发图片

Android 从 7.1 开始给 IME 提供了 `commitContent()`，专门允许输入法向支持的编辑器发送图片、GIF 等富内容。([Android Developers][4])

也就是说理论流程就是：

```text
你点熊猫头 GIF
↓
InputContentInfo
↓
commitContent()
↓
聊天 App
```

不过这里有个限制：

**目标 App 必须声明它支持相应 MIME 类型。** ([Android Developers][4])

所以我们做双方案：

```text
支持 commitContent
     ↓
直接插入图片

不支持
     ↓
复制图片 / 系统分享 / 其他兼容方式
```

这样覆盖范围最大。

---

# 2. 语音输入

这个也没有必要动 Rime。

而且有意思的是，语燕作者今年明确回复过：目前不准备内置语音，因为离线模型体积大，而在线识别需要网络权限。([GitHub][5])

但**你的版本本来就要联网、有后端**。

所以这个限制对我们不存在。

第一版直接：

```text
🎤
↓
Android SpeechRecognizer
↓
中文文本
↓
commitText()
```

Android 原生支持 `SpeechRecognizer`，需要 `RECORD_AUDIO` 权限。([Android Developers][6])

Android 12 / API 31 开始还提供：

`createOnDeviceSpeechRecognizer()`

如果设备有本地识别服务，就可以直接走设备端。([Android Developers][6])

所以可以：

```text
           🎤
            │
    ┌───────┴───────┐
    ↓               ↓
设备支持          不支持
    ↓               ↓
本地语音识别      在线语音识别
    ↓               ↓
       转文字
          ↓
      输入框
```

以后如果你自己的后端要做语音识别，也完全可以换成：

```text
输入法录音
↓
你的服务器
↓
ASR 模型
↓
文字
↓
输入法
```

---

# 3. 随意换皮肤

这个需要开发，但不难，而且仍然**完全不涉及 `libyuyanime.so`**。

语燕已经支持主题、深色模式、键盘调整等个性化功能。([GitHub][1])

但目前它并没有真正做到：

> 用户随便导入第三方皮肤。

GitHub 现在还有一个开放的需求，用户就是希望语燕开放自定义皮肤设计或者兼容 Trime 皮肤格式。([GitHub][7])

所以这个我们自己实现。

我建议不要把皮肤写死在 APK。

设计：

```text
skin.zip

├── manifest.json
├── keyboard.webp
├── key_normal.webp
├── key_pressed.webp
├── space.webp
├── delete.webp
├── enter.webp
└── preview.webp
```

`manifest.json`：

```json
{
  "name": "我的机械键盘",
  "author": "me",
  "keyRadius": 12,
  "fontSize": 20,
  "keyboardBackground": "keyboard.webp"
}
```

然后输入法：

```text
设置
↓
皮肤
↓
本地皮肤
↓
导入 ZIP
```

想换多少套就换多少套。

甚至后面我们可以做：

```text
选择手机照片
↓
裁剪
↓
调整透明度
↓
自动生成按键配色
↓
生成皮肤
```

这个会非常好玩。

---

# 所以现在四大功能的难度其实是这样的

| 功能                  |    难度 | 要不要改 `.so` |
| ------------------- | ----: | ---------: |
| 记录所有正常输入            | ★★☆☆☆ |          ❌ |
| 记录剪贴板               | ★★☆☆☆ |          ❌ |
| 上传后端                | ★★☆☆☆ |          ❌ |
| 用户输入习惯分析            | ★★★☆☆ |          ❌ |
| 高频语句补全              | ★★★☆☆ |          ❌ |
| 根据拼音深度影响 Rime 排序    | ★★★★☆ |     可能以后需要 |
| 表情包搜索               | ★★★☆☆ |          ❌ |
| 表情包插入聊天框            | ★★★☆☆ |          ❌ |
| 语音转文字               | ★★☆☆☆ |          ❌ |
| 自定义皮肤               | ★★★☆☆ |          ❌ |
| 替换 `libyuyanime.so` | ★★★★★ |          — |

因此我现在的建议非常明确：

> **第一版绝对不要碰 `libyuyanime.so`。**

---

# 我建议我们开发顺序也调整一下

不要先做 AI，也不要先做表情包。

先完成最核心的数据闭环：

```text
第一阶段
YuyanIme 能自己编译安装
↓
加入网络权限
↓
加入本地 Room 数据库
↓
拦截 commitText / 候选上屏
↓
监听 Clipboard
↓
过滤密码输入
↓
记录输入行为
↓
上传后端
```

然后：

```text
第二阶段

后端建立：
input_event
phrase_stat
completion_stat

↓
分析我的输入习惯
↓
生成私人高频词库
↓
同步回手机
↓
候选栏显示“我的补全”
```

到了这里，这个输入法就已经**真正有自己的核心价值**了。

第三阶段再做：

```text
表情包系统
语音输入
自定义皮肤
```

最后才是：

```text
AI 润色
AI 回复
AI 翻译
AI 语义表情包搜索
```

这样路线非常顺。

而且你最后得到的不只是一个“免费的搜狗输入法”，而是一个搜狗很难替你做到的东西：

**完全属于你自己的输入行为数据库 + 个人语言模型。**

我认为这个方向比单纯加 AI 按钮有意思得多。下一步如果要正式开始，我建议直接从**“语燕客户端埋点架构 + 后端数据库表结构 + API 设计”**开始，我可以把第一版完整的技术架构直接给你定下来。

[1]: https://github.com/gurecn/yuyansdk/tree/master "GitHub - gurecn/yuyansdk: 语燕输入法引擎-一款基于Rime定制开发的九键、全拼、双拼、手写、火星文等方案、支持悬浮、单手、数字行等模式的中文输入法引擎 · GitHub"
[2]: https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method?hl=zh-tw&utm_source=chatgpt.com "建立輸入法 | Views | Android Developers"
[3]: https://developer.android.com/about/versions/10/privacy/changes?utm_source=chatgpt.com "Privacy changes in Android 10 | Android Developers"
[4]: https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard?hl=zh-TW&utm_source=chatgpt.com "圖片鍵盤支援 | Views | Android Developers"
[5]: https://github.com/gurecn/YuyanIme/discussions/483?utm_source=chatgpt.com "建议增加语音识别，模糊拼音功能 · gurecn YuyanIme · Discussion #483 · GitHub"
[6]: https://developer.android.com/reference/android/speech/SpeechRecognizer "SpeechRecognizer  |  API reference  |  Android Developers"
[7]: https://github.com/gurecn/YuyanIme/issues/442?utm_source=chatgpt.com "开放皮肤设计 · Issue #442 · gurecn/YuyanIme"

“所有输入”先不加安全过滤层  ，我是自己用的。每次复制和粘贴的内容也要上传。像微信的语音输入，输入法是否可以拿到并且上传到后端？我想在后台先看看自己的输入习惯，看到数据分析后，再来设计功能，做一款最适合自己的输入法。然后数据库用postgresql的理由是啥，是因为mysql不合适吗？

可以。按你现在这个目标，我会把第一阶段重新定义成：

> **先做“私人输入行为采集器 + 数据分析后台”，暂时不做智能补全，也不做安全过滤。**
>
> 输入法先忠实记录你实际上怎么输入、复制、粘贴、修改文字。数据积累之后，再根据真实规律决定下一步做什么。

这个路线我认为比一开始凭感觉设计“智能输入法”更靠谱。

### 先说微信语音：要分三种情况

如果是**我们自己的输入法提供的麦克风按钮**：

```text
你的输入法
  ↓
录音
  ↓
语音识别
  ↓
“这个订单什么时候发货”
  ↓
输入框
```

那么完全可控。我们可以同时得到：

```text
event_type = voice
audio      = 可选
text       = "这个订单什么时候发货"
app        = com.tencent.mm
time       = ...
```

转出来的文字甚至原始音频，都可以上传后端。Android 官方本身就提供 `SpeechRecognizer`，包括设备端语音识别能力。([Android Developers][1])

但如果你说的是**微信自己提供的“语音转文字”功能**，情况不同。

微信自己录音、自己识别，然后把：

```text
你好，我今天下午过去
```

写进微信自己的输入框。

我们的输入法**拿不到微信录制的原始语音**，因为音频没有经过我们的输入法。

但是——

**我们有机会拿到最终写进输入框里的文字。**

Android 的 `InputConnection` 允许当前 IME 读取光标周围的文字，Android 还支持 `GET_EXTRACTED_TEXT_MONITOR`，输入法可以请求编辑器在文本发生变化时通知它；`InputMethodService.onUpdateExtractedText()` 也专门用于接收编辑框文本状态变化。([Android Developers][2])

所以我们可以设计：

```text
微信输入框：

之前：
""

微信语音识别完成：

之后：
"明天下午我过去一趟"

              ↓

我们的 EditorObserver

before = ""
after  = "明天下午我过去一趟"

              ↓

识别出 external_insert

              ↓

上传：
"明天下午我过去一趟"
```

但是这里我要把准确性说清楚：

**可以监测，但不能保证 100%。**

因为 Android 官方明确允许目标 Editor 返回比 IME 请求更少的文本，甚至在连接无效或 Editor 不配合的情况下返回 `null`。([Android Developers][2])

所以对微信，我预期实际情况会是：

| 行为            | 能否记录                    |
| ------------- | ----------------------- |
| 用我们的键盘打字      | **100% 可控**             |
| 点我们的候选词       | **100% 可控**             |
| 用我们的剪贴板粘贴     | **100% 可控**             |
| 用我们的语音输入      | **100% 可控**             |
| 微信自己的语音转文字    | **大概率可记录最终文字，但需实机验证**   |
| 微信发送语音消息      | **输入法拿不到**              |
| 微信把收到的语音消息转文字 | **通常输入法拿不到，因为那不是编辑框输入** |
| 微信自己修改编辑框内容   | **可以尝试监测 Diff，但不能保证来源** |

所以以后后台不要武断地写：

```text
source = wechat_voice
```

而应该写：

```text
source = external_insert
```

因为我们知道：

> “这段文字不是我的输入法写进去的。”

但不知道究竟是：

```text
微信语音识别
微信自动补全
微信内部粘贴
其他程序操作
```

这对数据分析非常重要。

---

# 复制和粘贴，我建议全部记录

这里可以比我上一条说得再完善一点。

Android 的 `ClipboardManager` 有 `OnPrimaryClipChangedListener`，可以监听系统剪贴板发生变化；而默认 IME 对剪贴板具有特殊访问能力，官方 API 文档明确把 default IME 作为可以获取当前 `ClipData` 的情况。([Android Developers][3])

所以每次系统剪贴板变化：

```text
复制：
这是一个产品报价

↓
ClipboardManager

↓
我们的 ClipboardCollector

↓
上传
```

后台：

```json
{
    "event_type": "clipboard_change",
    "content": "这是一个产品报价",
    "timestamp": "...",
    "mime_type": "text/plain"
}
```

我会把它叫：

`clipboard_change`

而不是直接叫：

`copy`

因为技术上我们知道的是：

> **系统 Clipboard 内容变了。**

大多数时候就是你执行了复制，但也有可能某个 App 主动修改了 Clipboard。Android 的 Clipboard 本质上保存的是全局的 `ClipData`。([Android Developers][3])

---

# “粘贴”比“复制”稍微复杂一点

如果你使用我们输入法自己的：

```text
📋 剪贴板

"这是一个产品报价"

↓ 点击
```

那么：

```text
event_type = paste
content = "这是一个产品报价"
source = ime_clipboard
```

**100% 确定。**

因为是我们的代码执行的。

但是你如果在微信输入框：

```text
长按
↓
粘贴
```

这是微信/Android Editor 自己做的，我们不会直接收到：

```text
onPaste("xxx")
```

`InputConnection` 确实存在执行 `R.id.paste` 等上下文菜单操作的能力，但那意味着 **IME 主动要求 Editor Paste**，并不代表目标 App 自己执行 Paste 时会反向通知 IME。([Android Developers][2])

不过我们可以非常聪明地推断。

假设：

```text
12:00:01

clipboard =
"你好，请问这个什么时候发货"


12:00:05

微信输入框变化：

before:
""

after:
"你好，请问这个什么时候发货"
```

我们就比较：

```text
新增文本
==
最近 Clipboard 内容
```

则记录：

```text
event_type = paste_inferred
confidence = 0.99
```

这样基本就解决了。

---

# 所以我建议我们的客户端采集不是一个点，而是三个点

```text
                 YuyanIme
                    │
       ┌────────────┼────────────┐
       ↓            ↓            ↓
 IME Event       Clipboard     Editor
 Collector       Collector     Observer
       │            │            │
       ↓            ↓            ↓
自己打字         复制内容      外部文字变化
候选词           Clipboard     微信语音文字
删除             图片/URI      系统粘贴
自己的粘贴                     App自动修改
自己的语音
       │            │            │
       └────────────┼────────────┘
                    ↓
                Event Store
                    ↓
                Upload Queue
                    ↓
                  后端
```

这才比较接近你说的：

**“记录我的所有输入行为。”**

---

# 我甚至建议暂时不要只保存“最终句子”

因为你现在的目标是：

> 先观察自己的行为，再决定输入法应该怎么做。

那我们要保留**原始行为数据**。

比如你输入：

```text
我觉得这个方案不太行
```

实际上可能经历：

```text
wojue
↓
我觉
↓
我觉得
↓
我觉得这个
↓
我觉得这个方法
↓
删除“方法”
↓
输入“方案”
↓
我觉得这个方案
↓
我觉得这个方案不太行
```

这些数据以后非常有价值。

因为后台可能发现：

> 你经常先输入“方法”，然后改成“方案”。

或者发现：

> 每次输入“麻烦”，72% 的情况下后面都是“帮我确认一下”。

这样真正能设计出适合你的候选系统。

---

# 后台 Event，我会这么存

核心不是一上来设计：

```text
user_phrase
user_dictionary
autocomplete
```

而是先建立一个**Append-only Event Log**。

例如：

```text
input_event

id
device_id
session_id
sequence

timestamp

package_name
editor_id

event_type

text
text_before
text_after

clipboard_id

source
source_confidence

input_mode

metadata
```

`event_type` 第一阶段可以包括：

```text
key
compose
commit
candidate_commit
delete

clipboard_change
paste
paste_inferred

external_insert
external_delete

voice
```

比如：

```json
{
    "event_type": "external_insert",
    "package_name": "com.tencent.mm",
    "text": "明天下午我过去一趟",
    "source": "editor_observer",
    "source_confidence": 0.8
}
```

以后数据分析才不会搞混：

> 这是我键盘打的？

还是：

> 微信自己产生的？

还是：

> 粘贴的？

---

# 一个特别重要的设计：上传一定不要直接跟输入操作绑定

不要：

```text
按一个键
↓
HTTP Request
↓
按一个键
↓
HTTP Request
```

这样网络一卡输入法就跟着卡。

应该：

```text
输入行为
↓
本地 SQLite / Room
↓
立即返回
↓
后台 Batch Upload
↓
服务器
```

例如：

```text
一次上传 50 / 100 条 Event
```

并给每条 Event 一个 UUID：

```text
0198c8...
0198c9...
0198ca...
```

服务器：

```text
UNIQUE(event_id)
```

这样即使：

```text
手机断网
↓
积累 600 条
↓
重新联网
↓
批量上传
```

也不会漏。

请求失败重复发送也不会产生重复数据。

对于你这个项目：

**“不丢数据”比“实时上传”重要得多。**

---

# 然后回答 PostgreSQL / MySQL

这里我要纠正我前面的表达：

**不是 MySQL 不合适。**

事实上你的第一版用 **MySQL 8 完全没有问题**。

甚至如果你的后端本来就习惯 MySQL，我不会为了这个项目强行换 PostgreSQL。

MySQL 本身有原生 JSON 类型，会验证 JSON 并用内部格式存储，也能够通过 generated columns / multi-valued indexes 对 JSON 中的数据建立索引。([MySQL开发者专区][4])

而我们的核心数据：

```text
input_event
clipboard_event
session
device
```

本质上都是非常普通的关系型数据。

MySQL 很适合。

---

# 我之前为什么提 PostgreSQL？

主要是考虑你这个项目后面会变成：

> **一个探索型数据分析项目。**

也就是说现在我们根本不知道半年后 `metadata` 会有什么。

现在可能：

```json
{
  "input_mode": "pinyin",
  "keyboard": "qwerty"
}
```

后来：

```json
{
  "input_mode": "voice",
  "asr_provider": "xxx",
  "duration": 4.6,
  "confidence": 0.96
}
```

再后来：

```json
{
  "source": "sticker",
  "keyword": "无语",
  "candidate_rank": 3
}
```

PostgreSQL 的 `jsonb` 对这种数据特别舒服，它不仅直接支持索引，而且可以用 GIN 对 JSONB 的 key/value 和包含关系做索引查询。PostgreSQL 官方也明确建议大多数需要处理 JSON 的应用优先考虑 `jsonb`。([PostgreSQL][5])

比如后台突然想研究：

```text
过去 30 天：

metadata.input_mode = voice

并且：

package_name = 微信

并且：

晚上 20:00~23:00
```

PostgreSQL 对这种“以后才想到的问题”比较舒服。

这就是我最开始偏向 PostgreSQL 的主要理由。

---

# 但对你的第一版，我不会把 PostgreSQL 当硬要求

实际上我会这样看：

|               |  MySQL 8 | PostgreSQL |
| ------------- | -------: | ---------: |
| 存输入 Event     |    ★★★★★ |      ★★★★★ |
| 高频词统计         |    ★★★★★ |      ★★★★★ |
| GROUP BY 分析   |    ★★★★★ |      ★★★★★ |
| JSON Metadata |    ★★★★☆ |      ★★★★★ |
| JSON 临时查询     |    ★★★★☆ |      ★★★★★ |
| 后期复杂数据分析      |    ★★★★☆ |      ★★★★★ |
| 第一版开发难度       |    ★★★★★ |      ★★★★★ |
| 是否适合本项目       | **完全适合** |   **完全适合** |

因此：

**如果你更想用 MySQL，就直接 MySQL。**

我不会为了所谓“技术先进”让你多维护一个完全没必要的新数据库。

这个项目是单人数据量，即使我们把输入记录得非常细，数据库压力对 MySQL/PostgreSQL 都不是问题。

真正需要认真设计的是：

**数据模型，而不是数据库品牌。**

---

# 我现在更倾向于第一阶段就做成“数据实验平台”

输入法先不要变聪明。

它只负责：

```text
采集
↓
保存
↓
上传
```

后台第一版重点反而应该是一个 Dashboard：

```text
我的输入数据

今日输入
18,632 字

主动键盘输入
62%

复制粘贴
21%

外部插入
10%

语音
7%


────────────

最常使用 App

微信        43%
浏览器      19%
邮件        14%
其他        24%


────────────

我的高频词

好的             186
可以             143
这个             139
确认             91


────────────

我的高频短语

好的，没问题                    73
麻烦帮我确认一下                56
这个什么时候可以发货            31


────────────

高频前缀

“麻烦” →
帮我确认一下             68%
看一下                   17%

“好的” →
没问题                   54%
我知道了                 21%


────────────

复制 → 粘贴

复制后 10 秒内粘贴        48%
复制后 1 分钟内粘贴       27%

最常复制：
链接
地址
产品名称
邮件内容
```

等你真正看一个月自己的数据后，可能会发现你现在想象的“最需要的输入法功能”和实际需要的完全不一样。

比如数据可能告诉我们：

> 你根本不需要 AI 续写。

但：

> 你一天有 80 次都在重复输入同样的 20 个句子。

那我们就优先做：

**个人短语预测。**

又或者发现：

> 你 35% 的文字来自复制粘贴。

那我们真正应该做的是：

**智能剪贴板 + 自动分类 + 历史搜索。**

这就是我觉得你这个项目现在最有价值的方向。

另外，你说**暂时不加安全过滤层**，那第一版我就会按“ALL Capture”设计；不过我会把过滤接口位置预留出来但默认关闭，这样以后如果你改变主意，不需要重构整个采集链路。

[1]: https://developer.android.com/reference/android/speech/SpeechRecognizer?utm_source=chatgpt.com "SpeechRecognizer | API reference | Android Developers"
[2]: https://developer.android.com/reference/android/view/inputmethod/InputConnection "InputConnection  |  API reference  |  Android Developers"
[3]: https://developer.android.com/reference/android/content/ClipboardManager "ClipboardManager  |  API reference  |  Android Developers"
[4]: https://dev.mysql.com/doc/refman/8.0/en/json.html?source=post_page-----f3cee8076abd--------------------------------&utm_source=chatgpt.com "MySQL :: MySQL 8.0 Reference Manual :: 13.5 The JSON Data Type"
[5]: https://www.postgresql.org/docs/17/datatype-json.html "PostgreSQL: Documentation: 17: 8.14. JSON Types"


帮我设计方案吧，用PostgreSQL，第一阶段也有在收集输入习惯，所以要做智能补全，这个又不难，只是根据高频词做补全而已

可以。按你现在确定的方向，我建议第一阶段就做成一个**完整可长期使用的 V1**：

> **语燕输入法 + 全量输入采集 + PostgreSQL + 数据分析后台 + 个人高频补全**

AI、表情包、语音、皮肤先不进入 V1 核心开发，等你看完自己的真实输入数据以后再决定优先级。

而且 **V1 完全不碰 `libyuyanime.so`**。语燕目前已经有成熟的全拼、九宫格、双拼、候选词、剪贴板、常用语、主题、自定义菜单栏等能力，我们只在上层增加数据和补全系统。语燕目前本身刻意不申请网络权限，所以我们的 Fork 需要主动增加网络层。([GitHub][1])

---

# 一、整体架构

我建议最终形成：

```text
                       Android 手机
┌────────────────────────────────────────────┐
│               YuyanIme                     │
│                                            │
│  ┌───────────┐       ┌─────────────────┐   │
│  │ Rime/语燕 │       │ Personal        │   │
│  │ 原候选词  │       │ Completion      │   │
│  └─────┬─────┘       └────────┬────────┘   │
│        │                      │             │
│        └──────────┬───────────┘             │
│                   ↓                         │
│             CandidateMerger                │
│                   ↓                         │
│            最终候选词 UI                    │
│                                            │
│ ────────────────────────────────────────── │
│                                            │
│ InputEventCollector                        │
│ ├─ 键盘输入                                │
│ ├─ 候选词上屏                              │
│ ├─ 删除/修改                               │
│ ├─ 剪贴板变化                              │
│ ├─ 粘贴                                    │
│ └─ 外部编辑器文字变化                      │
│                  ↓                         │
│              Room/SQLite                   │
│                  ↓                         │
│              SyncManager                   │
└──────────────────┬─────────────────────────┘
                   │
                   │ HTTPS / Batch
                   ↓
┌────────────────────────────────────────────┐
│                  后端                      │
│                                            │
│ API Server                                 │
│      ↓                                     │
│ PostgreSQL                                 │
│      ↓                                     │
│ Data Analyzer                              │
│ ├─ 高频词                                  │
│ ├─ 高频短语                                │
│ ├─ Prefix 分析                             │
│ ├─ App 输入习惯                            │
│ ├─ Copy/Paste 分析                         │
│ └─ Completion Ranking                      │
│      ↓                                     │
│ Personal Dictionary                       │
│      ↓                                     │
│ 同步回手机                                 │
│                                            │
│ Web Dashboard                              │
└────────────────────────────────────────────┘
```

核心原则：

**采集和补全完全分离。**

补全必须本地运行，不能：

```text
输入一个字
→ 请求服务器
→ 等服务器候选
```

服务器只负责：

```text
采集
→ 分析
→ 生成模型
→ 同步模型
```

输入的时候：

```text
输入
→ 本地模型
→ 立即出候选
```

因此没网也照常使用。

---

# 二、Android 端拆成 5 个模块

## 1. `InputEventCollector`

这是整个项目最重要的模块。

语燕正常往目标 App 输入文字最终还是通过 Android IME 的 `InputConnection` 工作；Android 官方提供 `commitText()`、`getTextBeforeCursor()`、`getTextAfterCursor()` 等接口，所以我们可以围绕上屏和编辑行为建立统一采集层。([Android Developers][2])

比如：

```text
你输入：

麻烦帮我确认一下
```

记录：

```json
{
    "event_type": "commit",
    "text": "麻烦帮我确认一下",
    "app": "com.tencent.mm"
}
```

候选词上屏：

```json
{
    "event_type": "candidate_commit",
    "text": "确认",
    "input_code": "queren",
    "candidate_index": 1
}
```

删除：

```json
{
    "event_type": "delete",
    "text": "认"
}
```

复制：

```json
{
    "event_type": "clipboard_change",
    "text": "产品什么时候可以发货"
}
```

输入法自己的剪贴板粘贴：

```json
{
    "event_type": "paste",
    "text": "产品什么时候可以发货"
}
```

外部 App 导致输入框变化：

```json
{
    "event_type": "external_insert",
    "before": "好的",
    "after": "好的没问题",
    "text": "没问题"
}
```

第一阶段按你的要求：

**全部采集，不做密码/APP/字段过滤。**

代码结构上把 Filter Interface 留着，但默认：

```text
CaptureFilter
    ↓
AllowAll
```

以后想过滤再换实现，不影响现在。

---

# 三、我建议同时保留“行为数据”和“结果数据”

不要只保存：

```text
今天输入了：
麻烦帮我确认一下
```

否则以后很多分析做不了。

比如真实过程：

```text
麻烦
↓
麻烦帮我
↓
麻烦帮我看看
↓
删除：看看
↓
麻烦帮我确认
↓
麻烦帮我确认一下
```

这个过程非常有价值。

后台以后可能发现：

```text
“麻烦帮我”
之后：

确认一下       68%
看看           17%
处理一下       8%
其他           7%
```

这就是智能补全的基础。

---

# 四、PostgreSQL 数据库

第一阶段核心不用设计几十张表。

我建议先控制在 **5 张核心表**。

## 1. `input_event`

这是原始数据库，最重要。

```sql
CREATE TABLE input_event (
    id UUID PRIMARY KEY,

    user_id UUID NOT NULL,
    device_id UUID NOT NULL,
    session_id UUID,

    sequence_no BIGINT,

    occurred_at TIMESTAMPTZ NOT NULL,

    package_name VARCHAR(255),

    event_type VARCHAR(50) NOT NULL,
    source VARCHAR(50),

    text TEXT,

    text_before TEXT,
    text_after TEXT,

    input_code TEXT,

    metadata JSONB NOT NULL DEFAULT '{}',

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

例如：

```text
event_type
────────────────────
key
compose
commit
candidate_commit
delete

clipboard_change
paste
paste_inferred

external_insert
external_delete

completion_show
completion_accept

voice
```

---

## 2. 为什么这里特别适合 `JSONB`

比如现在：

```json
{
    "keyboard": "qwerty",
    "language": "zh"
}
```

未来可能：

```json
{
    "keyboard": "qwerty",
    "language": "zh",
    "candidate_index": 2,
    "rime_schema": "rime_ice",
    "is_completion": true
}
```

再以后：

```json
{
    "voice_provider": "xxx",
    "voice_duration": 5.3
}
```

主表不用不停 ALTER。

PostgreSQL 的 `jsonb` 可以直接使用 GIN 索引查询 JSON 内容，因此很适合这种事件型、后续字段不断扩充的数据。([PostgreSQL][3])

例如：

```sql
CREATE INDEX idx_input_event_metadata
ON input_event
USING GIN(metadata);
```

---

# 五、`phrase_stat`

这是分析后的结果。

```text
phrase_stat

user_id
phrase
package_name

use_count
use_days
last_used_at

score
```

例如：

| phrase     |  次数 |
| ---------- | --: |
| 好的         | 683 |
| 好的没问题      | 316 |
| 麻烦帮我确认一下   | 182 |
| 这个什么时候可以发货 |  97 |
| 我这边确认一下    |  86 |

再进一步可以有：

```text
微信：

好的没问题             238
哈哈哈哈               116
什么时候               93

Gmail：

Please let me know     61
Thank you              49
Best regards           38
```

这才真正符合你的个人习惯。

---

# 六、最关键的 `completion_candidate`

智能补全实际使用这张表。

```text
completion_candidate

prefix
prefix_pinyin
prefix_initials

completion

package_name

use_count
score
last_used_at

version
```

例如：

```text
prefix:
麻烦

completion:
麻烦帮我确认一下

count:
91
```

于是：

```text
输入：

麻烦
```

输入法出现：

```text
┌────────────────────────────────────────┐
│ ★ 麻烦帮我确认一下 │ 麻烦看一下 │ ... │
└────────────────────────────────────────┘
```

---

# 七、甚至拼音阶段也可以做个人补全

不需要动 Rime。

例如：

```text
麻烦帮我确认一下
```

后台同时保存：

```text
text:
麻烦帮我确认一下

pinyin:
mafanbangwoquerenyixia

initials:
mfbwqryx
```

所以当你输入：

```text
mafan
```

我们自己的 PersonalCompletionEngine 可以匹配：

```text
mafanbangwoquerenyixia
```

然后显示：

```text
★ 麻烦帮我确认一下
```

这时候：

```text
Rime Candidate
+
Personal Candidate
```

最后交给：

```text
CandidateMerger
```

---

# 八、不建议“替换 Rime 候选”，而是合并

例如 Rime 原来：

```text
1. 麻烦
2. 麻烦你
3. 麻烦了
4. 马凡
5. 麻饭
```

你的个人模型返回：

```text
麻烦帮我确认一下
score = 0.95

麻烦看一下
score = 0.72
```

最终：

```text
1. 麻烦
2. ★ 麻烦帮我确认一下
3. 麻烦你
4. ★ 麻烦看一下
5. 麻烦了
```

我不建议一开始：

```text
个人候选全部顶掉 Rime
```

否则可能影响正常中文输入体验。

第一版规则可以非常简单：

> **最多插入 2 个个人候选。**

这样既有明显效果，又不会破坏语燕原来的体验。

---

# 九、智能补全算法第一版真的不用复杂

你说得对。

第一阶段根本不需要 AI。

先做一个：

**Frequency + Recency + App**

就很好用了。

比如：

```text
score =
    frequency_score × 0.50
  + recency_score   × 0.20
  + app_score       × 0.20
  + accept_score    × 0.10
```

全部 normalize 到：

```text
0 ~ 1
```

例如你：

```text
“麻烦帮我确认一下”
```

总共输入：

```text
150 次
```

最近一个星期：

```text
25 次
```

微信里面：

```text
100 次
```

而且历史候选出现后你：

```text
80% 都点了
```

那自然排名非常高。

---

# 十、最开始甚至可以更简单

第一版上线之前：

```text
use_count < 3
```

不生成候选。

意味着：

> 至少自己真正输入过 3 次，才认为这是一个习惯。

例如：

```text
今天随便输入一次：

天气真不错我要去火星
```

不会成为补全。

但是：

```text
麻烦帮我确认一下

出现 27 次
```

肯定加入。

---

# 十一、一定要有 Completion Feedback

否则以后不知道自己的补全系统好不好。

候选出现：

```json
{
    "event_type": "completion_show",
    "prefix": "麻烦",
    "completion": "麻烦帮我确认一下",
    "rank": 1
}
```

点击：

```json
{
    "event_type": "completion_accept",
    "completion": "麻烦帮我确认一下",
    "saved_chars": 7
}
```

以后 Dashboard 可以直接看到：

```text
个人补全

展示次数           1,832
点击次数             936

接受率             51.1%

节省输入字符      8,623

平均每次节省        9.2 字
```

这个指标以后非常有用。

---

# 十二、客户端本地数据库

服务器是 PostgreSQL。

**手机端还是 SQLite / Room。**

只保存：

```text
local_event
completion_cache
sync_state
```

不用把全部历史塞手机。

---

## `local_event`

尚未成功上传：

```text
event_id
event_type
data
created_at
uploaded
```

上传成功：

```text
uploaded = true
```

定期删除。

---

## `completion_cache`

例如：

```text
麻烦
→
麻烦帮我确认一下
麻烦看一下
麻烦处理一下
```

这个必须在本地。

---

# 十三、同步机制

这里也建议简单。

例如累计：

```text
100 条 event
```

或者适当时间触发：

```text
POST /api/v1/events/batch
```

一次：

```json
{
    "device_id": "...",
    "events": [
        {},
        {},
        {}
    ]
}
```

服务器根据：

```text
event UUID
```

做幂等。

所以：

```text
上传
↓
超时
↓
不知道服务器有没有收到
↓
再上传
```

也不会产生两份数据。

---

# 十四、补全模型也采用版本同步

服务器生成：

```text
completion_version = 183
```

手机现在：

```text
version = 181
```

请求：

```text
GET /api/v1/completions?since=181
```

返回变化：

```text
新增
修改
删除
```

然后更新本地。

因此不需要每次下载整个词库。

---

# 十五、后端第一阶段 API

其实不到 10 个。

```text
POST
/v1/events/batch

GET
/v1/completions/sync

GET
/v1/dashboard/overview

GET
/v1/dashboard/timeline

GET
/v1/dashboard/apps

GET
/v1/dashboard/phrases

GET
/v1/dashboard/prefixes

GET
/v1/dashboard/clipboard
```

一个私人项目足够了。

---

# 十六、数据分析后台我反而认为非常重要

你的项目第一阶段应该有一个很好用的 Dashboard。

首页：

```text
今天

输入字符
18,392

──────────

键盘输入
11,238     61%

复制粘贴
4,132      22%

外部输入
3,022      17%

──────────

删除次数
1,283

修改次数
523
```

然后是：

### 输入时间

```text
00 ──
01 ─
...
09 ██████
10 ███████████
11 ███████
...
20 █████████████
21 █████████
```

---

### APP 分布

```text
微信               46%
浏览器             18%
邮件               14%
ChatGPT             8%
其他               14%
```

---

### 高频词

```text
好的               892
可以               682
确认               428
这个               397
问题               286
```

---

### 高频句

```text
好的，没问题                     286
麻烦帮我确认一下                 138
这个什么时候可以发货              83
```

---

### Prefix 分析

```text
“麻烦”

→ 帮我确认一下       63%
→ 看一下             18%
→ 处理一下            9%
→ 其他               10%
```

这个页面直接告诉我们：

**下一步应该给你预测什么。**

---

# 十七、剪贴板单独做一个分析页

这个我认为你后面可能会发现很有价值。

```text
今日复制

163 次
```

分类：

```text
文字        83%
URL         12%
其他         5%
```

高频复制：

```text
产品链接
邮箱地址
客户地址
电话号码
产品名称
```

然后分析：

```text
复制 → 10 秒内粘贴       48%
复制 → 1 分钟内粘贴      71%
```

后面可能自然而然产生一个功能：

> **智能剪贴板。**

这就是为什么我同意你现在先采数据。

---

# 十八、PostgreSQL 后期还能帮我们做模糊分析

以后想查询：

```text
确认一下
确认下
帮我确认一下
麻烦确认一下
```

PostgreSQL 自带 `pg_trgm` 扩展，可以进行基于 trigram 的文本相似度和索引搜索。([PostgreSQL][4])

所以以后我们可以识别：

```text
麻烦帮我确认一下
麻烦帮忙确认一下
麻烦确认一下
```

可能其实属于一个表达习惯。

**但 V1 暂时不用。**

先用完全匹配和 Prefix 就行。

---

# 十九、后端技术栈

我会推荐：

```text
Android
├─ YuyanIme Fork
├─ Kotlin
├─ Room
└─ WorkManager / HTTP Client

Backend
├─ PHP
├─ Symfony
├─ PostgreSQL
└─ REST API

Analysis
├─ PostgreSQL SQL
├─ Symfony Command
└─ 后期再考虑 Python

Frontend Dashboard
├─ Vue / React
└─ ECharts
```

这里有一个我比较明确的建议：

**第一阶段不要上 Python 数据分析服务。**

没必要。

例如：

```sql
SELECT text, COUNT(*)
FROM input_event
WHERE event_type = 'commit'
GROUP BY text
ORDER BY COUNT(*) DESC;
```

绝大多数 V1 分析 PostgreSQL 自己就能完成。

复杂 NLP 真正出现以后再加 Python。

---

# 二十、第一阶段开发顺序

我建议严格按这个顺序。

### V1.1 — Fork 语燕

做到：

```text
源码可以编译
↓
APK 安装
↓
作为默认输入法正常使用
```

不要改 UI。

---

### V1.2 — 数据采集

加入：

```text
InputEventCollector
ClipboardCollector
EditorObserver
SessionManager
```

先只写手机本地数据库。

然后自己用两天检查：

> 数据是不是真的完整。

---

### V1.3 — 后端

建立：

```text
Symfony API
PostgreSQL
Batch Upload
Device
Session
Input Event
```

完成：

```text
手机
→
PostgreSQL
```

闭环。

---

### V1.4 — Dashboard

先把自己的数据看起来。

重点：

```text
输入量
APP
时间
高频词
高频短语
复制粘贴
删除修改
```

---

### V1.5 — 高频模型

后台每天/每小时执行：

```text
AnalyzeInputCommand
```

生成：

```text
phrase_stat
completion_candidate
```

---

### V1.6 — 本地智能补全

同步：

```text
PostgreSQL
↓
API
↓
completion_cache
```

然后：

```text
PersonalCompletionEngine
↓
CandidateMerger
↓
语燕候选栏
```

至此第一阶段结束。

---

# 第一阶段完成以后，你实际会得到

不是一个 Demo，而是真的可以每天用：

```text
                     你的输入法 V1

                      语燕
                       │
          ┌────────────┴─────────────┐
          ↓                          ↓
      原本所有功能              你的数据系统
                                     │
                          ┌──────────┼─────────┐
                          ↓          ↓         ↓
                       PostgreSQL  Dashboard  分析
                                                │
                                                ↓
                                         高频输入习惯
                                                │
                                                ↓
                                         Personal Model
                                                │
                                                ↓
                                             手机
                                                │
                                                ↓
                                          智能补全
```

我认为这就是目前最合理的 V1 边界。

**先不做 AI，不换 `libyuyanime.so`，也不碰 Rime 核心。** 第一阶段真正需要改语燕的地方只有三个核心方向：**事件采集、联网同步、候选栏注入个人补全**。而语燕现有的输入、剪贴板和键盘 UI 能力都继续保留。([GitHub][1])

接下来进入实际开发时，我建议第一件事不是写数据库，而是**把当前最新版 YuyanIme 的源码结构分析出来，准确找到“文字上屏、候选词生成/显示、剪贴板、InputMethodService”这几个类**。找到这四个入口以后，我就可以按上面的架构给你规划具体应该新建哪些 Kotlin 类、修改哪些原文件，而不是泛泛设计。

**回答 ID：IME-ARCH-001**

[1]: https://github.com/gurecn/YuyanIme "GitHub - gurecn/YuyanIme: 语燕输入法-一款基于Rime定制开发的九键、全拼、双拼、手写、火星文等方案、支持悬浮、单手、数字行等键盘模式的中文输入法 · GitHub"
[2]: https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method "Create an input method  |  Views  |  Android Developers"
[3]: https://www.postgresql.org/docs/17/datatype-json.html "PostgreSQL: Documentation: 17: 8.14. JSON Types"
[4]: https://www.postgresql.org/docs/17/pgtrgm.html?utm_source=chatgpt.com "PostgreSQL: Documentation: 17: F.33. pg_trgm — support for similarity of text using trigram matching"

Backend和Analysis最好是同一个语言和框架，Frontend Dashboard也要跟后端放在一起，毕竟是一人开发，不需要那么多的仓库

对，我也赞成。按你这个项目的规模，**拆 Backend / Analysis / Dashboard 三套技术栈反而是在给自己增加维护成本**。

我建议直接做成：

> **一个 Symfony 项目 + PostgreSQL + Twig/JavaScript Dashboard**
>
> API、数据分析、后台页面、定时任务全部在一个仓库里。

这样最适合一人开发。

## 推荐最终技术栈

```text
Android
└── YuyanIme Fork
    ├── Kotlin
    ├── Room
    └── API Client


Server
└── Symfony
    ├── REST API
    ├── PostgreSQL
    ├── 数据分析
    ├── 智能补全计算
    ├── Dashboard
    ├── 定时任务
    └── 管理功能
```

服务器甚至就是**一个应用**：

```text
personal-ime-server/
```

不拆仓库。

---

# 一、后端全部 PHP + Symfony

我建议：

```text
PHP
Symfony
PostgreSQL
Doctrine DBAL / ORM
Twig
JavaScript
ECharts
```

这样下面这些全部 PHP：

```text
输入数据接收
设备管理
输入 Session
剪贴板数据
高频词统计
高频短语统计
Prefix 分析
补全模型生成
补全模型同步
Dashboard API
Dashboard 页面
定时分析任务
```

完全没有必要再引入 Python。

至少在我们做到真正的机器学习/NLP之前，都不用 Python。

---

# 二、Analysis 直接做成 Symfony Command

比如：

```text
php bin/console app:analyze:input
```

负责：

```text
input_event
      ↓
清洗 / 聚合
      ↓
phrase_stat
      ↓
completion_candidate
```

可以再细一点：

```text
php bin/console app:analyze:phrases
php bin/console app:analyze:completions
php bin/console app:analyze:clipboard
```

但我甚至建议第一版只有：

```text
app:analyze
```

内部按步骤运行：

```php
InputAnalyzer
    ↓
PhraseAnalyzer
    ↓
PrefixAnalyzer
    ↓
CompletionGenerator
```

以后复杂了再拆。

---

# 三、定时分析直接 Cron

第一版不用 Redis，也不用搞复杂的 Queue。

例如：

```text
*/10 * * * *
php bin/console app:analyze
```

每 10 分钟分析一次新数据。

数据流：

```text
手机
 ↓
POST /api/v1/events/batch
 ↓
input_event
 ↓
PostgreSQL
 ↓
每 10 分钟
 ↓
app:analyze
 ↓
phrase_stat
completion_candidate
 ↓
手机同步
```

甚至后面可以做到增量分析，只处理：

```text
last_analyzed_event_id
```

之后的数据。

这样数据越来越多以后也不用每次全表重算。

---

# 四、Dashboard 也不要单独做 Vue/React 项目

我会直接：

```text
Symfony
+
Twig
+
少量 JavaScript
+
ECharts
```

例如：

```text
/dashboard
/dashboard/input
/dashboard/phrases
/dashboard/completion
/dashboard/clipboard
/dashboard/apps
```

Symfony Controller：

```php
#[Route('/dashboard', name: 'dashboard')]
public function index(
    DashboardService $dashboard
): Response {
    return $this->render('dashboard/index.html.twig', [
        'data' => $dashboard->overview(),
    ]);
}
```

Twig：

```text
templates/
└── dashboard/
    ├── index.html.twig
    ├── input.html.twig
    ├── phrases.html.twig
    ├── completion.html.twig
    ├── clipboard.html.twig
    └── apps.html.twig
```

图表用 ECharts：

```text
Symfony
    ↓
Twig
    ↓
JSON
    ↓
ECharts
```

够用了。

---

# 五、这样仓库会非常简单

我建议结构：

```text
personal-ime/
│
├── android/
│   └── YuyanIme/
│
└── server/
    ├── bin/
    ├── config/
    ├── migrations/
    │
    ├── public/
    │
    ├── src/
    │   ├── Command/
    │   │
    │   └── AnalyzeCommand.php
    │   │
    │   ├── Controller/
    │   │   ├── Api/
    │   │   │   ├── EventController.php
    │   │   │   └── CompletionController.php
    │   │   │
    │   │   └── Dashboard/
    │   │       ├── DashboardController.php
    │   │       ├── PhraseController.php
    │   │       └── ClipboardController.php
    │   │
    │   ├── Entity/
    │   │   ├── InputEvent.php
    │   │   ├── PhraseStat.php
    │   │   └── CompletionCandidate.php
    │   │
    │   ├── Repository/
    │   │
    │   ├── Service/
    │   │   ├── Input/
    │   │   ├── Analysis/
    │   │   │   ├── InputAnalyzer.php
    │   │   │   ├── PhraseAnalyzer.php
    │   │   │   ├── PrefixAnalyzer.php
    │   │   │   └── CompletionGenerator.php
    │   │   │
    │   │   ├── Completion/
    │   │   └── Dashboard/
    │   │
    │   └── DTO/
    │
    ├── templates/
    │   └── dashboard/
    │
    ├── assets/
    │   ├── app.js
    │   └── dashboard/
    │
    └── composer.json
```

甚至整个项目都可以只有一个 Git：

```text
personal-ime
├── android
└── server
```

**Monorepo 最适合这个项目。**

---

# 六、我甚至不建议所有分析结果都做 Entity

这里可以稍微务实一点。

例如：

```text
input_event
```

这种核心业务数据，可以 Doctrine Entity。

但很多分析：

```text
每日输入量
APP 使用比例
过去30天输入趋势
小时分布
高频 Prefix
```

没有必要为了“架构漂亮”全部 ORM。

直接 PostgreSQL SQL：

```sql
SELECT
    package_name,
    COUNT(*) AS event_count,
    SUM(length(text)) AS chars
FROM input_event
WHERE occurred_at >= :start
GROUP BY package_name
ORDER BY chars DESC;
```

Symfony：

```text
Doctrine DBAL
↓
原生 SQL
↓
DashboardService
```

对于数据分析项目，这通常比硬写 Doctrine ORM 更舒服。

所以可以采用：

> **业务数据 ORM，分析查询 DBAL/SQL。**

---

# 七、第一阶段数据库也可以重新精简

现在我建议主要就是：

```text
device

input_session

input_event

phrase_stat

completion_candidate

analysis_state
```

六张核心表。

其中最重要的是：

```text
input_event
```

其他都是从它计算出来的。

可以把整个架构理解成：

```text
                input_event
                     │
          ┌──────────┼───────────┐
          ↓          ↓           ↓
     phrase_stat   Dashboard   clipboard
          │
          ↓
 completion_candidate
          │
          ↓
        Android
```

---

# 八、补全甚至可以准实时更新

既然第一阶段就做智能补全，我不一定会等每天晚上分析。

比如：

```text
10分钟一次
```

生成新的 completion。

你今天连续输入：

```text
麻烦帮我确认一下
麻烦帮我确认一下
麻烦帮我确认一下
```

过一会儿：

```text
phrase_stat

麻烦帮我确认一下
count = 3
```

生成：

```text
prefix:
麻烦

completion:
麻烦帮我确认一下
```

手机下一次同步：

```text
麻烦
```

马上就能看到：

```text
★ 麻烦帮我确认一下
```

这个学习反馈会非常明显。

---

# 九、补全算法也全部用 PHP

第一版：

```php
final class CompletionScorer
{
    public function score(
        int $useCount,
        int $appUseCount,
        int $acceptCount,
        \DateTimeInterface $lastUsedAt,
    ): float {
        // frequency
        // recency
        // app affinity
        // accept rate
    }
}
```

不需要：

```text
Python
TensorFlow
PyTorch
LLM
Embedding
Vector DB
```

这些第一阶段全部不要。

---

# 十、我建议 Dashboard 第一版重点做 6 页

```text
Overview
输入总览

Timeline
每天 / 每小时输入量

Applications
不同 APP 输入习惯

Phrases
高频词 / 高频短语

Completion
补全效果 / 接受率

Clipboard
复制粘贴分析
```

其中 **Phrases** 页面我认为以后你会经常看。

例如：

```text
我的高频短语

过去 7 天 | 30 天 | 全部

────────────────────────────

短语                         次数

好的，没问题                  183
麻烦帮我确认一下              126
我这边看一下                   96
这个什么时候可以发货           82

────────────────────────────

点击：

麻烦帮我确认一下

微信                  73
工作软件              38
邮件                  15

首次出现：
2026-08-20

最近使用：
2026-09-16

平均每天：
4.3 次
```

这才真正符合你：

> **“先看看自己到底怎么输入。”**

---

## 所以技术方案现在可以正式收敛

我建议确定为：

```text
【手机】

YuyanIme
Kotlin
Room
HTTP Client


【服务器】

PHP
Symfony
PostgreSQL
Doctrine ORM + DBAL
Twig
JavaScript
ECharts


【分析】

Symfony Service
Symfony Command
PostgreSQL SQL


【调度】

Linux Cron


【代码管理】

一个 Monorepo

personal-ime/
├── android/
└── server/
```

**不引入 Node 后端、不引入 Python、不拆 Dashboard、不拆 Analysis、不拆微服务、不上 Redis、不上 MQ。**

等哪一天真的出现某个问题必须引入新的组件，再加。

对于**一人开发、自用、快速迭代**，我认为这种架构比“标准互联网大项目架构”合理得多。

下一步我们其实可以正式进入**数据库和 API 的详细设计**了：把 `input_event` 到底记录哪些字段、Android 每个事件怎么对应数据库、`phrase_stat` 怎么计算、`completion_candidate` 怎么生成一次性定下来，这会直接决定后面的 Android 埋点怎么写。

**回答 ID：IME-ARCH-002**

手机一个目录，server用nodejs，api和后台接口放一起，一个目录，client用vue，一个目录，这样不是更好吗

对，我认为你这个拆法**比我上一版更适合这个项目**。

你实际上不是要“一个 Symfony 全家桶”，而是一个很清晰的三端项目：

```text
personal-ime/
│
├── android/        # 手机输入法
│
├── server/         # Node.js 后端
│
└── client/         # Vue 数据分析后台
```

而且仍然是**一个 Git 仓库，一个项目**，并没有增加管理复杂度。

### 我建议最终就定成这个结构

```text
personal-ime/
│
├── android/
│   └── YuyanIme/
│       ├── app/
│       ├── ...
│       └── build.gradle.kts
│
├── server/
│   ├── src/
│   │   ├── api/
│   │   │   ├── event/
│   │   │   ├── completion/
│   │   │   ├── device/
│   │   │   └── sync/
│   │   │
│   │   ├── dashboard/
│   │   │   ├── overview/
│   │   │   ├── phrase/
│   │   │   ├── clipboard/
│   │   │   └── application/
│   │   │
│   │   ├── analysis/
│   │   │   ├── phrase/
│   │   │   ├── prefix/
│   │   │   ├── completion/
│   │   │   └── clipboard/
│   │   │
│   │   ├── jobs/
│   │   │   └── analyze-input.job.ts
│   │   │
│   │   ├── database/
│   │   │
│   │   └── app.ts
│   │
│   ├── migrations/
│   ├── package.json
│   └── tsconfig.json
│
├── client/
│   ├── src/
│   │   ├── views/
│   │   │   ├── Overview/
│   │   │   ├── Input/
│   │   │   ├── Phrase/
│   │   │   ├── Completion/
│   │   │   ├── Clipboard/
│   │   │   └── Application/
│   │   │
│   │   ├── components/
│   │   ├── api/
│   │   ├── router/
│   │   └── stores/
│   │
│   └── package.json
│
├── docker-compose.yml
└── README.md
```

### `server` 里把所有后端能力放一起

这里我非常赞成你的想法。

不要：

```text
api-server
analysis-server
admin-server
sync-server
```

而是全部：

```text
server/
```

只是代码内部按照 module 分。

数据流就是：

```text
Android
   │
   │ POST /api/events/batch
   ↓
Node.js Server
   │
   ├── 数据写入
   ├── 输入分析
   ├── 高频词计算
   ├── 补全词计算
   ├── Clipboard 分析
   └── Dashboard API
           │
           ↓
       PostgreSQL
```

Vue 同样请求这个 Server：

```text
Vue Dashboard
      │
      ↓
GET /api/dashboard/overview

GET /api/dashboard/phrases

GET /api/dashboard/completions

GET /api/dashboard/clipboard
```

也就是：

```text
             PostgreSQL
                  ↑
                  │
               server
              Node.js
             ↙        ↘
       Android        Vue
```

这个结构非常舒服。

---

## Analysis 也不要单独一个服务

你的数据分析第一阶段本质就是：

```text
PostgreSQL
    ↓
查询 / 聚合
    ↓
生成统计表
    ↓
生成补全数据
```

所以直接：

```text
server/src/analysis/
```

就可以。

比如：

```text
PhraseAnalyzer
PrefixAnalyzer
CompletionAnalyzer
ClipboardAnalyzer
```

然后一个定时任务：

```text
AnalyzeInputJob
```

执行：

```text
Input Events
     ↓
PhraseAnalyzer
     ↓
PrefixAnalyzer
     ↓
CompletionGenerator
     ↓
completion_candidate
```

以后需要每 5 分钟跑一次，就：

```text
cron
↓
Node Job
```

不用再起一个 Python 服务。

---

# Node.js 在这里其实还有一个优势

因为你的：

```text
server
```

和：

```text
client
```

都可以统一使用：

**TypeScript**

于是很多东西未来可以共用。

例如 Server 定义：

```ts
interface DashboardOverview {
    inputChars: number;
    clipboardCount: number;
    completionCount: number;
}
```

Vue 也需要同样的数据结构。

以后项目稍微成熟一点，可以增加：

```text
packages/
└── shared/
```

变成：

```text
personal-ime/
├── android/
├── server/
├── client/
└── packages/
    └── shared/
```

放：

```text
DTO
API Types
Enums
Event Types
```

例如：

```ts
export enum InputEventType {
    Key = 'key',
    Commit = 'commit',
    CandidateCommit = 'candidate_commit',

    ClipboardChange = 'clipboard_change',
    Paste = 'paste',
    PasteInferred = 'paste_inferred',

    ExternalInsert = 'external_insert',
    ExternalDelete = 'external_delete',

    CompletionShow = 'completion_show',
    CompletionAccept = 'completion_accept',
}
```

Server 和 Vue 共用。

Android Kotlin 再维护对应的 enum 就行。

不过这个 **第一版甚至都不用建**，等真的出现大量重复类型再加。

---

# API 我建议也别区分两个服务器

只区分 URL：

```text
/api/v1/mobile/*
```

给输入法：

```text
POST /api/v1/mobile/events/batch

GET  /api/v1/mobile/completions

POST /api/v1/mobile/sync
```

然后：

```text
/api/v1/dashboard/*
```

给 Vue：

```text
GET /api/v1/dashboard/overview

GET /api/v1/dashboard/timeline

GET /api/v1/dashboard/apps

GET /api/v1/dashboard/phrases

GET /api/v1/dashboard/completions

GET /api/v1/dashboard/clipboard
```

但实际上都是：

```text
同一个 Node.js process
同一个 server 项目
同一个 PostgreSQL
```

逻辑上分开，物理上不拆。

---

# 部署也会非常简单

最终你的服务器可能只有：

```text
Nginx
│
├── /api/*
│       ↓
│    Node.js
│       ↓
│ PostgreSQL
│
└── /*
        ↓
    Vue dist
```

访问：

```text
https://ime.xxx.com/
```

就是你的个人输入分析后台。

Android：

```text
https://ime.xxx.com/api/v1/mobile/
```

上传数据。

Vue：

```text
https://ime.xxx.com/api/v1/dashboard/
```

读取统计。

甚至 **Vue build 完以后直接让 Nginx 提供静态文件即可**，不需要额外跑 Node 前端服务。

---

## 所以现在技术栈我会改成

```text
┌──────────────────────────────┐
│ Android                      │
│                              │
│ YuyanIme                     │
│ Kotlin                       │
│ Room / SQLite                │
│ PersonalCompletionEngine     │
└──────────────┬───────────────┘
               │ HTTPS
               ↓
┌──────────────────────────────┐
│ Server                       │
│                              │
│ Node.js + TypeScript         │
│ REST API                     │
│ Analysis                     │
│ Scheduled Jobs               │
│ Completion Generator         │
└──────────────┬───────────────┘
               │
               ↓
        ┌─────────────┐
        │ PostgreSQL  │
        └─────────────┘
               ↑
               │
┌──────────────┴───────────────┐
│ Client                       │
│                              │
│ Vue + TypeScript             │
│ Dashboard                    │
│ Charts                       │
│ Data Explorer                │
└──────────────────────────────┘
```

我认为这个版本已经比较接近**可以正式定下来的项目架构**了。

尤其对一个人开发来说，它满足几个重要条件：**一个仓库、只有三个清晰目录、后端只有一个服务、只有一个数据库、Analysis 不拆服务、Dashboard 不和 API 混代码，但部署仍然可以放一起。**

而且以后真加“表情包搜索、语音、AI”，基本都是往现有的 `server` 增加 Module，而不是重新设计架构。

**回答 ID：IME-ARCH-003**
