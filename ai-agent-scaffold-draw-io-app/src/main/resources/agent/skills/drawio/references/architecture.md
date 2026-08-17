# Draw.io 架构图

## 工作流程

1. 明确图的视角：逻辑架构、部署架构、运行时交互或网络拓扑；一张图只保留一个主要视角。
2. 提取边界、层级、组件、存储、外部系统、协议和关键数据流。
3. 先按层与边界分组，再放置节点，最后连接依赖关系。
4. 对无法确认的技术细节标注“待确认”，不要虚构组件或协议。
5. 输出前检查父子关系、连线方向、图例、重叠和 XML 完整性。

## 视觉语义

| 对象 | Draw.io 样式 |
| --- | --- |
| 区域/边界 | `swimlane;whiteSpace=wrap;html=1;dashed=1;fillColor=#f5f5f5;strokeColor=#666666;startSize=30;` |
| 服务/应用 | `rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontStyle=1;` |
| 数据库/存储 | `shape=cylinder3;whiteSpace=wrap;html=1;boundedLbl=1;fillColor=#ffe6cc;strokeColor=#d79b00;` |
| 消息队列 | `shape=process;whiteSpace=wrap;html=1;fillColor=#e1d5e7;strokeColor=#9673a6;` |
| 外部系统 | `rounded=1;whiteSpace=wrap;html=1;dashed=1;fillColor=#f8cecc;strokeColor=#b85450;` |
| 用户/客户端 | `shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;outlineConnect=0;` |

同一类对象使用同一套颜色和形状。不要仅靠颜色传达语义；同时使用标签、边型或分组。

## 连线规范

- 同步 API：实线 `endArrow=classic;edgeStyle=orthogonalEdgeStyle;strokeWidth=2;html=1;`。
- 异步消息：虚线 `endArrow=classic;edgeStyle=orthogonalEdgeStyle;dashed=1;strokeColor=#9673a6;html=1;`。
- 数据读写：实线并标注 `读/写` 或协议，使用 `strokeColor=#d79b00`。
- 边标签优先写协议或动作，如 `HTTPS`、`gRPC`、`publish`；不要重复节点名称。
- 双向交互应优先拆成两条有方向的边，避免含义模糊的双箭头。

## 布局规范

- 分层图从上到下排列客户端、接入层、应用层、基础设施与数据层。
- 同层节点横向排列；节点外框之间横向至少 150，纵向至少 120。
- 边界节点先确定尺寸，子节点使用该边界作为 `parent`，坐标按父节点局部坐标计算。
- 高频调用放在视觉中心，外部依赖靠边，跨层连线尽量保持垂直。
- 组件超过 20 个时拆分总览图和专题图。

## XML 与质量检查

- 保证 ID 唯一，边引用存在节点，父节点先于子节点定义。
- 转义 XML 属性中的特殊字符；所有标签和 `mxGeometry` 正确闭合。
- 必须包含 `0/1` 根节点并输出完整 `mxGraphModel`。
- 检查边界归属、协议标签、单向依赖、孤立组件、线条交叉和节点重叠。
- 不在架构图中混入详细业务流程；需要时改读 `flowchart.md` 或 `sequence.md` 并拆成独立图。
