# 眼互 UI 设计规范（v1，2025-09）

> 配套实施计划：`.omc/plans/ui-style-system-v1.md`；需求来源：`.omc/specs/deep-interview-ui-strategy.md`
> 原则一句话：**Material Components 做底座，自研令牌层集中管理，新页面从模板出发。**

---

## 1. 设计令牌（禁止直接写死）

| 类别 | 位置 | 引用方式 |
|------|------|----------|
| 语义色 | `res/values/attrs.xml` + `themes.xml` 映射 | 布局用 `?attr/textPrimary`、`?attr/colorPrimary` 等 |
| 间距 | `res/values/dimens.xml`（8dp 步进：space_2/4/8/10/12/14/16/24/32）| `@dimen/space_16` |
| 圆角 | `dimens.xml`（radius_sm 8 / md 12 / lg 16 / xl 24）| `@dimen/radius_lg` |
| 字号 | `dimens.xml`（text_12/13/14/15/16/20）| `@dimen/text_14` |

- **禁止**：布局/Java 中出现 `#RRGGBB` 硬编码色值（drawable 内除外）；已由构建检查 `checkUiStyle` 拦截。
- **皮肤预留**：语义色全部走 `?attr/`，未来换肤 = 替换 `themes.xml` 中的 attr 实现（theme overlay）。

## 2. 组件样式清单（`res/values/styles.xml`）

| 样式 | 用途 |
|------|------|
| `Widget.EyeMonitor.Button.Primary` | 主操作（填充暖色） |
| `Widget.EyeMonitor.Button.Outlined` | 次级操作（描边） |
| `Widget.EyeMonitor.Button.Header` | 头栏按钮（半透明白） |
| `Widget.EyeMonitor.Button.Danger` | 危险操作（状态红：删除/解绑/SOS） |
| `Widget.EyeMonitor.Button.Sos` | SOS 圆形按钮特例（保留自绘 background） |
| `Widget.EyeMonitor.RadioButton` | 单选（tint 跟随主色） |
| `Widget.EyeMonitor.Card` | 通用圆角卡片 |
| `Widget.EyeMonitor.EditText` | 输入框 |
| `TextAppearance.EyeMonitor.HeaderTitle / Display / Title / Body / Label` | 排版层级 |

> 防呆：`themes.xml` 已将 `materialButtonStyle / radioButtonStyle / editTextStyle` 指向本体系——**裸 `<Button>` 也自动是圆角非大写形态**。

## 3. 新页面流程（护栏）

1. 复制 `res/layout/template_page.xml`（渐变头栏 + 内容区骨架已就位）
2. 继承 `com.eyemonitor.ui.BaseActivity`（系统栏配色兜底）
3. 内容控件引用第 2 节样式；间距用 `@dimen/space_*`；颜色用 `?attr/*`
4. 字符串进 `strings.xml`，禁止硬编码中文
5. 构建时 `checkUiStyle` 自动校验，违规即失败

## 4. 页面改造边界（本轮非目标，下一迭代）

- 完整组件表（Chip/FAB/Dialog/BottomSheet/EmptyState 全量样式）
- 全部页面深度重排（本轮仅 5 个焦点页精修）
- 自定义 lint 规则（当前为脚本级构建检查）
- 皮肤功能模块（换肤入口/多主题/持久化）——结构已预留，功能未做
