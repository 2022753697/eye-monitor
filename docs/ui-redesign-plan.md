# 眼互 EyeMonitor 前端 UI 全面改造计划

> 版本：v1.0（2025）｜产出方：UI Designer｜状态：规划稿（只做规格，不动代码）
> **决策批复（2026-10-02）**：① 品牌名 = **保留「恋视」**（不统一为眼互，strings 不动，反向在文档中注明产品名=恋视）② 实心按钮主色 = **切深玫瑰 primary_fill #D13F47**（AA 合规方案全量采纳）
> 范围：`android/app/src/main` 下全部 38 个布局 + 值资源 + `ui/`、`util/` 视觉相关代码
> 原则：**纯视觉重构，零业务逻辑改动**；一切改造落到 design token / 样式 / 布局层，Java 侧只允许新增「视觉工具类」与替换 Toast/空态/弹窗调用点。

---

## 0. 审计方法

- 逐一阅读 `res/layout/` 全部 38 个布局、`res/values/` 6 个资源、`res/drawable/` 82 个资源
- 抽样阅读 `ui/BaseActivity.java`、`util/UiDialogs.java`、`util/Transitions.java`、`ui/TrackReplayActivity.java`（空态/Toast 逻辑）
- 全量统计：Toast 调用 114 处（16 个文件）；硬编码字号分布；硬编码色值；圆角分布；elevation 分布；品牌词出现位置
- 对比度按 WCAG 2.1 相对亮度公式手工核算，标注「AA/AA大字号/不达标」，实施时用工具复核

---

## 1. 现状审计表

### 1.1 页面级审计（17 个 Activity 布局）

| # | 页面 | 现状要点 | 一致性痛点 | 可复用点 |
|---|------|----------|-----------|----------|
| 1 | activity_login | 渐变 hero 头（40/28dp padding，30sp 标题 + letterSpacing 0.1）；登录/注册双卡片；输入与按钮 52dp 固定高；`12dp`/`14dp` 字面量 | 头栏是「品牌 hero」风格，与其他页头栏完全两套；世界上独一无二的 30sp；卡片 elevation 2dp 但用的是 LinearLayout android:elevation | bg_card、EditText 样式、Button 样式、RadioButton 样式均已就位 |
| 2 | activity_main | 未配对：hero 28sp；已配对：聊天头（12dp padding，18sp 标题 + 10sp 状态行 + 2 个 40dp 白色圆角按钮）、消息列表 paddingTop 56dp、底部输入栏（mic/emoji 36dp、send 44dp、more 40dp）、引用条、录音条、更多面板 ViewPager2 200dp、爱心悬浮 | 聊天头是最复杂头栏：三行信息 + 双侧按钮，10sp 微文本在渐变上对比度 ~2.2:1 严重不足；图标按钮 36–40dp < 44dp 触控标准；`12/13sp`、`12dp` 字面量；contentDescription 中文字面量（"电量""充电状态"） | 更多面板页根已是 match_parent（护栏正确）；气泡/状态行/输入栏结构完整，只需令牌化 |
| 3 | activity_task | 头栏 56dp：icon back 36dp + 18sp 居中标题 + 发布按钮（**6 个属性样板覆盖** minHeight/inset/padding）；筛选 chips；列表；空态 = 裸 TextView + 80dp marginTop | 头栏与 template_page / Memo 系高度一致但返回控件形态不同；空态无图标；发布按钮样板多 | Chip.Selectable、item_task_card、bg_task_bubble_* 质量好 |
| 4 | activity_memo_list | 56dp 头栏 + HeaderText 新建按钮（同款 6 属性样板覆盖）；RecyclerView padding 12dp；空态裸 TextView | 头栏样板代码 ×3 文件重复 | HeaderText 样式思路正确 |
| 5 | activity_memo_detail | 56dp 头栏 + HeaderText 编辑；正文 17sp 硬编码（无令牌）；删除按钮 44dp + 6 属性样板 | 17sp 无令牌；删除按钮样板 | danger 按钮语义对 |
| 6 | activity_memo_edit | 56dp 头栏 + HeaderText 保存；bg_memo_item 行内 Switch 已用样式；图片条/子项条 | 头栏样板 ×3；提醒时间行 icon 18dp 偏小 | Switch 样式已收敛 |
| 7 | activity_map | 全屏地图：顶部双头像胶囊（bg_top_card）、头像下小菜单、设围栏底部面板 | 与品牌体系基本脱钩（地图页特质）；菜单分隔线用 1dp TextView 直画 | bg_top_card、fence 面板已用令牌 |
| 8 | activity_gallery | 渐变头栏（**无返回按钮**，返回在 FAB 扇形菜单里）；按天分组网格；批量栏（3 个 Outlined 按钮）；FAB + 4 个扇形气泡 TextView（52–56dp） | 头栏无返回但其他页都有——返回入口藏得深；扇形气泡是「TextView+drawable 伪按钮」，与 Button 体系两套语言；elevation 3dp 气泡 | 空态已存在（文本）；批量栏语义完整 |
| 9 | activity_media_picker | 白底 52dp 顶栏（回退 icon + 相册名 + `▾` 字面量）；网格；相册面板 elevation 8dp 360dp；底栏 56dp 发送按钮 + 6 属性样板 | 「顶栏」与全 App 渐变头栏体系不同（系统相册风格，可保留但需令牌化）；发送按钮样板；52dp 高度 | 预览层结构完成度高 |
| 10 | activity_media_view / video_player | 黑底 + 44dp 圆白返回按钮 | 与全 App 体系不同（沉浸页可保留）；无其他问题 | 两者的返回按钮形态已一致 |
| 11 | activity_permission | Header 胶囊返回（Button.Header + 文字「返回」）；7 个权限行为 = 同一模板 ×7 重复 | **状态色文字 `#E0913C` 对比度 2.53:1 不达标**；7 段重复结构（XML 无法循环，至少令牌化文案/颜色） | 行结构（标题+状态+描述+分隔线）值得抽成文档级「列表行组件」模板 |
| 12 | activity_device_status | Header 胶囊返回；bg_card_alt（**14dp 圆角非令牌**）状态卡 6 行 | 圆角 14dp 破令牌；行 padding 14dp 字面量 | 行结构可与 permission 统一为「设置行」组件 |
| 13 | activity_profile | Header 胶囊返回；92dp 头像；编辑卡片；服务器地址行 | 头栏返回形态（胶囊文字）与 Task/Memo（icon）不一致 | 表单卡片结构完整 |
| 14 | activity_anniversary | 头栏 padding 10dp（最矮）；胶囊返回 + 18sp 标题 + **56dp 假占位 View**（硬编码宽度）；空态裸 TextView；底部添加按钮 | 56dp 占位 View 是 hack；空态无图标；头栏是第五种形态 | item_anniversary / item_anniversary_heart 完成度好 |
| 15 | activity_track_replay | 地图 + 渐变头（icon back 40dp bg_icon_circle + 18sp）+ 底部 bg_card 控制面板（3 个 44dp Outlined 13sp + Play Primary） | 头栏第 4 种形态；空数据用 Toast + 文本进度（无空态） | 控制面板结构清晰 |
| — | template_page | HeaderTitle + Header「返回」胶囊 + 16dp 内容区 | **模板与多数真实页面不一致**（Task/Memo 用 icon 返回，Profile 用胶囊返回）→ 模板必须先行统一，否则护栏失效 | 模板本身是正确思路，需升级为统一 HeaderBar |

### 1.2 弹窗级审计（8 个 dialog + UiDialogs）

| # | 弹窗 | 现状 | 痛点 | 复用点 |
|---|------|------|------|--------|
| 1 | dialog_card（UiDialogs 全家） | 透明 window + bg_card 白卡 24dp padding；双按钮 Primary/Outlined 等宽；danger 时 tint status_error；宽度 0.85 屏 | 与 Material 语义一致，**整体最规范**；宽度 0.85/0.82 是代码魔法数字 | 可作为全 App 弹窗基准 |
| 2 | dialog_list | 标题 + 代码动态建行（48dp 行高、bg_row_ripple、16dp padding 均在 Java 里写死） | 行规格硬编码在 Java（密度乘法），应令牌化 | dangerIndex 行红色语义正确 |
| 3 | dialog_task_detail | ScrollView 根**无 bg_card**；操作按钮 = **TextView 伪按钮**（bg_pill_primary / bg_btn_outline_coral，46dp，999dp 圆角） | 与 Button 体系两套按钮语言；`17sp` 无令牌；10dp/14dp 字面量 | 时间线/配图条复用价值高 |
| 4 | dialog_task_publish | 输入 + 6 Chip 奖励选择 + 配图条 + 加图行；ScrollView 根无 bg_card | 结构佳；minHeight 46dp 输入 | Chip.Selectable 体系完整 |
| 5 | dialog_task_reject | 输入 + 4 Chip 快速理由 | 同上，注意「快速理由」文案规范 | Chip 复用 |
| 6 | dialog_monitor_permission | 引导行 + 26dp 状态圈（bg_select_badge_off）；提示 12sp | 26dp 角标非令牌；行 padding 14dp 字面量 | 行结构与 permission 页同源 |
| 7 | dialog_sos | 220dp 红色圆形 SOS + 白环 + 说明 | 特例保留（合法）；背景在代码设置透明 → 需确认容器圆角 | Widget.EyeMonitor.Button.Sos 已收敛 |
| 8 | dialog_anniversary_edit | EditText + **SwitchCompat 无样式**（tint 不跟随主色）；padding 24/8 | SwitchCompat 裸用，与 memo 页 Switch 样式脱节 | 其余字段 OK |
| + | dialog_create_folder / delete_folder | 与 dialog_card 同构、已用令牌 | 无 | **已是目标形态**（可作新弹窗模板） |

### 1.3 组件级审计

| 组件 | 现状 | 问题 |
|------|------|------|
| 按钮体系 | Default/Primary/Outlined/Header/HeaderText/Danger/Sos 7 样式齐全 | ① `cornerRadius=radius_lg(16dp)` 与 pill 伪按钮 999dp 并存；② Header 系列被 5 个布局用 6 属性覆盖样板「再压缩」；③ Primary 白字对比度 2.78:1 不达标 |
| 输入框 | Widget.EyeMonitor.EditText + bg_input | 52dp 固定高 ×5 文件；bg_input 14dp 圆角非令牌 |
| 卡片 | bg_card 16dp / bg_card_alt **14dp** | 14dp 破令牌；cardElevation 2dp 与非 CardView 平级 elevation 混用 |
| 空态 | component_empty_state.xml 存在但 **0 引用**；5+ 页面裸 TextView | 组件形同虚设；gallery/task/memo/anniversary/chat 五个空态文案、图标全各异 |
| Toast | 裸 `Toast.makeText` 114 处 / 16 文件 | 无统一视觉、无 icon、无主题化 |
| 头栏 | 5 种形态：hero(30sp)、chat(18sp+状态行)、std(HeaderTitle 20sp+胶囊)、icon(56dp 18sp+icon)、anniversary(10dp padding) | 高度约 52–88dp 不等；返回控件 3 种；假占位 View |
| 更多面板 | view_more_panel 200dp + ViewPager2 + 6dp 指示点；item_more_page1/2 根 match_parent | 页 2 只有 1 个真入口 + 3 个空占位块（视觉空洞）；6dp 点略小 |
| 聊天气泡 | self=primary 16/4 角、peer=白+描边、system=surface_alt 12dp 胶囊 | 气泡语言统一良好；时间戳 10sp 对比度不足；自我气泡渐变仅 task_bubble 有（45° #FF6B6B→#FFA26B 硬编码） |
| 列表项 | item_* 共 20 个，bg_card 白块为主 | margin/padding 字面量 4/8/10/12/14dp 混用；缩略图无圆角（gallery 网格 cell 120dp 方形） |
| 触控 | 聊天栏 icon 36–40dp、头栏返回 36dp、select badge 22–26dp、更多页 cell ≈60dp | 多处 <44dp（聊天栏/头栏返回）；badge 为非交互可豁免但建议 26→28dp 视觉 |
| 图标 | 82 个 drawable，全部 vector + 运行时 tint | 无尺寸/语义分层文档；部分 icon 16dp 内容过密（emoji 面板、alarm 18dp） |

### 1.4 硬数据汇总（审计证据）

```
硬编码字号（布局内，无对应令牌）：
  10sp ×14 ｜ 11sp ×21 ｜ 17sp ×2 ｜ 18sp ×7 ｜ 24sp ×1 ｜ 28sp ×1 ｜ 30sp ×1
非令牌圆角：14dp（bg_input / bg_card_alt / bg_btn_white_rect / 电池 icon）
drawable 内硬编码色：#E3F3EB(bg_pill_green) #FBEAE6(bg_pill_red) #14FF6B6B(bg_row_ripple)
              #FF6B6B→#FFA26B(bg_task_bubble_self 渐变未引用颜色令牌)
elevation 分布：1dp×1 2dp×6 3dp×4 4dp×10 6dp×2 8dp×1（无令牌文档）
Toast：114 处 / 16 文件
按钮 6 属性样板覆盖（minHeight/insetTop/insetBottom/paddingTop/paddingBottom/paddingStart 等）：5 文件
52dp 固定高度：5 文件（login/main/profile/media_picker/album_item）
品牌词：strings.xml「恋视」11 处 + MonitorService 5 处 + AccessibilityDiagnostic 1 处；仓库名/包名/文档「眼互」
空态组件引用数：0；空态裸 TextView 页面数：5（task/memo/anniversary/gallery 有文、track 用 Toast）
contentDescription 中文字面量：activity_main（电量/充电状态/蓝牙）、item_*（缩略图/无图/纪念日）等
「他/她」与「TA」混用：task_publish_hint 用「他/她」、anniversary 系列用「TA」
```

---

## 2. Design Tokens 升级（具体值）

### 2.1 颜色体系（colors.xml 扩展与精修）

新增/调整如下（实施时用 WebAIM Contrast Checker 复核，误差 ±0.05 内）：

```xml
<!-- ── 主色分层（品牌色保持，功能色分离） ────────────────── -->
<color name="primary">#FF6B6B</color>          <!-- 品牌珊瑚粉：渐变/装饰/图标（保留） -->
<color name="primary_dark">#E05555</color>     <!-- 保留 -->
<color name="primary_fill">#D13F47</color>     <!-- 新增：实心按钮底，白字 4.67:1 ✅AA -->
<color name="primary_text">#C03D3D</color>     <!-- 新增：浅底上的珊瑚文字（链接/描边按钮），on-white 5.28:1 ✅AA -->
<color name="accent">#FFA26B</color>           <!-- 保留：暖橙，仅用于装饰/图标/强调 -->
<color name="accent_text">#C97A3D</color>      <!-- 新增：暖橙文字（如奖励文案），on-white ≥4.5:1 ✅ -->

<!-- ── 文本层级 ────────────────────────────────────── -->
<color name="text_primary">#4A3B3D</color>     <!-- 保留：on-white 8.6:1 ✅ -->
<color name="text_secondary">#7A696C</color>   <!-- 调整（原 #9A8B8D 仅 3.10:1 ❌）：on-white 5.16:1、on-bg 4.92:1 ✅ -->
<color name="text_tertiary">#A99A9C</color>    <!-- 新增：仅用于图标/时间戳等非关键信息（不承诺 AA） -->
<color name="text_on_primary">#FFFFFF</color>  <!-- 保留 -->
<color name="text_on_primary_muted">#E6FFFFFF</color> <!-- 调整（原 #B3FFFFFF ❌）：90% 白，用于头栏次要文字 ≥12sp -->

<!-- ── 状态文字变体（填充色保留用于装饰/图标；文字一律用变体保证 AA） ── -->
<color name="status_ok_text">#2E7D63</color>       <!-- 4.97:1 ✅ -->
<color name="status_warn_text">#9A6217</color>     <!-- 5.08:1 ✅（替换 #E0913C 的文字用法） -->
<color name="status_error_text">#B33A2C</color>    <!-- 5.90:1 ✅（替换 #D9604F 的文字用法） -->
<color name="status_success_text">#167A4C</color>  <!-- 5.35:1 ✅ -->

<!-- ── 状态浅底（pill 面）令牌化（替换 drawable 内硬编码） ── -->
<color name="surface_ok">#E3F3EB</color>       <!-- 原 bg_pill_green 硬编码 -->
<color name="surface_error">#FBEAE6</color>    <!-- 原 bg_pill_red 硬编码 -->
<color name="surface_press">#14FF6B6B</color>  <!-- 原 bg_row_ripple 硬编码（主色 8% 压暗） -->

<!-- ── 头栏渐变（标准页用加深端，标题 20sp 粗体=大字号，白字 ≥3:1） ── -->
<!-- bg_header_gradient_std：#EE5F68 → #D96F52（270°），白字大字号 3.25:1 ✅ -->
<!-- bg_header_gradient（hero/聊天）：保留 #FF6B6B→#FFA26B（装饰性，见 4.1 说明） -->
<color name="header_grad_start">#EE5F68</color>
<color name="header_grad_end">#D96F52</color>

<!-- ── 遮罩/分层 ── -->
<color name="scrim_header">#1A000000</color>   <!-- 新增：聊天头渐变上的微压暗带（改善微文本对比） -->
<color name="media_overlay_tint">#59FF6B6B</color> <!-- 保留 -->

<!-- ── 间距/留白（dimens.xml 追加） ── -->
<dimen name="space_40">40dp</dimen>
<dimen name="space_48">48dp</dimen>
<dimen name="space_56">56dp</dimen>
<dimen name="space_60">60dp</dimen>
<dimen name="space_64">64dp</dimen>
```

**对比度核算依据（关键项）**：

| 组合 | 现值 | 目标 | 整改 |
|------|------|------|------|
| 白字 on 主色按钮 #FF6B6B | **2.78:1 ❌** | ≥4.5 | 按钮底改 `primary_fill #D13F47`（4.67:1） |
| 珊瑚文字 on 白（描边/链接） | **2.78:1 ❌** | ≥4.5 | 文字改 `primary_text #C03D3D`（5.28:1） |
| text_secondary on bg/surface | **3.10/3.26:1 ❌** | ≥4.5 | 改 `#7A696C`（5.16/4.92:1） |
| status_warn #E0913C 文字 | **2.53:1 ❌** | ≥4.5 | `status_warn_text #9A6217`（5.08:1） |
| status_error #D9604F 文字 | **3.66:1 ❌** | ≥4.5 | `status_error_text #B33A2C`（5.90:1） |
| 白字 on 渐变尾端 #FFA26B | **1.97:1 ❌❌** | 大字号≥3 | 标准头栏改 `#EE5F68→#D96F52`，标题统一 20sp 粗（3.25:1 ✅） |
| 白字 on 渐变头端 #FF6B6B | **2.78:1 ❌** | 大字号≥3 | 同上（3.25:1 ✅） |
| 头栏 10sp 状态微文本 on 渐变 | ~2.2:1 ❌ | — | 上移/加 scrim 带 + 字号 ≥12sp + 90% 白；记录为已知残差 |

> 设计说明：品牌珊瑚粉 #FF6B6B 保留用于 hero 渐变、爱心 icon、头像环等**非文字**场合；**承载文字的交互面**（按钮、链接、状态文字）全部切到 AA 合规变体。这是「品牌色 + 功能色」分层，观感上珊瑚变深约一档，属预期。

### 2.2 字号体系（补齐 8 级节奏）

```xml
<!-- 现有：12/13/14/15/16/20｜追加补齐，覆盖全部现用值 -->
<dimen name="text_10">10sp</dimen>  <!-- 仅限角标数字/非关键标注（如电池格），不承诺 AA -->
<dimen name="text_11">11sp</dimen>  <!-- 仅限图注/状态 pill/时间戳 -->
<dimen name="text_17">17sp</dimen>  <!-- 正文大号（memo 正文、任务详情内容） -->
<dimen name="text_18">18sp</dimen>  <!-- 头栏次级标题（统一后尽量 20sp；18 保留给聊天头两行场景） -->
<dimen name="text_24">24sp</dimen>  <!-- hero 副标题 -->
<dimen name="text_28">28sp</dimen>  <!-- hero 标题（Main 配对面板） -->
<dimen name="text_30">30sp</dimen>  <!-- hero 标题（Login） -->
```

层级规范（强制）：
- 页面标题 = `TextAppearance.EyeMonitor.HeaderTitle`（20sp 粗，白）→ **头栏物理高度 ≥56dp**
- 正文 = 14sp；正文强调 = 15–16sp 粗；卡片标题 = 16sp 粗（Title）
- 辅助 = 12sp（Label）；**内容性文本禁止 10/11sp**（仅角标/时间戳/图标旁注可用，且色值用 text_tertiary）

### 2.3 间距 / 高度 / 圆角

```xml
<!-- 追加：触控与结构高度 -->
<dimen name="touch_target">44dp</dimen>   <!-- 最低触控目标 -->
<dimen name="bar_height_compact">44dp</dimen>
<dimen name="bar_height">48dp</dimen>     <!-- 标准按钮/行高（8dp 节奏 6×8） -->
<dimen name="bar_height_hero">56dp</dimen> <!-- 头栏/主操作 -->
<dimen name="radius_full">999dp</dimen>   <!-- 胶囊（替换魔法 999dp） -->

<!-- 治理：删除/替换所有布局内 52dp 固定高 → bar_height(48dp) 或 space_56(56dp)；
     圆角 14dp → radius_md(12dp)；bg_dot 6dp → 8dp -->
```

圆角语义化（一处生效）：
- `radius_sm 8dp`：小角标/小图
- `radius_md 12dp`：输入框、白色描边钮、system tip、quote 条、bubble（气泡 16/4 组合保留）
- `radius_lg 16dp`：卡片、标准按钮、菜单卡
- `radius_xl 24dp`：底部面板/大图卡
- `radius_full`：胶囊、状态 pill、SOS 圆

### 2.4 阴影层级（elevation 令牌，dimens 落值 + 文档注释）

```xml
<dimen name="shadow_1">2dp</dimen>  <!-- 静态卡片（bg_card 搭配） -->
<dimen name="shadow_2">4dp</dimen>  <!-- 悬浮条/底栏/头栏按钮感 -->
<dimen name="shadow_3">8dp</dimen>  <!-- 浮层（相册面板/弹窗） -->
<dimen name="shadow_4">12dp</dimen> <!-- 顶层层（SOS/大浮层） -->
```
治理：布局一律引用以上，删除散落 1/3/6dp 值（3→4、6→8 就近归并）。

### 2.5 图标与触控尺寸

```xml
<dimen name="icon_16">16dp</dimen>  <!-- 行内小 icon（禁更小） -->
<dimen name="icon_20">20dp</dimen>
<dimen name="icon_24">24dp</dimen>  <!-- 标准工具栏 icon -->
<dimen name="icon_36">36dp</dimen>  <!-- 聊天栏按钮（配 44dp 触控热区） -->
<dimen name="icon_44">44dp</dimen>  <!-- 头栏返回/页级操作 -->
```
规则：
- 头栏返回 = 44dp 热区 + 24dp 图形（IconButton 样式统一）
- 聊天底栏 mic/emoji/more 36dp **图形** + 44dp 热区（`android:padding` 外扩或父容器 minHeight 44dp）
- 选择角标（22–26dp）为非交互指示，统一 24dp；指示点 6→8dp

### 2.6 动效

| 令牌 | 值 | 用途 |
|------|----|------|
| duration_fast | 150ms | 按压反馈/icon 切换 |
| duration_normal | 250ms | 面板滑入滑出/淡入 |
| duration_slow | 300ms | 页面转场（沿用 Transitions：slide_in_right + fade） |
| reduce-motion | 尊重系统动画缩放 | `Transitions.push/up/pop` 在 `Settings.Global.ANIMATOR_DURATION_SCALE==0` 时跳过 |

---

## 3. 全局组件改造规格

### 3.1 HeaderBar（头栏统一）——最高优先级

**新增 include 组件 `component_header_bar.xml` + 样式族**，根因：5 种头栏形态 + 5 个布局的 6 属性按钮样板。

```
组件规格（标准页形态）：
┌────────────────────────────────────────┐
│ [◀ 44dp]        标题 20sp 粗        [操作] │  ← 渐变 bg_header_gradient_std
└────────────────────────────────────────┘
- 高度：minHeight 56dp + wrap_content（不锁死，兼容 200% 字号）
- 返回：ImageButton 44dp 热区 / 24dp ic_back / bg_header_icon（#26FFFFFF 圆形）tint 白
- 标题：居中（weight=1），TextAppearance.EyeMonitor.HeaderTitle（20sp 粗 = 大字号，白字 ≥3:1 ✅）
- 右操作槽：44dp 热区，允许 Header 胶囊 / HeaderText / Icon
```

配套样式：
```xml
<!-- 消除 6 属性样板：替代 5 个布局里的手工 minHeight/inset/padding 覆盖 -->
<style name="Widget.EyeMonitor.Button.HeaderAction" parent="Widget.EyeMonitor.Button.Header">
    <item name="android:minHeight">44dp</item>
    <item name="android:paddingStart">@dimen/space_16</item>
    <item name="android:paddingEnd">@dimen/space_16</item>
    <!-- 已继承 inset=0 / paddingV=4dp / elevation=0 -->
</style>

<!-- 头栏圆icon返回底（替代 bg_icon_circle 在渐变上的生硬观感） -->
<!-- drawable/bg_header_icon：oval #26FFFFFF -->
```

三套头栏变体（共享令牌，结构性差异合理保留）：
1. **标准 HeaderBar**（Task/Memo/Anniversary/Profile/Permission/DeviceStatus/Gallery/Media 全适用）→ include 组件
2. **聊天头**（Main 已配对）：渐变 + 20sp 标题（原 18sp 上调）+ scrim 带 + 状态行 12sp/90% 白 + 双白钮 40dp→44dp
3. **Hero 头**（Login/Main 未配对）：保留大字号品牌感，标题统一 28sp（Login 30sp→28sp 归并），副标题 13sp

同时更新 `template_page.xml` 为新 HeaderBar（护栏与真实页面重新对齐）。

### 3.2 按钮体系（消除两套按钮语言）

- `Button.Primary`：backgroundTint → `primary_fill`（AA）；字号 15sp 粗保留；高度规范 = bar_height 48dp（布局不再写 52dp）
- `Button.Outlined`：strokeColor/textColor → `primary_text`；高度 44–48dp
- `Button.Danger`：backgroundTint → status_error_fill（保留 #D9604F 填充，白字 3.66:1 大字号边缘 → 建议暗一档 #C94A3B 复核）；文字用途一律 status_error_text
- **伪按钮清零**：dialog_task_detail 的 `TextView+pill` 操作钮 + gallery 扇形气泡 `TextView` → 全部换成真实 Button / ImageButton 样式（视觉变化最小化：圆角 16dp 与 pill 并列过渡，最终统一 lg 16dp）
- 新增 `Widget.EyeMonitor.Button.Dialog`（弹窗内 44dp 紧凑、字 14sp）供 task 弹窗使用

### 3.3 卡片

- `Widget.EyeMonitor.Card`（CardView 16dp/2dp elevation）作为**卡片唯一来源**；`bg_card`（shape）保留给非 CardView 容器
- `bg_card_alt` 圆角 14→16（radius_lg），`item_media_folder`/`item_anniversary` 的 margin 4/10dp 统一 12dp
- 图库网格缩略图：cell 内 ImageView 加 6dp 圆角（`bg_media_thumb_radius` 新 shape 或 clip），与全 App 圆角语言一致

### 3.4 空态组件（落地，消除 0 引用）

`component_empty_state.xml` 已具备 icon 64dp + 标题 + 副文案 + 主按钮，规格微调：
- icon 64dp 置于 `bg_icon_circle` 底上（64dp surface_alt 圆）α 1.0、tint primary
- 标题 = Title 16sp 粗 textPrimary；副文案 = Label 12sp（text_tertiary）
- 主按钮 = 默认隐藏，`es_action` 48dp
- **落地方式**：每个列表页布局 include 一次 + 新增 `EmptyStateUtil.show(activity, containerId, iconRes, title, sub, actionLabel?, onClick?)`（Java 仅新增工具类，不进入业务逻辑）
- 接入页面：Task（💌 图标）、Memo（📝）、Anniversary（❤️）、Gallery 文件夹视图、Chat（💬）、Track 无数据（复用，替换 Toast 方案）
- 空态文案表见 §5

### 3.5 弹窗统一（UiDialogs 升级）

- 窗口宽度魔法数字 0.85/0.82 → `dimens.xml` `dialog_width_factor` 注释令牌（代码可读引用）+ 统一 padding：正文区 20dp 横 / 12dp 纵
- dialog_task_detail / publish / reject：根加 `bg_card` + 圆角 24dp（xl），操作钮换 Button.Dialog（Primary/Outlined），删除 pill 伪按钮
- dialog_anniversary_edit：Switch 换 `Widget.EyeMonitor.Switch`
- dialog_monitor_permission：行 padding 字面量 → space_14 令牌；角标 24dp
- dialog_sos 保留原设计（特例）
- dialog_create_folder / delete_folder：**确立为新弹窗模板**，新弹窗一律照此结构

### 3.6 Toast / Snackbar（统一）

新增 `util/Toasts.java`（或 `UiToast`）：
```java
Toasts.show(Context, CharSequence);            // 短，圆角深色 pill + 品牌 icon
Toasts.showOk(Context, CharSequence);          // ✅ 成功态
Toasts.showWarn(Context, CharSequence);        // ⚠️ 警示态
Toasts.showError(Context, CharSequence);       // ✕ 错误态
```
- 视觉：bg_toast（#E6000000 圆角 pill 24dp）+ 白字 14sp + 左侧 18dp tint icon；LENGTH_SHORT 统一
- 迁移策略：分阶段替换 114 处调用（Phase 2 高频 9 页 ≈60 处 → Phase 3 余量），**不改变任何业务参数/时长语义**
- 提示类（非操作反馈）与确认类弹窗职责分离：仍是确认 → UiDialogs.confirm；仅告知 → Toasts；可操作反馈（撤销等）暂不引入 Snackbar（控制改动面），列为后续选项

### 3.7 底部面板 / 列表项

- view_more_panel：ViewPager2 高度 Token `panel_height_more 200dp`；指示点 6→8dp、选中态用 primary、未选中 surface_variant；**页根保持 match_parent（护栏，禁止改动）**；页 2 去掉 3 个空占位 LinearLayout，未上线入口前隐藏该页（dots 同步 2→1）或补 3 个真实入口（P3 产品决策）
- view_emoji_panel：高度 220dp Token；item 48dp 保留
- item_chat_self/peer：paddingStart/End 60dp → space_60；时间戳 10sp→11sp + text_tertiary；引用条色条 3dp → 统一 accent；item_chat_media 触摸菜单热区抬到 44dp
- item_task / item_task_card：11sp/10sp 字面量 → 令牌；状态 pill 底色 token 化（surface_ok/surface_error/surface_variant）
- item_more_page1/2：icon 44dp 槽 + 11sp 旁注 → icon_44 + text_12（旁注升 12sp，icon 槽含 44dp 热区）

---

## 4. 逐页改造清单（17 界面 + 8 弹窗）

> 每项「现状 → 改法」；凡未列出的页 = 仅令牌化处理。所有改动**不触碰 ID、状态逻辑、点击回调、数据绑定**。

### 4.1 activity_login
- 现状：hero 30sp + 40/28 padding；双卡 52dp 输入/按钮；12dp 字面量 ×7；品牌「恋视」。
- 改法：Hero 标题 30sp→`text_28`；padding → space_24/space_20 令牌；卡片 padding 24 保留；输入/按钮 52dp→`bar_height`(48dp)；`layout_marginBottom="12dp"`→`@dimen/space_12`；登录注册双面板按钮文案与主色按钮用 `primary_fill`；「恋视 · 恋爱守护」按品牌决策改（见 §5.1）；服务器行 padding 14dp→space_14。`tv_login_status` 文字 `@color/text_primary`→`?attr/textPrimary`。

### 4.2 activity_main
- 现状：聊天头三行一体 10sp 微文本 + 40dp 白钮；底栏 36–40dp icon；rv paddingTop 56dp 字面量；contentDescription 中文字面量。
- 改法：①聊天头：标题 18sp→`HeaderTitle` 20sp 粗；状态行整体下沉为**单行 12sp #E6FFFFFF** + 头栏底部接入 `scrim_header` 微压暗带；地图/图库双钮 40dp→44dp 高、13sp→text_13 令牌、bg_btn_white_rect 圆角 14→12；②底栏：mic/emoji/more 图形 36dp 不变 + 44dp 热区（改 `android:padding` 布局, minWidth/MinHeight 44dp）；send 44dp 保留；③`rv_chat` paddingTop 56dp→`@dimen/space_56` 注释保留（爱心占位）；④配对面板 hero 28sp；⑤contentDescription 全部改 `@string/`；⑥聊天空态用 component_empty_state（💬 图标 +「配对成功，开始聊天吧」）。

### 4.3 activity_task + item_task + item_task_card
- 现状：56dp 头栏 + 6 属性按钮样板；空态裸文本；item 内 10/11sp 字面量。
- 改法：头栏换 `component_header_bar`（icon 返回 + 20sp 标题 + HeaderAction「发布」）；发布按钮样板删除；空态 → EmptyState（💌 +「还没有任务，发布第一个吧」+ 主按钮直达发布弹窗——**仅 UI 接线，点击逻辑回调用已有方法**）；筛选 chips padding 8dp→space_8；`tv_task_empty` 移除。item_task：字号/色令牌化，pill 底色 token。item_task_card：`bg_pill_gray` 状态 pill 按语义换 surface token；margin 16/8 → tokens。

### 4.4 activity_memo_list / detail / edit + item_memo_card
- 现状：三文件重复 56dp 头栏样板；17sp 无令牌；空态裸文本；删除钮 6 属性样板。
- 改法：三头栏换 `component_header_bar`（Icon 返回 / HeaderAction 新建·编辑·保存）；正文 17sp→`text_17`；删除按钮 → HeaderAction 同族 Danger 紧凑样式（44dp）；空态 → EmptyState（📝 +「还没有备忘录」）；item_memo_card：64dp 缩略图加圆角、margin 令牌化、右侧角标列 16dp icon → icon_16 令牌。

### 4.5 activity_map / activity_track_replay
- 现状：全屏地图两类浮层；轨迹头栏第 4 种形态；空数据 Toast。
- 改法：轨迹头栏换 HeaderBar（标准渐变 + 20sp 标题 + 44dp 返回，保留全屏浮层定位）；底部控制面板：三个 44dp Outlined 13sp 保留 + 按钮色 `primary_text`；空数据 → 地图中央 EmptyState 浮层（可关闭，替代 Toast+文本）；Map 顶部小菜单分隔线改用 divider 色、菜单项触控行 44dp、`top_tools_menu` 圆角 → radius_md。

### 4.6 activity_gallery + item_media_*
- 现状：头栏无返回；空态裸文本；批量栏 3 钮；FAB 扇形气泡 TextView 伪按钮；folder 卡 margin 4dp。
- 改法：头栏换 HeaderBar（返回走 HeaderBar 而非 FAB 菜单，**FAB 扇形保留上传/选择/新建**）；扇形气泡 4 个 → 统一圆钮样式（bg_bubble_menu 保留视觉，但文字令牌化、热区 ≥52dp 已有）；空态 → EmptyState（🖼️ +「暂无共享媒体」+ 上传主按钮）；批量栏按钮 → Button.Outlined 44dp；grid cell 120dp 保留 + 缩略图圆角 6dp、角标 24dp；item_media_folder margin 4dp→space_4 令牌；item_media_day_header 12sp 令牌。

### 4.7 activity_media_picker（+ album/media items）
- 现状：白顶栏 52dp + `▾` 字面量 + 发送钮 6 属性样板 + 52dp 相册行。
- 改法：顶栏高度 52dp（系统相册风，保留）但高度改 `bar_height_hero`(56dp) 令牌化，`▾` 入 strings（`picker_album_arrow`）；发送钮样板 → HeaderAction；相册行 52dp→48dp；select badge 22→24dp；album panel 高度 360dp + elevation 8dp → shadow_3 令牌；底栏 56dp 保留令牌 space_56。

### 4.8 activity_media_view / activity_video_player
- 现状：黑底 + 44dp 圆白返回（两文件一致）。
- 改法：仅令牌化 margin（space_12/space_24 已有），无结构改动；返回按钮包一层 44dp 热区确认。

### 4.9 activity_permission
- 现状：7 段重复行结构；状态色文字 2.53:1 不达标；胶囊文字返回。
- 改法：头栏换 HeaderBar；**状态文字颜色全部切 `status_ok_text / status_warn_text / status_error_text` 语义**（按权限状态着色）——这是本页最大收益；行结构在文档级抽「设置行」规格（标题 15sp + 状态 13sp + 描述 12sp + divider），代码逐行改令牌即可；`permission_tip` 文案品牌词随决策。

### 4.10 activity_device_status
- 现状：bg_card_alt 14dp + margin 12；行 padding 14dp 字面量；胶囊返回。
- 改法：头栏换 HeaderBar；bg_card_alt 圆角→16、margin→space_12；行 padding→space_14；状态值文字色：在线=status_ok_text、离线=text_tertiary。

### 4.11 activity_profile
- 现状：胶囊返回；92dp 头像；表单 52dp；服务器行。
- 改法：头栏换 HeaderBar；输入/保存 52dp→48dp；头像 92dp 保留 + 加 3dp 主色描边环（新 drawable bg_avatar_ring）品牌点睛；divider 行令牌化。

### 4.12 activity_anniversary + dialog_anniversary_edit + item_anniversary(heart)
- 现状：10dp padding 头 + 56dp 假占位 View；空态裸文本；SwitchCompat 无样式。
- 改法：头栏换 HeaderBar（**删除 56dp 假占位**——HeaderBar 右侧动作槽等宽结构天然居中）；标题 18sp→20sp；空态 → EmptyState（❤️ +「还没有纪念日」+ 主按钮添加（接线已有回调））；dialog_anniversary_edit：Switch→`Widget.EyeMonitor.Switch`、padding 令牌化；item_anniversary：marginBottom 10dp→space_12、11sp 徽标令牌；item_anniversary_heart：42/48dp → icon_44 体系。

### 4.13 dialog_task_detail / publish / reject
- 现状：无 bg_card 根；pill 伪按钮；17sp；10/14dp 字面量。
- 改法：三弹窗根 LinearLayout 包 bg_card + radius_xl 24dp（视觉与 UiDialogs 对齐）；操作钮 `TextView+pill` → `Button.Dialog`（Primary/Outlined 各一，保留 46dp 高度语义→44dp）；内容 17sp→text_17；Chip 区 minHeight 34dp→`space_36?`（34 保留，圆角/字号令牌化）；配图条 72dp/80dp → space_64 族（72/80 保留亦可，标记 instance），推荐 72dp 卡通高度维持。

### 4.14 dialog_monitor_permission / dialog_sos
- monitor：行 padding 字面量→space_14；26dp 角标→24dp（或维持 26 记 `space_24`）；「去设置」跳转行热区 ≥44dp（padding vertical 14×2 已达标）。
- sos：保留 220dp 圆钮 + bg_sos_press（白环 4dp 保留）；说明文字 12sp → 13sp 微升；无其他改动。

### 4.15 dialog_create_folder / delete_folder / dialog_card / dialog_list
- 现状：已符合目标形态。
- 改法：仅将其标记为**弹窗模板**；Java 侧对话框宽度 0.85/0.82 魔法数字改为读 `R.dimen.dialog_width_percent_confirm = 0.85`（dimen float 可存 fraction,存 `fraction` type 或注释常量，实施时选型），行高 48dp→`bar_height`。

### 4.16 其余列表项（item_chat_*, item_emoji, menu_chat_item, view_*）
- item_chat_self/peer：60dp padding→space_60；时间 10sp→text_11 + text_tertiary；引用条圆角 6→radius_sm；peers 名称 12sp→text_12。
- item_chat_system：bg_system_tip 12dp 圆角已有 → radius_md 令牌（视觉不变）；文字 text_12。
- item_chat_media：占位 icon 32dp、说明 12sp 令牌化；长按菜单热区。
- item_emoji：48dp/24sp 保留（防宽字形裁切注释保留）。
- menu_chat_item：圆角 10→radius_md、padding 令牌化。
- view_anniversary_card / info_window：字号/圆角令牌化；无结构改动。

### 4.17 template_page.xml（护栏更新）
- 改为 include `component_header_bar` + 内容区 16dp；注释更新：头栏规格、触控 44dp、禁 52dp、禁 14dp 圆角、禁 10/11sp 正文、空态引用方式。**护栏与实现重新对齐是本计划前提。**

---

## 5. 文案体系规范

### 5.1 品牌名统一（产品决策点 P0，需批复）

现状：**strings.xml 全部使用「恋视」11 处 + 通知/服务/下发文案**；仓库名 `eye-monitor`、包名 `com.eyemonitor`、`docs/ui-guide.md`、styles 注释均用「眼互」。

- **推荐方案**：统一为 **「眼互」（EyeMonitor）**——与工程资产 100% 一致，仅需改 strings.xml 11 处 + MonitorService 5 处 + AccessibilityDiagnostic 1 处；App 名作为品牌资产后续可再议。
- 备选方案：保留「恋视」，则需反向改仓库/包/文档（工程量大且易漏）。
- 副标题统一为一句 slogan：现有 `main_subtitle`「恋爱守护 · 相互看见」与 `login_subtitle`「恋视 · 恋爱守护」合并为 **「眼互 · 相互看见」**（login_subtitle 同步）。

### 5.2 语气规范

- 第二人称「你」，称呼对方统一 **「TA」**（禁止「他/她」「他」「她」混用；task_publish_hint 等 3 处待改）
- 句式：短句 + 动词开头；结果优先（"已保存""任务已发布 💌"）
- 礼貌但不过度：不用"亲爱的"式昵称文案（UI 文案中性；昵称留给用户数据）
- 数字/时间：「还有 %1$d 天」「已 %1$d 天」「距离 %1$s 还有 %2$d 天」保持现格式

### 5.3 Emoji 规范

- **允许**：空态/成功反馈/庆祝类 + 图标语义相关的场景，**每串文案 ≤1 个**（空态标题除外，可 1 个）
- **禁止**：按钮、错误提示、权限引导、状态文本、正式标签
- 现有清理：`gallery_folder_new "➕ 新建文件夹"`（按钮语）→ 去掉 ➕（或保留 ➕ 改为图标复用）；`task_toast_*` 成功反馈保留；`anniversary_*` ❤ 保留
- 全角符号：省略号统一 `…`（半角三点）；分隔统一 `·` 与 `> `（"查看全部 ›" → "查看全部 ›" 保留）

### 5.4 按钮文案动词表（全局统一）

| 场景 | 文案 | 备注 |
|------|------|------|
| 关闭弹窗 | 取消（Outlined） | 恒左 |
| 确认 | 确定 / 创建 / 保存 / 发送 / 删除 / 兑现 / 完成 | 按动作字 |
| 返回 | 返回（HeaderBar icon 无字；首屏/登录场景可文字） | |
| 危险 | 删除 / 解除配对 / 清空聊天 / 拒绝任务 | Danger 样式恒右 |
| 空态主按钮 | 去添加 / 去上传 / 开始聊天 / 发布任务 / 去设置 | 与空态图标呼应 |

现状清查：`gallery_folder_back_all "← 全部"`（item_folder_header 硬编码"← 全部"）→ strings 化；`task_tap_detail "点我看看"` → 「查看详情」；`pick 文案` 检查无遗漏。

### 5.5 空态文案表（统一结构：图标 + 标题 + 副文案 + 主按钮?）

| 页面 | 图标 | 标题 | 副文案 | 主按钮 |
|------|------|------|--------|--------|
| 聊天 | 💬 | 开始你们的对话吧 | 配对成功后就可以互发消息、语音和图片啦 | — |
| 任务 | 💌 | 还没有任务 | 发布第一个小任务，让 TA 开心一下 | 发布任务 |
| 备忘录 | 📝 | 还没有备忘录 | 随手记下想和 TA 分享的点滴 | 新建备忘录 |
| 纪念日 | ❤️ | 还没有纪念日 | 记录属于你们的重要日子，一起倒数 | 添加纪念日 |
| 共享图库 | 🖼️ | 还没有共享媒体 | 上传照片/视频，随时翻看彼此的日常 | 去上传 |
| 轨迹回放 | 📍 | 暂无轨迹数据 | 换个时间段再看看 TA 的足迹吧 | — |
| 相册（picker） | 🖼️ | 相册是空的 | 去拍一张或从其他相册选择 | — |

提示类多行文案（permission/monitor 弹窗）：保留结构化「1.… 2.…」 + 加粗步骤号，避免大段散文。

---

## 6. 可访问性检查（WCAG AA + 触控）

| 项 | 现值 | 目标值 | 处理 |
|----|------|--------|------|
| 按钮白字/主色底 | 2.78:1 ❌ | ≥4.5:1 | primary_fill #D13F47（4.67:1） |
| 珊瑚文字/浅底 | 2.78:1 ❌ | ≥4.5:1 | primary_text #C03D3D（5.28:1） |
| text_secondary 正文 | 3.10:1 ❌ | ≥4.5:1 | #7A696C（5.16:1） |
| 状态色文字(4 个) | 2.5–3.7:1 ❌ | ≥4.5:1 | 新增 *_text 变体（4.97–5.90:1） |
| 头栏白字 20sp 粗 | — | ≥3:1（大字号） | 标准渐变 #EE5F68→#D96F52 + 标题统一 20sp 粗 |
| 头栏 10sp 微文本 | ~2.2:1 ❌ | 记录残差 | scrim 带 + 12sp + 90% 白；仍不足处列入已知残差表 |
| 触控目标 | 36–40dp（聊天栏/返回） | ≥44dp | 热区扩到 44dp，图形 36dp 内嵌 |
| 选择角标/指示点 | 22–26dp/6dp | 非交互可豁免 | 视觉统一 24dp / 8dp |
| 键盘/焦点 | 现状 OK | 保持 | HeaderBar 组件统一后复核 focus order |
| contentDescription | 中文字面量若干 | @string/ | 全量迁移 |
| 字号缩放 200% | 未验证 | 布局不锁死高度 | 标准头栏 minHeight + wrap；列表项避免固定高 |
| 动效 | 无开关 | 尊重系统动画 | Transitions 读 ANIMATOR_DURATION_SCALE |

**残差记录（验收时明知项）**：聊天头状态行 12sp #E6FFFFFF 在渐变上约 2.6:1（依赖 icon 冗余信息，文档记录）；SOS 红钮白字 #D9604F 3.66:1（大字号 16sp 粗接近达标，记录）。

---

## 7. 分期落地计划

> 每期独立提交、可回滚（全改动仅 res/ + 新增 util/Toasts.java、EmptyStateUtil.java + 调用点文本替换）。**任何一期禁止触碰业务逻辑、数据库、网络、ID。**

### Phase 1｜令牌与全局组件（地基，改完整个 App 自动换血）

**内容**
1. colors.xml：新增 §2.1 全部色令牌；text_secondary 重调
2. dimens.xml：新增 space_40~64、text_10~30、touch_target、bar_height 族、radius_full、shadow_1~4、icon_*、panel 高度令牌
3. drawable：bg_header_gradient_std、bg_header_icon、bg_toast、bg_media_thumb_radius、bg_avatar_ring；bg_pill_green/red、bg_row_ripple、bg_task_bubble_self 硬编码色 → @color 令牌
4. styles.xml：Button.Primary/Outlined/Danger 换 token 色；新增 Button.HeaderAction / Button.Dialog；HeaderTitle 保持 20sp；Card/card_alt 圆角统一
5. 布局全局清扫：14dp 圆角→radius_md、52dp→bar_height、12/13/16dp 字面量→令牌、10/11sp 正文→令牌或上迁（机械替换，可脚本辅助）
6. component_header_bar.xml + template_page 更新 + 5 个头栏样板布局切换（Task/Memo×3/Anniversary 先切，其余页 P2 切）
7. UiDialogs：宽度常量令牌化、dialog 内 padding/按钮样式统一
8. Toasts.java + EmptyStateUtil.java 新建（**先建后用，P2 开始替换调用**）
9. 构建检查 `checkUiStyle` 白名单更新：禁 52dp/14dp 圆角/裸 10-11sp 正文/`红色`硬编码（drawable 外）

**验收标准**：`gradlew checkUiStyle` 通过；git diff 中 `**/java/` 仅新增 2 个工具类 + UiDialogs 读令牌；抽样对比度 5 处 ≥ 表值；全项目 grep 无 52dp、无 14dp 圆角、无 drawable 内硬编码（除遗留特例）；头栏模板单一来源；手感回归：登录/主界面/任务/备忘录各过一遍。

**风险**：视觉一次变色（按钮/正文灰）——预期内全量变化；若产品对 `primary_fill` 深玫瑰抵触，备选「按钮保留 #FF6B6B + 白字 15sp 粗，记入残差表」（**P1 决策点**）。

### Phase 2｜高频页面（9 页，用户日活核心）

**内容**：Main（聊天头/底栏热区/空态/文案品牌词）、Login（hero/按钮高度/品牌）、Task 页+弹窗×3+item×2、Memo 页×3+item、Anniversary 页+item+item_heart+dialog、Gallery 页+item_media_*、Profile、Permission、DeviceStatus（全部换 HeaderBar + EmptyState + Toasts 替换 ≈60 处调用点）。

**验收标准**：9 页走查截图与审计表逐项对照；HeaderBar 覆盖 100%（标准页）；空态 5+ 处均用组件；Toast 出现处带统一 pill 视觉；无新增硬编码；`git diff --stat` 显示布局改动集中在上述 9 页；业务回归：配对/收发消息/发布任务/存备忘录/传图片/改资料各一次。

**风险**：Main 聊天头改动最敏感（v 布局多层嵌套）——只改尺寸/色/字号不动结构；若改出问题可单独 revert 该 commit。

### Phase 3｜长尾 + 收尾

**内容**：Map/TrackReplay/MediaPicker/MediaView/VideoPlayer 令牌化；dialog_monitor_permission / sos 微调；item_emoji/menu_chat_item/view_* 收尾；剩余 ~54 处 Toast 迁移；品牌词全量替换（若 P0 获批）；contentDescription strings 化；more 面板页 2 决策（补位或隐藏）；README + ui-guide.md 升级为 v2（含 token 表/组件表/残差表）；全量 A11y 扫描（Accessibility Scanner 或脚本对比度核验）出报告归档。

**验收标准**：全部 38 布局走查表签核；A11y 扫描 0 blocker（残差表条目逐一确认）；strings.xml 品牌词一致、emoji 规范命中；`checkUiStyle` 通过；无任何业务逻辑 diff。

---

## 8. 关键设计决策摘要（供快速批复，10–15 条）

1. **品牌名：保留「恋视」**（P0 已批复 2026-10-02）：strings.xml 保持不动，工程/文档中「眼互」仅作工程代号，产品对外品牌=恋视；文案规范其他项照常（TA 统一/emoji 规范/按钮动词表）。
2. **按钮/链接/状态文字全部切换到 AA 合规色**：primary_fill #D13F47、primary_text #C03D3D、text_secondary #7A696C、四个 status_*_text；品牌珊瑚 #FF6B6B 退回装饰/渐变/图标用途。
3. **标准头栏单一来源**：`component_header_bar` include（56dp 起、44dp 返回热区、20sp 粗标题=大字号 AA）；Task/Memo/Anniversary 等 8 页先切，删除 anniversary 56dp 假占位 View。
4. **标准页渐变加深** `#EE5F68→#D96F52`（白字大字号 3.25:1 AA）；Login/Main hero 保留 vivid 渐变 + 28sp 标题（装饰用途）。
5. **新增 Button.HeaderAction / Button.Dialog**，消灭 5 个布局的 6 属性按钮样板；弹窗伪按钮（pill TextView）清零。
6. **消灭 52dp**：输入/按钮统一 48dp（bar_height），保持 8dp 节奏；`12dp/14dp/16dp` 字面量全部令牌化。
7. **空态组件落地**：component_empty_state + EmptyStateUtil 接入 6+ 页面，附统一空态文案表。
8. **Toast 收敛**：新建 Toasts.java（pill + icon + 语义色），114 处分两期替换，不改任何业务参数。
9. **更多面板页根 match_parent 护栏保留**（ViewPager2 改高只动 view_more_panel）；页 2 空占位 P3 决策（补入口或隐藏）。
10. **聊天头**：标题 18→20sp、状态行 12sp/90% 白 + scrim 压暗带、双白钮 44dp；10sp 微文本从渐变上禁止。
11. **触控 ≥44dp**：聊天栏 icon 36dp 图形外套 44dp 热区；头栏返回统一 44dp。
12. **动效**：150/250/300ms 三档令牌 + 尊重系统动画缩放（Transitions 现有实现做 reduce-motion 判断）。
13. **弹窗模板确立**：dialog_create_folder 为基准（bg_card + 24dp padding + 双钮）；UiDialogs 宽度魔法数字令牌化。
14. **文案**：TA 统一替代他/她；每字符串 emoji ≤1 且禁用于按钮/错误/状态；按钮动词表落地；Glide 类文案不涉及。
15. **分期**：P1 令牌+全局（一次变色，可回滚）→ P2 高频 9 页 → P3 长尾+品牌+无障碍复检；每期独立提交验收，不改业务逻辑。

---

## 9. 验收方式与工具

- 构建检查：`./gradlew checkUiStyle`（更新版白名单）
- 对比度：WebAIM Contrast Checker 复核 §2.1 表内全部值（±0.05）
- 走查：每期截图对照审计表；P3 用 Android Accessibility Scanner 全量扫描归档
- 回归：关键链路（配对→聊天→任务→备忘录→图库→设置）手工过一遍
- diff 审计：每期确认 `java/` 无业务逻辑改动（仅工具类新增与调用点文本级替换）

---

*本文档为规划稿；**P0（品牌名）/P1（按钮色）已批复**：品牌名保留「恋视」（strings.xml 不动，工程文档品牌词后续注释对齐）；按钮切深玫瑰 #D13F47 全量采纳。*