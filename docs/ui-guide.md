# 眼互 UI 设计指南 v2（2026-10 改造版）

> 基于 `docs/ui-redesign-plan.md` 落地成果。v1 见 git 历史。
> 品牌：产品名「恋视」（strings 保持），工程代号「眼互/EyeMonitor」。品牌珊瑚 #FF6B6B 用于装饰/渐变/图标；**承载文字的交互面一律用 AA 变体**。

## 1. Design Tokens

### 颜色（colors.xml）
| Token | 值 | 用途 | 对比度 |
|-------|----|------|--------|
| primary | #FF6B6B | 渐变/装饰/图标（品牌色） | — |
| primary_fill | #D13F47 | **实心按钮底** | 白字 4.67:1 ✅ |
| primary_text | #C03D3D | 描边按钮/珊瑚文字 | on-white 5.28:1 ✅ |
| accent | #FFA26B | 装饰/强调图标 | — |
| accent_text | #C97A3D | 暖橙文字 | ≥4.5 ✅ |
| text_primary | #4A3B3D | 主文本 | 8.6:1 ✅ |
| text_secondary | #7A696C | 次要文本 | 5.16:1 ✅ |
| text_tertiary | #A99A9C | 图标/时间戳（不承诺 AA） | — |
| text_on_primary_muted | #E6FFFFFF | 头栏次要文字 | 90% 白 |
| status_ok_text | #2E7D63 | 成功文字 | 4.97:1 ✅ |
| status_warn_text | #9A6217 | 警示文字 | 5.08:1 ✅ |
| status_error_text | #B33A2C | 错误文字 | 5.90:1 ✅ |
| status_error_fill | #C94A3B | 危险按钮底 | 白字 ≥4.0 ✅ |
| header_grad_start/end | #EE5F68/#D96F52 | 标准页头栏渐变 | 白字大字号 3.25:1 ✅ |
| surface_ok/error/press | #E3F3EB/#FBEAE6/#14FF6B6B | pill 浅底/按压 | — |

**规则**：状态色（status_ok/warn/error/success）的**填充**用于装饰/图标；**文字**必须用 `*_text` 变体。

### 字号（dimens.xml）
`text_10`（仅角标）`text_11`（图注/时间戳）`text_12`（Label）`text_13/14/15/16/17`（正文）`text_18`（次级标题）`text_20`（HeaderTitle 页标题）`text_24/28/30`（hero）。
**禁止**：正文内容性文本用 10/11sp（仅角标/时间戳可用，配合 text_tertiary）。

### 间距/圆角/高度
- 间距：space_2~64（8dp 步进 + 28）
- 圆角：radius_sm 8 / md 12（输入/白钮）/ lg 16（卡片/标准按钮）/ xl 24（底部面板）/ full 999（胶囊）
- 高度：touch_target 44（最低触控）/ bar_height 48（标准按钮/输入）/ bar_height_hero 56（头栏）
- **禁止 52dp 固定高、禁止 14dp 圆角**（checkUiStyle 拦截）
- 阴影：shadow_1 2 / 2 4 / 3 8 / 4 12

## 2. 组件规范

### 头栏（component_header_bar.xml）
- 标准页 = include 组件：标准渐变 `bg_header_gradient_std` + 44dp 圆底返回（bg_header_icon）+ 20sp 居中标题（HeaderTitle）
- 右动作：`Button.HeaderAction`（44dp 热区胶囊）或 `Button.HeaderText`（次级纯文字）
- 变体：聊天头（vivid 渐变 + 状态行 11sp/90% 白 + 44dp 双钮）、Hero（Login/Main 未配对 28sp）
- **不要再手工写 minHeight/inset/padding 六件套**（HeaderAction/Dialog 样式已内置）

### 按钮
Primary（primary_fill）/ Outlined（primary_text 描边）/ Danger（status_error_fill）/ HeaderAction / HeaderText / Dialog / DialogOutlined。**禁止 TextView 伪按钮**（bg_pill_* 只用于状态 pill 非按钮语义）。

### 空态（component_empty_state + EmptyStateUtil）
图标落 bg_icon_circle 圆底 + 标题 + 副文案 + 可选主按钮。文案模板见 §4。

### Toast（Toasts）
`Toasts.show / showRes / showLong / showOk / showWarn / showError`——深色圆角 pill + 语义 icon、居中。**禁止裸 Toast.makeText**（checkUiStyle 建议阶段）。

## 3. 已知残差（验收记录）
| 项 | 说明 |
|----|------|
| 聊天头状态行（11sp #E6FFFFFF on 渐变） | ~2.6:1，依赖图标冗余信息，记录 |
| SOS 红钮白字 | 3.66:1，大字号接近达标，记录 |
| Gallery 扇形 FAB 菜单 | 保留（产品特例），文案已令牌化 |
| 更多面板页 2 空占位 | P3 产品决策：补真实入口或隐藏 |

## 4. 空态文案表
| 页面 | 图标 | 标题 | 副文案 | 主按钮 |
|------|------|------|--------|--------|
| 任务 | 💌 ic_task | 还没有任务 | 发布第一个小任务，让 TA 开心一下 | 发布任务 |
| 备忘录 | 📝 ic_memo | 还没有备忘录 | 随手记下想和 TA 分享的点滴 | ＋ 新建 |
| 纪念日 | ❤️ ic_task | 还没有纪念日… | 记录属于你们的重要日子，一起倒数 | 添加纪念日 |
| 图库根 | 🖼️ ic_image | 还没有共享媒体 | 上传照片/视频，随时翻看彼此的日常 | — |
| 图库文件夹 | 🖼️ | 文件夹空 | 这个文件夹还是空的，去上传一张吧 | — |

## 5. 文案规范（摘要）
- 称呼对方统一 **TA**（禁 他/她 混用）
- Emoji：每字符串 ≤1，禁用于按钮/错误/权限引导
- 按钮动词表：取消（恒左）/ 确定·保存·删除·兑现（按动作）/ 危险操作恒右 Danger
- contentDescription 一律 `@string/cd_*`

## 6. 检查工具
- `./gradlew assembleDebug`（挂接 checkUiStyle：裸 Button、布局硬编码色、52dp/14dp 等违规即失败）
- 对比度用 WebAIM Contrast Checker 复核；A11y 用 Android Accessibility Scanner