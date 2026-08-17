---
name: drawio
description: 设计、生成和审查可直接加载的 Draw.io 图表与 mxGraph XML；当用户要求流程图、业务流程、架构图、部署图、网络拓扑、时序图、交互图、UML 类图、用例图，或需要修改现有 Draw.io XML 时使用。
---

# Draw.io 图表

## 先选择图表类型

只读取当前任务需要的规范：

- 流程图、业务流程、审批流、状态流、算法控制流：读取 [references/flowchart.md](references/flowchart.md)。
- 系统架构、部署架构、微服务、云或网络拓扑：读取 [references/architecture.md](references/architecture.md)。
- API 调用时序、跨系统交互、回调、重试与异步消息：读取 [references/sequence.md](references/sequence.md)。
- UML 类图、接口关系、用例图、组件图：读取 [references/uml.md](references/uml.md)。

如果需求同时包含多种视角，优先拆成多张图，并分别读取对应规范；不要把流程、架构和时序语义堆在一张图中。

## 通用工作流程

1. 确认图表目的、受众、范围、关键元素和期望方向。
2. 选择图表类型并读取且仅读取对应 reference。
3. 先建立节点与连线的结构化清单，再计算坐标，最后生成 XML。
4. 用户要求渐进输出时，先输出节点，再输出边，最后输出完整 XML。
5. 完成前校验语义、布局、引用关系、XML 转义和可加载性。

## 通用 XML 约束

- 完整图包含 `<mxGraphModel><root>`、`<mxCell id="0"/>` 和 `<mxCell id="1" parent="0"/>`。
- 所有 ID 唯一；边的 `source`、`target` 和子节点的 `parent` 必须引用已存在元素。
- XML 属性中的 `& < > " '` 必须转义，换行使用 `&lt;br&gt;`。
- 每个节点和边包含闭合的 `mxGeometry`，所有标签正确闭合。
- 不输出 Markdown 围栏或 XML 之外的说明，除非调用方明确要求解释。

## 通用质量门禁

- 不重复节点和连线，不保留孤立节点。
- 节点不重叠、不越界，连线尽量不穿过节点或相互交叉。
- 标签简洁、方向明确、语义一致；不虚构用户未提供且无法安全推断的系统事实。
- 最终 XML 能由 Draw.io 直接加载。
