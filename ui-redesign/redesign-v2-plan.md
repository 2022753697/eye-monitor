# 恋视（眼互）UI 改造计划 v2 —— 系统性收紧与聊天页重点整改

> 版本：v2.0（2026-11）｜产出方：UI Designer｜状态：规划稿（**只做设计规格与 HTML 原型，不改任何 android/ 代码**）
> 依据：`docs/ui-guide.md`（设计令牌 v2，色板/字号/组件已落地）+ `docs/ui-redesign-plan.md`（v1 审计与已落地项）
> 产品名：**恋视**（工程代号眼互）；品牌珊瑚 `#FF6B6B` 只用于装饰/渐变/图标，**承载文字的交互面一律用 AA 变体**。
> 范围：`ui-redesign/redesign-v2-plan.md`（本文档）+ `ui-redesign/prototypes/*.html`（8 个手机视口原型，390×844 逻辑像素）。

---

## 0. 改造背景与目标

用户明确不满（本轮改造重点）：

| # | 不满点 | 现状根因（对照令牌） | v2 目标 |
|---|--------|---------------------|---------|
| 1 | 聊天头栏「地图」「图库」两个按钮占掉近一半宽度 | 双按钮 = 图标 18dp + 文字 13sp「地图/图库」+ padding 12dp×2 + margin 8dp ≈ **180dp / 390dp（≈46%）**；`padding="12dp"` 字面量 | 收窄为**纯图标 44dp 热区**（24dp 图形 → 视觉 20dp），双钮合计 ≈ 96dp（≈25%）；或降级到头栏「⋯」溢出层（备选方案 B） |
| 2 | 头栏左侧状态行（电量 100%、4G、蓝牙、在线）**太大** | 状态行 = 3 个图标（12×16 / 10×12）+ 4 段 11sp 文字，margin 2–6dp 字面量；三行结构（标题行/typing/状态行）把头栏撑到 ≈100dp；90% 白 on vivid 渐变 ~2.6:1 残差 | 状态行**收为紧凑单行**：图标缩到 10×13 / 8×10，文字统一 `text_11` + `text_on_primary_muted`，margin 2dp，段间用 `·` 分隔；底部压 `scrim_header` 微压暗带 |
| 3 | 聊天消息的时间、「已读」等辅助文字**太大** | 时间/已读用 `text_11` + `text_secondary(#7A696C)`（AA 灰，视觉偏重） | 收敛到**图注级**：`text_11` + `text_tertiary(#A99A9C)`（时间戳/图注专用，非关键信息可豁免 AA）；媒体气泡时间 10sp 字面量 → `text_11` 令牌 |
| 4 | 整体「不太符合要求」 | 各页头栏 5 种形态残留（Profile/Permission/DeviceStatus 仍是「标题左置+胶囊返回右置」）；字号/留白字面量散落；若干 `<44dp` 触控 | 一轮**系统性收紧**：字号层级、留白 8dp 节奏、触控 ≥44dp、头栏统一 `component_header_bar`、气泡辅助文字收敛图注级、更多面板页 2 填空位 |

**与 v1 的关系**：v1 已落地（`component_header_bar`、`HeaderAction/Dialog` 样式、AA 色板、EmptyState 组件、Task/Memo/Anniversary/Gallery 已换头栏）。**v2 不重复已落地项**，聚焦：①聊天页头栏/状态行/辅助文字（用户不满重灾区）；②把未切到统一头栏的 4 页（Profile/Permission/DeviceStatus/TrackReplay）切齐；③页面级密度与触控清零；④更多面板页 2 填空位。

---

## 1. 改造总纲（跨页统一收口清单）

### 1.1 字号层级（强制）

| 层级 | 令牌 | 用途 | v2 强制规则 |
|------|------|------|------------|
| hero | `text_28` | Login/Main 未配对大标题 | 已归并（Login 30→28） |
| 页标题 | `text_20` 粗 | 所有标准头栏标题（HeaderTitle） | 聊天头也 20sp（已落地） |
| 次级标题 | `text_18` | 聊天两行场景标题（暂不用） | 保持 20sp 优先 |
| 正文强调 | `text_15/16` 粗 | 卡片标题、任务内容、权限行标题 | 保持 |
| 正文 | `text_14` | 默认正文（Body） | 保持 |
| Label | `text_12` | 辅助说明、状态 pill、more 面板旁注 | 旁注从 11sp 升到 12sp |
| 图注/时间戳 | `text_11` + `text_tertiary` | **聊天气泡时间、已读、任务卡片日期、memo 角标** | 颜色必须 `text_tertiary`（视觉收敛，非关键信息豁免 AA）；**禁止再配 text_secondary** |
| 角标 | `text_10` | 仅角标数字（等级徽章、媒体网格内提示字） | 网格内提示 10sp 升 11sp（见图库） |

> 用户不满 #3 的直接解药：**图注级 = text_11 + text_tertiary**，一处令牌、全局生效。

### 1.2 触控尺寸（≥44dp 清零）

| 现状 <44dp | 位置 | v2 方案 |
|-----------|------|---------|
| 32dp | 聊天引用条取消钮 `btn_quote_cancel` | 44dp 热区（padding 外扩） |
| 36dp | 录音条 `btn_record_cancel`（90×36） | 高度 44dp（bar_height_compact） |
| 36dp | 纪念日卡编辑/删除 `btn_anni_edit/delete` | 44dp 热区 + 24dp 图形 |
| 40dp | 备忘录编辑「＋ 添加子项」`btn_memo_item_add` | 44dp（bar_height_compact） |
| 22–26dp | 媒体选择角标（非交互） | 视觉统一 24dp（可豁免） |
| 6dp | 更多面板指示点 | 8dp |
| 42/48dp | 爱心悬浮/纪念日 heart 项 | 统一 icon_44 体系 |

### 1.3 头栏统一（消灭第 4/5 种形态）

标准页（Profile/Permission/DeviceStatus/TrackReplay）一律换 `component_header_bar`：
- 渐变 `bg_header_gradient_std`（#EE5F68→#D96F52，白字大字号 3.25:1 ✅）
- 返回 = 44dp 圆底 icon（左）
- 标题 = 20sp 粗居中（HeaderTitle）
- 右动作 = HeaderAction 胶囊 / HeaderText 文字 / Icon（44dp 热区）

**聊天头**为保留变体（vivid 渐变 + 状态行），但按 §2.1 收紧；**Hero 头**（Login/Main 未配对）保留。

### 1.4 气泡辅助文字收敛（用户不满 #3 的组件级落点）

| 元素 | 现状 | v2 |
|------|------|----|
| 自己气泡 时间/已读 `tv_chat_read/tv_chat_time` | 11sp + text_secondary | 11sp + **text_tertiary** |
| 对方气泡 时间 `tv_chat_time` | 11sp + text_secondary | 11sp + **text_tertiary** |
| 媒体气泡 时间 | 10sp 字面量 | 11sp + **text_tertiary** |
| 引用条名字 `tv_ref_name` | 11sp + primary(#FF6B6B ❌) | 11sp + **primary_text**（AA） |
| 引用条内容 `tv_ref_text` | 12sp + text_secondary | 12sp + text_secondary（内容性，保留 AA） |
| 系统提示 `item_chat_system` | 12sp 字面量 | 12sp 令牌 + text_secondary |
| 任务气泡时间/状态 | 10sp/11sp + text_secondary | 11sp + text_tertiary / 语义 pill |
| 任务卡片日期 | 11sp + text_secondary | 11sp + text_tertiary |
| 备忘录角标数 | 11sp + text_secondary | 11sp + text_tertiary |

---

## 2. 逐页改造计划

> 每页 = 现状问题（对照令牌指出违规/失衡点）→ 改造方案 → 影响到的文件清单（layout + values + java 视觉引用）。
> 所有改动**不触碰 ID、状态逻辑、点击回调、数据绑定**；java 侧仅允许替换色值/字号常量引用与新增视觉工具调用点。

### 2.1 聊天页 `activity_main`（重点页）

**现状问题**
1. 头栏信息密度失衡：`btn_chat_map` + `btn_chat_gallery` 各含 18dp 图标 + 13sp 文字 + 12dp×2 padding ≈ 90dp，双钮合计 ≈180dp（~46% 屏宽），且 `padding="12dp"`、`textSize="13sp"`、`layout_marginStart="8dp"` 字面量。
2. 状态行过重：图标 12×16/10×12dp、margin 2/4/6dp 字面量、四段文字全 11sp，视觉与标题行挤成三行头栏（≈100dp 高）；90% 白 on vivid 渐变仅 ~2.6:1（已知残差，无 scrim 兜底更差）。
3. 头栏 `tv_typing_hint` 12sp 字面量；`rv_chat paddingTop="56dp"` 字面量。
4. 引用条/录音条/底栏字面量：`btn_quote_cancel` 32dp（<44）、`btn_record_cancel` 90×36dp（<44）、`et_chat_input` 46dp、底栏 `padding="6dp"`、margin 4dp 多处字面量。
5. 更多面板：指示点 6dp（<8dp 视觉）、页 2 两个空占位块（视觉空洞）。
6. 状态值文字 11sp 但全部 `text_on_primary_muted`，其中网络/在线为纯文字无图标冗余，仍依赖图标冗余信息 → 收紧时保留图标+加 scrim。

**改造方案（改后见 `prototypes/chat.html`）**
- **头栏重排（三行 → 两行，高度 ≈76dp）**：
  - 行 A：左 = 标题 20sp 粗（HeaderTitle）+ 好感度徽章（10sp 角标）｜右 = **两个纯图标钮**（44dp 热区 ×2，24dp 图形视觉 20dp，`bg_btn_white_rect` 圆角 14→12，tint 白），删除按钮内文字 → 需新增 strings `cd_btn_map/cd_btn_gallery`（contentDescription）。
  - 行 B：状态行**紧凑单行**：图标缩为 10×13（电量）/8×10（充电、蓝牙），文字 `text_11` + `text_on_primary_muted`，margin 2dp，段间加 `·` 分隔（复用 `peer_status_line` 格式思路，Java 拼接处不动、仅布局收紧）；整行底部叠加 `scrim_header` 微压暗带（`bg_header_scrim` 新 drawable：渐变底部 10% 黑带，或直接用 View + `@color/scrim_header`）。
  - typing 提示行并入行 B 或维持隐藏态（visibility 切换，不动逻辑）。
- **气泡辅助文字**：时间/已读 → `text_11` + `text_tertiary`（见 §1.4，对应 item_chat_* 4 个文件）。
- **底栏收紧**：`et_chat_input` 46dp → `@dimen/bar_height_compact`(44dp)；底栏 padding 6dp → `@dimen/space_8`；图标钮保持 44dp（已达标）；引用条取消钮 32→44dp 热区；录音条取消钮 36→44dp 高。
- `rv_chat paddingTop` 56dp → `@dimen/space_56`（注释保留爱心占位语义）。
- 更多面板：指示点 6→8dp；**页 2 两个空占位 → 真实入口「纪念日」「亲密度」**（strings 已存在 `anniversary_title` / `profile_affection_section`），消除视觉空洞。

**影响文件**
- layout：`activity_main.xml`、`view_more_panel.xml`、`item_more_page1.xml`、`item_more_page2.xml`、`view_emoji_panel.xml`
- values：`dimens.xml`（无新增，用现有）、`colors.xml`（如加 `bg_header_scrim` 相关色则新增 scrim 渐变 drawable）、`strings.xml`（`cd_btn_map`、`cd_btn_gallery`，纯新增）
- drawable：`bg_btn_white_rect`（圆角 14→12）、`bg_dot`（6→8dp）、`bg_header_scrim`（新增）
- java 视觉引用：`ui/MainActivity.java`（仅布局相关 setText 不改；若 dots 数量随页 2 变化则动 `vp_more` 注册处——**本 v2 保留 2 页不删**，故 Java 基本零改动）

### 2.2 聊天气泡与消息项（item_chat_self / peer / system / media）

**现状问题**
- self/peer 的 `paddingStart/End="60dp"` 字面量；self 时间/已读 11sp+text_secondary（用户不满 #3）；peer 名字 `text_12` + `@color/primary`(#FF6B6B，on-white 2.78:1 ❌)。
- media 时间 10sp 字面量、占位提示 12sp 字面量、voice 气泡 minHeight 44dp 已达标。
- system 提示 12sp 字面量 + padding 14/5 字面量。

**改造方案**
- self：`paddingStart 60dp → @dimen/space_60`、`paddingEnd 12dp → @dimen/space_12`；已读/时间 → `text_11` + `text_tertiary`；引用条：名字 → `primary_text`、圆角 → `radius_sm`、padding 4/10/5 → 令牌。
- peer：`paddingEnd 60dp → @dimen/space_60`；名字 → `primary_text`（AA 5.28:1 ✅）；时间 → `text_11` + `text_tertiary`。
- system：12sp → `@dimen/text_12`；padding → `space_14`/`space_6` 族；背景圆角已有 12dp → 引用 `radius_md` 令牌（视觉不变）。
- media：时间 10sp → `text_11` + `text_tertiary`；提示 12sp → `text_12`；`fl_media_container` 长按/触摸菜单热区抬到 44dp（注释说明，Java 长按回调不动）。

**影响文件**
- layout：`item_chat_self.xml`、`item_chat_peer.xml`、`item_chat_system.xml`、`item_chat_media.xml`
- values：`dimens.xml`（无新增）
- java：`ui/MainActivity.java`（引用条/媒体菜单仅绑定，不改）

### 2.3 更多面板（view_more_panel / item_more_page1/2）

**现状问题**：指示点 6dp；页 1 旁注 11sp（正文性文字，按令牌 11sp 仅限图注）；页 2 两个 `LinearLayout weight=1` 空占位（v1 记录 P3 决策未决）；`padding 8dp` 字面量。

**改造方案**
- 指示点 6→8dp、选中态 `@color/primary`、未选中 `surface_variant`（Java dots 逻辑不动，仅布局尺寸）。
- 页 1/2 旁注 11sp → `@dimen/text_12`（Label 级，正文性文字不允许 11sp）。
- **页 2 补位（本 v2 决策）**：清空聊天 / 打卡 / **纪念日**（`anniversary_title`，ic_heart）/ **亲密度**（`profile_affection_section`，ic_heart 或 ic_affection）。两空占位删除；页数保持 2（dots 不变，Java 改动最小）。
- 网格 cell：icon 44dp 槽保留；padding 8dp → `@dimen/space_8`；icon 底 `bg_icon_circle` 保留。

**影响文件**
- layout：`view_more_panel.xml`、`item_more_page1.xml`、`item_more_page2.xml`
- values：`strings.xml`（如新增 cd，非必须）、`dimens.xml`（无新增）
- drawable：`bg_dot`（6→8dp）
- java：`ui/MainActivity.java`（页 2 两入口点击绑定，纯 UI 接线，不触业务）

### 2.4 资料页 `activity_profile`

**现状问题**
- 头栏形态不一致：标题左置 + 右侧「返回」胶囊按钮（`Button.Header`），与统一 HeaderBar（icon 返回左置 + 标题居中）不符（第 4 种形态）。
- 表单 label margin `6dp` 字面量；`row_server_url` paddingVertical 10dp 字面量。
- **AA 违规**：`tv_aff_level`（28sp 大数字）用 `@color/primary`(#FF6B6B) on-white **2.78:1 ❌**（大字号需 ≥3:1）；周对比数值 `textColor="@color/primary"` 同违规；「修改」链接 `@color/primary` 同违规。
- 头像 92dp + `bg_avatar_ring` 已达标（保留）。

**改造方案**
- 头栏 → include `component_header_bar`（std 渐变、icon 返回、标题「我的主页」20sp 居中）。
- label margin 6dp → `@dimen/space_8`（8dp 步进）或 space_4；server row padding → `@dimen/space_10`。
- 亲密度数值/周对比数值/「修改」链接 → **`primary_text`(#C03D3D, 5.28:1 ✅)**；进度条 tint 可留 primary（装饰）。
- 其余（输入 48dp、卡片 padding、divider）已达标，不动。

**影响文件**
- layout：`activity_profile.xml`
- values：无新增（色值现成）
- java：`ui/ProfileActivity.java`（如 Java 里 setTextColor primary 的 3 处数值/链接 → 改 primary_text，纯视觉引用）

### 2.5 地图 `activity_map`

**现状问题**
- 顶部小菜单：分隔线用 1dp TextView + `@color/text_secondary`（应为 divider 语义色）；菜单项 padding 8dp 字面量（触控行需 ≥44dp）；菜单文字 11sp 字面量。
- 围栏面板 elevation `6dp` 字面量（shadow 令牌化：6→8 归并 shadow_3）；`top_avatar_card` padding 12/6 字面量、marginTop 16dp 字面量。
- 无头栏（沉浸地图页，产品特质保留，不算违规）。

**改造方案**
- 菜单分隔线 → `@color/divider`；菜单项热区 ≥44dp（padding 8dp→`space_8` + 最小 44 高）、文字 11sp→`@dimen/text_12`。
- `top_tools_menu` 圆角 → `radius_md`；avatar 卡 padding → `space_12/space_8` 令牌、marginTop → `@dimen/space_16`。
- 围栏面板 elevation 6dp → `@dimen/shadow_2`(4dp) 或 shadow_3(8dp)（就近归并，建议 4dp 贴合底栏感）；按钮高度默认（48dp）达标。
- 状态提示（地图就绪等）建议底部小胶囊 11sp + text_secondary（新增可选，非必须）。

**影响文件**
- layout：`activity_map.xml`
- values：`dimens.xml`（无新增）
- java：`ui/MapActivity.java`（菜单分隔线色若在 Java 设置则改，通常为布局内）

### 2.6 图库 `activity_gallery`（+ item_media_*）

**现状问题**
- 头栏已 include `component_header_bar` ✅；但 FAB 扇形菜单里仍有「返回」气泡（`btn_gallery_back`）与头栏返回重复（返回入口藏 FAB 里，v1 已指出）。
- 扇形气泡 = TextView 伪按钮，`textColor="@color/primary"`（2.78:1 ❌），elevation 3dp 字面量。
- 批量栏按钮 wrap_content（高度默认达标）；`item_media_gallery` 占位提示 10sp（角标级，正文提示不允许）；选择角标 26dp（→24dp）；视频角标 26dp。
- `item_media_folder` margin 4dp / padding 10dp 字面量；「查看更多 ›」`@color/primary` ❌。

**改造方案**
- **删除 FAB 扇形中的「返回」气泡**（返回由头栏承担）；扇形保留 上传 / 选择 / 新建文件夹 三项；气泡文字色 → `primary_text`、elevation → `shadow_2`(4dp)。
- 批量栏 3 钮 → `Button.Outlined` 44dp（现状 wrap_content 48dp 已达标，仅确认）；`tv_batch_count` 13sp → `text_13` 令牌。
- 网格 cell：占位提示 10sp→`@dimen/text_11`（角标允许）或 12sp（图注）；选择角标 26→24dp（`bg_icon_circle` 视觉）；thumb 圆角已用 `bg_media_thumb_radius` ✅。
- 文件夹卡：margin/padding → `space_4`/`space_10` 令牌；「查看更多 ›」→ `primary_text`；`tv_folder_name` 15sp bold ✅。

**影响文件**
- layout：`activity_gallery.xml`、`item_media_gallery.xml`、`item_media_folder.xml`、`item_media_day_header.xml`、`item_folder_header.xml`
- values：无新增
- java：`ui/GalleryActivity.java`（扇形菜单去掉返回项：数据/动画数组删一项，纯视觉接线）

### 2.7 任务 `activity_task`（+ item_task / item_task_card / 3 弹窗）

**现状问题**
- 头栏：`btn_task_publish` 是 **TextView 套 HeaderAction 样式**（伪按钮，v1 弹窗伪按钮已清零、头栏伪按钮未清零）；`padding="8dp"` 字面量。
- `item_task`（聊天气泡形态）：`tv_task_time` 10sp 字面量 + text_secondary（→ 11sp+tertiary）；`tv_task_reward` `@color/accent`(#FFA26B) **on-white 对比不足（~2.4:1 ❌）** → accent_text；标题 12sp+primary ❌ → primary_text；状态 11sp。
- `item_task_card`：日期 11sp + text_secondary → 11sp + tertiary；状态 pill `bg_pill_gray` 无语义（按状态换 `surface_ok/surface_error/surface_variant` + `*_text` 色）；奖励 `@color/accent` ❌ → accent_text。
- 弹窗 task_detail/publish/reject：根无 `bg_card`、操作钮 TextView+pill 伪按钮（v1 已列，需落地确认）、17sp 无令牌、10/14dp 字面量。

**改造方案**
- 头栏：`btn_task_publish` → 真 `Button` + `Button.HeaderAction`（胶囊，44dp 热区）；chips padding → `space_8`。
- item_task：标题 → `primary_text`；时间 → `text_11`+`tertiary`；奖励 → `accent_text`；状态底行 → 语义 pill（已接受=surface_ok+status_ok_text；已拒绝=surface_error+status_error_text；待响应=surface_variant+text_secondary）；icon 14dp → `icon_16`。
- item_task_card：日期 → `text_11`+`tertiary`；状态 pill 语义色；奖励 → `accent_text`；margin/padding → 令牌。
- 三弹窗：根加 `bg_card` + `radius_xl`(24dp)；操作钮 → `Button.Dialog`/`DialogOutlined`；内容 17sp → `text_17`；padding 令牌化。

**影响文件**
- layout：`activity_task.xml`、`item_task.xml`、`item_task_card.xml`、`dialog_task_detail.xml`、`dialog_task_publish.xml`、`dialog_task_reject.xml`
- values：`dimens.xml`/`colors.xml` 无新增（全部现成）
- java：`ui/TaskActivity.java`（状态 pill 背景/文字色切换逻辑 → 引用语义令牌，视觉级）

### 2.8 纪念日 `activity_anniversary`（+ item_anniversary / item_anniversary_heart / dialog_anniversary_edit）

**现状问题**
- 头栏已 include ✅（假占位已删）；但底部添加按钮 `minHeight="52dp"` 字面量（52 禁值）。
- `item_anniversary`：padding 14dp 字面量、marginBottom 10dp 字面量（→space_12）；编辑/删除 36dp（<44）；倒计时 `@color/primary` ❌ → primary_text；日期 13sp 字面量 → text_13；重复徽标 11sp ✅（图注级）。
- `item_anniversary_heart`：42/48dp 非令牌、label 10sp 字面量（→11sp）。
- `dialog_anniversary_edit`：SwitchCompat 无样式（v1 已列）。

**改造方案**
- 底部按钮去 `minHeight="52dp"` → `@dimen/bar_height`(48dp)。
- item_anniversary：padding → `space_14`；marginBottom → `space_12`；编辑/删除 → 44dp 热区 + 24dp 图形（`icon_44` 布局 + `icon_24` 图形，背景 `bg_icon_circle` 保留）；倒计时 → `primary_text`；日期 → `text_13`。
- item_anniversary_heart：`icon_44` 体系（44dp 容器 + 24/20dp 图形）；label → `text_11` + text_secondary（图注）。
- dialog_anniversary_edit：Switch → `Widget.EyeMonitor.Switch`；padding 24/8 → 令牌。

**影响文件**
- layout：`activity_anniversary.xml`、`item_anniversary.xml`、`item_anniversary_heart.xml`、`dialog_anniversary_edit.xml`
- java：`ui/AnniversaryActivity.java`（倒计时色若 Java setTextColor 则改 primary_text）

### 2.9 备忘录列表 / 详情 / 编辑（activity_memo_list / detail / edit + item_memo_card）

**现状问题**
- 三头栏是「统一 HeaderBar 视觉」的**手工复制**（非 include 单源）——建议切 include（护栏要求单一来源）。
- detail：正文 17sp 字面量（→text_17）；info 12sp 字面量（→text_12）；删除按钮 44dp + minHeight/inset/padding 手工覆盖 4 属性（样板残留）；margin 16/12/24 字面量。
- edit：`btn_memo_item_add` 40dp（<44）；icon 18dp 字面量（→icon_18 令牌）；padding 12dp 字面量。
- list：rv `padding="12dp"` / `paddingBottom="24dp"` 字面量。
- item_memo_card：时间 12sp 字面量；角标数 11sp + text_secondary（→tertiary）；缩略图容器 `bg_card_alt`（圆角 14 非令牌，v1 已列 →16）；pin/alarm 16dp 字面量（→icon_16）。

**改造方案**
- 三头栏 → include `component_header_bar`（右动作：list「＋ 新建」用 HeaderText 或 HeaderAction 胶囊；detail「编辑」HeaderText；edit「保存」HeaderAction Primary 语义）。
- detail：正文 → `text_17`；info → `text_12`；删除 → `Button.Danger` + `bar_height`（删手工覆盖）；margin → 令牌。
- edit：添加子项按钮 40→44dp；alarm icon → `icon_18`；行 padding → `space_12`。
- list：padding → `space_12`/`space_24` 令牌。
- item_memo_card：时间 → `text_12`；角标数 → `text_11`+`tertiary`；thumb 容器圆角统一 16（bg_card_alt 修）；pin/alarm → `icon_16`。

**影响文件**
- layout：`activity_memo_list.xml`、`activity_memo_detail.xml`、`activity_memo_edit.xml`、`item_memo_card.xml`
- drawable：`bg_card_alt`（圆角 14→16，v1 已立项）
- java：`ui/MemoListActivity.java`、`ui/MemoDetailActivity.java`、`ui/MemoEditActivity.java`（头栏 ID 随 include 变化 → `header_back/header_title/header_action` 命名对齐，纯视图引用）

### 2.10 设备状态 `activity_device_status`

**现状问题**
- 头栏：vivid 渐变 `bg_header_gradient`（非 std）+ 右侧「返回」胶囊 → 形态不一致。
- 状态卡 `bg_card_alt`（14dp 圆角非令牌，v1 已列）；行 padding `space_14` ✅。
- 值颜色：在线/离线统一 `text_secondary`（无语义）；「未豁免 · 点击去设置」text_secondary（应为 warn 语义）。

**改造方案**
- 头栏 → include `component_header_bar`（std 渐变、icon 返回、标题「对方设备状态」20sp 居中）。
- 状态值语义色：**在线=status_ok_text**、离线=text_tertiary；充电中=status_ok_text；网络/电量=text_primary；蓝牙开=status_ok_text、关=text_tertiary；电池优化：已豁免=status_ok_text、未豁免=**status_warn_text**；备注=text_secondary。
- 卡片圆角 14→16（radius_lg，改 bg_card_alt drawable 或换 bg_card）。

**影响文件**
- layout：`activity_device_status.xml`
- drawable：`bg_card_alt`（圆角 14→16）
- java：`ui/DeviceStatusActivity.java`（状态值 setTextColor 6 处 → 语义令牌，视觉级）

### 2.11 登录 `activity_login`

**现状问题**
- Hero 头：padding 24/40/28 字面量（→space_24/40/28）；标题 `text_28` ✅（v1 已归并）。
- 表单：输入/按钮 `@dimen/bar_height` ✅；margin `12dp` 字面量 ×7；`reg_gender_label` 14sp 字面量。
- **AA 违规**：`tv_go_register`/`tv_go_login` `?attr/colorPrimary`(#FF6B6B) **2.78:1 ❌** → `primary_text`。
- 服务器行 padding 14dp 字面量（→space_14）。

**改造方案**
- padding/margin 字面量全部令牌化（space_24/space_12/space_14/space_16）。
- 注册/登录切换链接 → `primary_text`（5.28:1 ✅）；gender label → `text_14`。
- 注册面板性别标签 margin 6dp → space_8。

**影响文件**
- layout：`activity_login.xml`
- values：无新增
- java：`ui/LoginActivity.java`（链接色若 Java 设置则改，通常布局内）

### 2.12 权限 `activity_permission`

**现状问题**
- 头栏：vivid 渐变 + 右侧「返回」胶囊 → 形态不一致。
- 状态文字已切 `status_warn_text` ✅（v1 落地）；但**未按权限状态区分**：全部默认 warn 色；已开启的项（通知等）应为 status_ok_text，boot「默认开启」为 text_secondary。
- 行结构重复 ×7（XML 无法循环，v1 已在文档级抽「设置行」规格）。

**改造方案**
- 头栏 → include `component_header_bar`。
- 状态值动态语义色：已授权/已开启/已豁免 → `status_ok_text`；未授权/未开启 → `status_warn_text`；默认开启（boot）→ `text_secondary`；已停用 → `status_error_text`。Java 侧 `setTextColor` 引用语义令牌（4 色映射表）。
- 行 padding space_12 ✅ 保留；desc 12sp ✅；tip Label ✅。

**影响文件**
- layout：`activity_permission.xml`
- java：`ui/PermissionActivity.java`（状态色映射，视觉级）

### 2.13 轨迹回放 `activity_track_replay`

**现状问题**
- 头栏形态与 HeaderBar 一致但手工复制（返回 44dp ✅ + 标题左置非居中）→ 切 include 组件保持浮层定位。
- 底栏按钮：range 44dp + `textSize="13sp"` 字面量（→text_13）；play 46dp（→bar_height 48dp）；speed Outlined 44dp ✅；margin 6/10/12dp 字面量。
- 空数据用 Toast + 文本进度（无空态，v1 已列 → 地图中央 EmptyState 浮层）。

**改造方案**
- 头栏 → include `component_header_bar`（保留 `layout_gravity="top"` 浮层；标题居中 20sp）。
- 底栏：`text_13` 令牌、play `bar_height`、margin → `space_6/space_10/space_12`（space_6 不存在 → 用 space_8/space_4 就近）。
- 空数据 → 地图中央 `component_empty_state` 浮层（📍 图标 +「暂无轨迹数据」+ 副文案，可关闭），替换 Toast 方案（Java 仅替换展示调用，不动查询逻辑）。

**影响文件**
- layout：`activity_track_replay.xml`
- values：无新增
- java：`ui/TrackReplayActivity.java`（空态展示替换 Toast，视觉级）

### 2.14 共享组件与护栏

| 组件 | 现状 | v2 |
|------|------|----|
| `component_header_bar.xml` | 已达标（std 渐变/44 返回/20sp 居中/动作槽） | **保持**；文档注释补「聊天头/hero 为例外变体」 |
| `template_page.xml` | 已对齐组件 ✅ | 保持 |
| `component_empty_state.xml` | 已达标 | 保持；图标 64dp + 主按钮 48dp 确认 |
| `bg_btn_white_rect` | 圆角 14（非令牌） | → `radius_md` 12dp（聊天头 icon 钮同源） |
| `bg_dot` | 6dp | → 8dp（更多面板指示点） |
| `bg_card_alt` | 圆角 14 | → 16dp（radius_lg），DeviceStatus/Memo 缩略图同源受益 |
| 伪按钮 | gallery 扇形 / task 头栏 publish | 扇形保留视觉换色；publish 换真 Button |

---

## 3. 原型清单（ui-redesign/prototypes/*.html，390×844 逻辑像素）

| 文件 | 页面 | 覆盖要点 |
|------|------|----------|
| `chat.html`（必做，重点） | 聊天页全貌 | 头栏两行重排 + 纯图标钮 + 紧凑状态行 + scrim；气泡（self/peer/system/media）时间/已读图注级；输入栏 44dp；更多面板（页 2 已填空位）；含「改造前 vs 改造后」对照小节 |
| `profile.html` | 资料页 | 统一头栏、头像 92dp 圆环、表单 48dp、亲密度等级（primary_text 大数字）、周对比、装扮区 |
| `map.html` | 地图 | 顶部双头像胶囊、小菜单（44dp 行、divider 分隔、12sp）、围栏面板（shadow、48dp 按钮） |
| `gallery.html` | 图库 | HeaderBar + 文件夹卡 + 网格 120dp + 批量栏 + FAB 扇形（去返回项、primary_text） |
| `task.html` | 任务 | HeaderBar + HeaderAction 发布 + 筛选 chips + 任务卡（语义 pill、accent_text 奖励） |
| `anniversary.html` | 纪念日 | HeaderBar + 纪念日卡（44dp 编辑/删除、primary_text 倒计时）+ 底部 48dp 添加 |
| `device-status.html` | 设备状态 | HeaderBar + 状态卡 6 行（语义状态色：在线绿/离线灰/未豁免橙） |
| `login.html` | 登录 | Hero 28sp + 登录卡（48dp 输入/按钮、primary_text 链接）+ 服务器行 |

所有原型：内联 CSS、真实中文文案（取自 `strings.xml`）、暖色品牌盘、尺寸/字号以 CSS 注释 + 底部 `<details>` 规格表标注；CSS 中 **1px = 1dp**（390dp 视口）。

---

## 4. 验收对照（v2 完工自检）

- [ ] 聊天头栏双钮宽度 180dp → 96dp（纯图标），触控 ≥44dp
- [ ] 状态行 = 单行紧凑（11sp + 90% 白 + scrim），头栏高度 ≈76dp
- [ ] 全 App 时间/已读/日期等辅助文字 = `text_11` + `text_tertiary`（grep 无 text_secondary 配时间戳）
- [ ] 头栏 5 形态 → 3 形态（std 组件 / 聊天头 / hero），Profile/Permission/DeviceStatus/TrackReplay 全部切齐
- [ ] <44dp 交互控件清零（引用条取消/录音取消/纪念日编辑删除/memo 添加子项）
- [ ] 更多面板页 2 空占位清零（纪念日/亲密度补位）
- [ ] 对比度：所有新增文字用法命中 ui-guide AA 变体（primary_text/accent_text/status_*_text/text_tertiary 记录残差豁免）
- [ ] 无 52dp 固定高、无 14dp 圆角、无 10sp 正文残留
- [ ] 零业务逻辑改动（java 仅视觉引用/接线）

**已知残差（记录在案）**：聊天头状态行 11sp 90% 白 on vivid 渐变 ~2.6:1（依赖图标冗余 + scrim 兜底，延续 ui-guide 残差表）；SOS 红钮白字 3.66:1 大字号（保留）。

---

*本文档为纯视觉规格；`prototypes/*.html` 为设计验收原型，不进入 android/ 构建链。*
