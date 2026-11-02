# 天衍 · UI/UX 与对话排版审计

范围：`feature/chat`、`feature/components`、`feature/theme`、`feature/home` 的 Compose 代码（只读；行号取自工作区当前内容，已逐条核对）。
口径：**用户能感知的排版/交互缺陷优先**，代码洁癖类降权。共 20 条。
引用上游架构结论（不重复挖）：工具细节 UI 十件 ~799 行、群聊工作三件套 762 行均零调用。

证据基线：三模块裸 `dp` **1 432 处**；`Touch.*`/`Content.bubbleMax`/`Breakpoints.*` **0 引用**；裸 `Color(0xFF…)` **277 处**；裸 `RoundedCornerShape(nn.dp)` **161 处**；显式 `fontSize=` **88 处**、落在 9~17sp 十档；`values-en` 比 `values` 少 **59 键**。

---

## P0

**P0-1　整张消息列表关掉 M3 的 48dp 触控下限，列表内按钮真实尺寸 18~28dp**
位置：`ChatMessageList.kt:150` `LocalMinimumInteractiveComponentSize provides 0.dp`；受影响：`ChatMessageBubbles.kt:552/557/562`(24dp)、`:705/:727`(**18dp** 翻译/复制)、`:176`(28dp 图片菜单)、`ToolStepsCard.kt:49`(28dp 工具链展开，唯一入口)、`:65`(28dp)、`MessageFeedbackAction.kt:65`(24dp)
现象：该开关是 M3 把触摸区撑到 48dp 的唯一机制，置 0 后所有 `size(24.dp)` 不再补偿；`RuntimeButtons.kt:129-132` 又把调用方 modifier 放在链首，尺寸被约束死。
影响：18dp≈3.6mm，TalkBack 触摸探索与单手操作大面积失准；工具链展开是读过程链的必经交互，28dp 高横贯全宽极易点空。
改法：删 `:150` 这层 provider；视觉不变、触摸区恢复 → `Modifier.minimumInteractiveComponentSize().size(24.dp)`（**顺序不能反**，现成范式见 `ComposerSendButton.kt:19`、`ChatAttachment.kt:307`）；`ToolStepsCard.kt:49` 改 `heightIn(min = 48.dp)`。

**P0-2　开聊天壁纸后，纯文本回复与工具链卡片被额外内缩，消息左边缘不再对齐**
位置：`ChatPageBackground.kt:60-71`（纯文本回复套 28dp 圆角卡 + 16dp 内边距）、`:74-77` `chatGreetingModifier()` 加 12/8dp，应用点只有 `ChatWelcome.kt:86` 与 `ToolStepsCard.kt:48`；不参与：`ChatMessageBubbles.kt:151`(24/8 气泡)、`ChatToolCards.kt:120`(16dp 卡)
现象：同一列表出现三种左边缘、三种圆角（24 / 28 / 16）；工具链左右各缩 12dp、上下 8dp。
影响：这是"自定义背景"卖点功能打开后的主屏观感——滚动时消息块边界来回跳，比无壁纸时更乱；命名也泄底（问候语样式被拿去当工具链容器样式）。
改法：抽一套"壁纸模式消息容器"样式（圆角/内边距/底色）供四类消息共用；内缩只作用于背景块，不改布局宽度；`chatGreetingModifier()` 改名或并入。

---

## P1

**P1-1　i18n 断裂：`values-en` 在维护，红队整块与多处对话文案走裸中文**
位置：`RedTeam*.kt` 12 个文件 `stringResource` 命中 **0**（抽样 `RedTeamPanel.kt:104` "范围 Scope"、`:117` 绑定按钮、`:158-163` 内/外网、`:240-248` 体检）；`ChatMessageBubbles.kt:696/706/772/794/829/836/838/854/859/897/921/939/947/954` 与同文件 `:194/:226/:246` 的 `stringResource` 混用；`ChatScreen.kt:896` "运行"；`ChatWelcome.kt:37-39/110/125/224-227`；`values-en/strings.xml` 缺 59 键。
现象：英文 locale 存在且已译 531 条，但这几处掉回中文，同一屏内中英混排。
影响：英文用户看不懂红队面板；`contentDescription` 硬编码后 TalkBack 永远读中文。
改法：红队 12 文件批量抽键 + 补 59 条英文键；`:706` 的"查看英文原文"语义本身就错（假定原文是英文），拆成 show/hide 两键。

**P1-2　字号落在 9~17sp 共十档，88 处只改 `fontSize` 不改 `lineHeight`**
位置：`Type.kt:11` 是 stock `Typography()`；越界值 `ChatPopups.kt:170`(9sp)、`ChatAttachment.kt:299`(10)、`MarkdownText.kt:723/746`(10.5)、`ChatMessageBubbles.kt:987/1050`(11.5)、`FloatingChatView.kt:382`(12.5)；无行高补偿的如 `ChatMessageBubbles.kt:987`、`ChatAttachment.kt:299`。
现象：M3 每档 `fontSize`/`lineHeight` 成对（labelSmall 11/16、bodyMedium 14/20），只覆盖字号 → 9sp 配 16sp 行高 = 1.78 行距，"字小行稀"。
影响：Markdown 正文里行距不匀最明显（代码块 11.5/16.5 紧邻正文 14/20）；`fontScale` 放大时标题与正文层级塌陷。
改法：`Type.kt` 建项目自有字阶收编这几档，88 处 `copy(fontSize=…)` 改语义样式引用；加 lint 禁止 `feature/**` 出现 `fontSize =` 字面量。

**P1-3　代码块/行内代码/语法高亮用硬编码 GitHub 色板，`code`/`codeSurface` 令牌零引用**
位置：`MarkdownText.kt:231-232`（行内代码 2 色）、`:699-702`（代码块 4 色）；`SyntaxHighlighter.kt:22-40`（14 色）；对照 `SemanticColors.kt:47-48` 定义的 `code`/`codeSurface` 全仓 **0 次**使用，`:150` 注释还专门说明它们该怎么用。
现象：`SemanticColors.kt:18` 点名批评过裸 Tailwind 色破坏 Material You，代码块却仍是整套外来蓝灰。
影响：开动态取色/切 `ThemePalette` 时正文气泡都跟随，唯独对话里视觉最重的代码块脱节；`:229/:687` 的 `isDark = surface.luminance()<0.5f` 是逐组件现算的启发式，高对比度模式下可能与 `isSystemInDarkTheme()` 不一致。
改法：`CodeBlock`/`InlineText` 改用 `semanticColors().code/codeSurface`（补 `codeBorder`），palette 由 `SemanticColors` 派生；深色判定上收进 `TianyanTheme` 用 CompositionLocal 提供。

**P1-4　表格列宽按"字符数×7.4dp"硬估，窄屏只能横滑，大字号下字符被压**
位置：`MarkdownBlockRenderers.kt:47-52`（`(widest * 7.4f).dp.coerceIn(56.dp, 260.dp)`）、`:89-97`（`TableCell` 用固定 `Modifier.width`，不参与权重）、`:60` 外层 `horizontalScroll`、`:173-192` `displayWidthOf` 不读 fontScale；同型 `:165` `quoteBarHeight = (22 * lines).dp`。
现象：列宽是内容驱动的绝对 dp、与可用宽无关；3 列长中文可达 260×3=780dp，360dp 屏必然横滑。
影响：手机上 3 列以上表格退化成"横向滚动条"；字体放大 1.5× 后文字撑出固定列宽被裁，引用块竖条与内容等高失配。
改法：改权重分配 `Modifier.weight(w, fill = true)`，仅权重比 >6:1 时退化为横滑；需绝对估算处用 `rememberTextMeasurer()` 实测宽（天然跟随 fontScale）；竖条改 `fillMaxHeight()` + `IntrinsicSize.Min`。

**P1-5　流式生成时每帧 O(n) 全量重算，并每帧物化整段正文**
位置：`ChatMessageList.kt:126`(整条时间线投影)、`:131`(`assistantToolbarIds`)、`:132`(`indexOfLast`)、`:133`(`assistantTurnLabelKeys`) 全部以 `messages`/`renderItems` 为 key；`:139` `last.text.length` 走 `HarnessMessage.kt:188` 的 getter → `ChunkedText.toString()` 全量拼接。
现象：流式期间 `messages` 每 token 变一次 → 上面四处每帧重跑；取一个 length 却物化最多 64k 字符，而 `ChunkedText.length` 本是 O(1)。
影响：长会话 + 长回复时"越输出越卡、滚动越粘"的直接来源。同文件 `:134-147` 已示范正确做法（依赖收敛成末条消息签名），四处没跟上。
改法：`:131-133` key 收敛到末条消息签名+条数（或并入投影一次产出）；`:139` 改 `last.textContent.length`。

**P1-6　骨架屏把无限动画读在 composition 里，每帧重组整棵子树**
位置：`ChatMessageList.kt:371-392` `shimmerBrush()`（`val translateAnim by transition.animateFloat(…)` 在 composable 体内读），调用点 `:303`，下游 6 个 `Box(… .background(brush))`。
现象：动画每帧推进即触发 `ChatMessageSkeleton` **重组**（非重绘），并新建 `listOf`+`Brush`+`Offset`，1200ms 循环 ≈ 每秒 60 次重组。
影响：加载态本身就是帧预算最紧的时刻（同时解密会话、构建投影），叠加每帧子树重组后掉帧可见。
改法：动画值移进 `Modifier.drawBehind`/`graphicsLayer { }` 里读（范式见 `OrbitalComponents.kt:300`、`MarkdownText.kt:453-455` 注释）。`OrbitalComponents.kt:259-272` 的 `PulseDot` 同病，其 `:248` 注释误称"GPU 开销可忽略"（只对 graphicsLayer 成立）。

**P1-7　红队面板硬编码绿色在深色下对比度约 2.8:1（WCAG AA 不过）**
位置：`RedTeamPanel.kt:165`（`Color(0xFF2E7D32)` 作为 `labelMedium` 12sp 文字色，底为 `:155` 的 `surfaceContainerHighest`）、`:121`、`:246`；`RedTeamConsole.kt:333`（`medium` 档 `0xFFF59E0B`，同一 `when` 另两档却用 `colorScheme.error/onSurfaceVariant`）。
对比度估算（WCAG 相对亮度）：`#2E7D32` on dark `#282A30` ≈ **2.80:1**、on dark `#121318` ≈ 3.62:1、on light `#FAF8FD` ≈ 4.76:1 —— 12sp 属正文，要求 4.5:1，**深色不通过**；同屏 `primary`(dark `#BAC3FF`) 约 11:1。
影响：深色模式对内网/健康状态标签几乎看不清，且与同屏主题色对比度反差刺眼。
改法：四处改 `semanticColors().success / successContainer / warning`（已含 harmonize 与明暗两套）。

**P1-8　设计令牌体系形同虚设：`Touch`/`Content`/`Breakpoints` 零引用**
位置：`Dimens.kt:86`(`Touch.min=48`)、`:89`、`:95`(`bubbleMax=560`)、`:98`(`dualPaneMin=720`)、`:110-124`(`Breakpoints`) 全部 **0 引用**；数字被各写一遍：`ChatMessageBubbles.kt:153`/`AttachmentFileCard.kt:129`(560)、`ChatAdaptiveLayout.kt:58`(720 私有常量)、`ChatScreen.kt:722`(840)。
现象：`Dimens.kt:12-27` 把"间距只取六档、48dp 是无障碍硬要求"写成铁律，实测 97 处用令牌 vs 1 432 处裸 dp；`Theme.kt:136` 注释也在抱怨"507 处硬编码使形状 token 形同虚设"，现状仍有 161 处裸圆角。
影响：P0-1 的 18/24/28dp 正是缺这道闸；"气泡 560dp 铁律"靠三处抄写维持。
改法：先收口（三处数字改引令牌、删 `DockedCompanionMinWidth`），再上棘轮 lint（禁止 `feature/**` 裸 `\d+\.dp`，白名单 `Dimens.kt`）。

---

## P2

**P2-1　同一语义的圆角/内边距/发丝线取值不一**
`ChatMessageBubbles.kt:378`(12dp 图片) vs `MarkdownText.kt:338`(14dp 正文图)；`RuntimeComponents.kt:268`(卡片 16dp) vs `HomeScreen.kt:441/739`(14dp)；发丝线 `MarkdownText.kt:707`(0.6) / `MarkdownBlockRenderers.kt:57`(0.7) / `ChatAttachment.kt:263`(0.8) / `CompactionBanner.kt:61`(1.0)。
影响：跨页扫视时同类块边界不齐；0.6/0.7/0.8 在 mdpi 上都被抗锯齿吃掉，等于随机。
改法：`Dimens.kt` 补 `Radius.media`、`Content.cardPadding`，发丝线统一 `Dp.Hairline` 或 1dp。

**P2-2　骨架屏气泡形状与实际气泡不符** — `ChatMessageList.kt:314/363` 用 `(16,4,16,16)`，真实气泡 `ChatMessageBubbles.kt:151` 是 `(24,8,24,24)`。影响：加载完成瞬间形状跳变。改法：骨架屏复用气泡形状常量。

**P2-3　流式出图占位与成品尺寸不匹配** — `ChatMessageBubbles.kt:469` 占位 `size(260.dp,180.dp)`(1.44)，成品按 `MarkdownText.kt:679-682` 的 4:3 占位(260×195)。影响：同一张图从占位切成品跳 15dp。改法：流式占位改引 `MEDIA_PLACEHOLDER_ASPECT`。

**P2-4　悬浮聊天与主界面两套几何** — `FloatingChatView.kt:589-595` 输入栏 `height(36.dp)`+18dp 圆角 vs `ChatComposer.kt:189` `shapes.extraLarge`(28dp)+`:237` `heightIn(min=48.dp)`；`:67-68` 边缘手柄触摸区 **32×40dp**（浮层唯一唤出入口，低于 48dp）；单文件 5 档圆角。改法：手柄外包 `minimumInteractiveComponentSize()`（视觉可保持 32×40）；输入栏抽 `ChatInputSurface` 与主界面共用。

**P2-5　重复/无效 Modifier** — `ChatScreen.kt:857-858` 与 `CompactionBanner.kt:54,60` 均 `clip(shape)` + `shape=` 双裁剪；`ChatMessageBubbles.kt:505` `padding(top = 0.dp)` 空操作；`ToolStepsCard.kt:62-64` 两段 start padding 相加 20dp，分隔线画在中间；`ChatScrollToBottomButton.kt:93-101` `shadow(4.dp)` 与 `elevation(0.dp)` 并存。改法：逐项删除/合并。

**P2-6　`setOf(...)` 在热循环里逐次分配** — `SyntaxHighlighter.kt:100`（每个 `#` 字符分配 8 元素 Set）、`:205`（每个标识符一次）、`ToolStepsCard.kt:45-46`（每步每帧）、`ChatTimelineProjection.kt:119`。影响：代码块高亮在流式时每帧重跑（`MarkdownText.kt:695` 的 remember key 每 token 变）。改法：提为文件级 `private val`。

**P2-7　状态仅靠颜色表达 / 缺语义** — `OrbitalComponents.kt:259-272` `PulseDot` 无任何 semantics；`ChatScrollToBottomButton.kt:96` 有 `contentDescription` 但缺 `role = Role.Button`；`ChatScreen.kt:898-907`（关闭建议）与 `ChatShareSupport.kt:91` 的 IconButton **无 contentDescription**；`ToolStatusIndicator.kt:21` 允许 `description == null` 静默丢语义。改法：统一走 `RuntimeIconButton` 并在 `RuntimeButtons.kt:129` 对空标签加约束。

**P2-8　`ChatComposer` 的 pinned/attached 判定逐 item 线性扫描** — `ChatComposer.kt:218-219` 在 `LazyRow items{}` 内做 `any { it.id == item.id }`（O(n·m)）；`:204-206` 的 `remember` 键含 `pinnedCapabilities` 但 lambda 只用到 `attachedMentions`（多余依赖），变量名 `allDisplayItems` 名不副实。改法：`remember { mapTo(HashSet()) { it.id } }` + `in` 判定。

**P2-9　`AssistantMessageFooter` 与消息操作条重复实现（死代码）** — `AssistantMessageFooter.kt:15` 全仓命中 1 次=自身，而 `ChatMessageBubbles.kt:551-572` 另有一份内联实现，尺寸(24/32dp)与组件(`IconButton` vs `RuntimeIconButton`)都不同。改法：删一留一（建议反向抽成组件并复用，顺带统一 P0-1 的触摸区）。

**P2-10　文案标点与拼接不统一** — `ChatMessageBubbles.kt:772` "中文译文 (ML Kit 离线生成)" 用半角括号而 `:836` 用全角；`:921` `"依据：" + reason` 用拼接而非 `stringResource(x_fmt)`；`ChatScreen.kt:875` 拼接 `"${projectName} · ${title}"`。改法：统一全角标点 + 格式化键。

---

## 待确认

- `ChatMessageList.kt:158` 列表 top = statusBars+64dp，`:295` 渐隐层 top = statusBars+72dp。`ChatTopBar.kt:44,52` 实测高度正好 64dp（8dp padding + 48dp 按钮），多出的 8dp 可能是有意让渐变从悬浮栏下沿起。需真机截图对比（有/无壁纸）后定，未确认前不断言为缺陷。
- `MarkdownText.kt:363-373` 非 4:3 图片仍会跳变（竖图 195→340dp），`:367-370` 注释承认是已知取舍。需确认 Coil3 能否在头信息阶段回调 intrinsic size。

---

## 最值得先修的 5 条

1. **P0-1** 删 `ChatMessageList.kt:150` 的 48dp 下限覆盖 + 8 处改 `minimumInteractiveComponentSize().size(…)`（顺序不可反）—— 一行 + 八处，整屏对话触控恢复达标。
2. **P0-2** 壁纸模式下统一消息容器样式、停止给工具链容器单独内缩 —— 直接决定自定义背景这个卖点功能的观感。
3. **P1-1** 红队 12 文件抽 `stringResource` + 补 59 条英文键 + 清掉对话区裸中文 —— 英文用户不再掉回中文，`contentDescription` 不再硬编码。
4. **P1-7** 四处裸绿/裸橙改 `semanticColors().success/warning` —— 修掉深色 2.8:1 的对比度硬失败，4 处替换。
5. **P1-5 + P1-6** 流式热路径 key 收敛 + `textContent.length` + 动画值移进 draw —— 长会话卡顿与骨架屏每帧重组同时消掉，约 30 行。

> 备选：**P1-3** 代码块改用 `semanticColors().code/codeSurface`，影响面只集中在 `MarkdownText.kt` + `SyntaxHighlighter.kt` 两个文件。

## 附：可保留的既有正确范式（返工时勿改坏）

`MarkdownText.kt:453-455`（拖动状态只在 graphicsLayer/pointerInput 里读）、`OrbitalComponents.kt:287-303`（`staggeredEntrance` 用 graphicsLayer 且对前 N 项封顶）、`ChatMessageList.kt:134-147`（依赖收敛成末条消息签名，P1-5 应照此推广）、`ChatAttachment.kt:307` 与 `ComposerSendButton.kt:19`（48dp 触摸区 + 语义标签）、`HomeScreen.kt:293/308`（`144.dp * fontScale` 真正跟随字体缩放，P1-4 的表格列宽可借鉴）、`MarkdownText.kt:161-190`（块间距按前后块类型分别给值）。
