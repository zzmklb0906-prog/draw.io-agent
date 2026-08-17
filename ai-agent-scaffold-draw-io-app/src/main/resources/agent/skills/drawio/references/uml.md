# Draw.io UML

## 工作流程

1. 先确认 UML 图类型；不要在同一张图中混合类图、用例图和时序图语义。
2. 从需求或代码提取元素、职责、可见性、关系方向和多重性。
3. 先放核心抽象，再放实现与依赖；关系语义不确定时标注待确认。
4. 输出前验证 UML 关系、箭头方向、标签、多重性和 XML 完整性。

## 类图节点

- 类使用三段结构：类名、属性、方法；简单类可用 HTML 文本和 `&lt;hr&gt;` 分隔。
- 类样式：`rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;align=left;verticalAlign=top;spacing=5;`。
- 接口在标题中标注 `&lt;&lt;interface&gt;&gt;`，使用 `fillColor=#d5e8d4;strokeColor=#82b366;`。
- 可见性使用 `+` public、`-` private、`#` protected、`~` package。
- 属性写作 `name: Type`，方法写作 `method(arg: Type): ReturnType`；只展示对当前设计有价值的成员。

## 用例图节点

- 用例：`ellipse;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;`。
- 参与者：`shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;outlineConnect=0;fillColor=#f8cecc;strokeColor=#b85450;`。
- 系统边界使用浅灰容器，参与者置于边界外，用例置于边界内。

## 关系规范

| 关系 | 样式与方向 |
| --- | --- |
| 泛化 | `endArrow=block;endFill=0;edgeStyle=orthogonalEdgeStyle;html=1;`，箭头指向父类 |
| 实现 | 泛化样式增加 `dashed=1`，箭头指向接口 |
| 依赖 | `endArrow=open;dashed=1;endSize=8;edgeStyle=orthogonalEdgeStyle;html=1;`，指向被依赖项 |
| 关联 | `endArrow=none;edgeStyle=orthogonalEdgeStyle;html=1;`，按需标注角色与多重性 |
| 聚合 | `startArrow=diamondThin;startFill=0;edgeStyle=orthogonalEdgeStyle;html=1;`，菱形位于整体端 |
| 组合 | 聚合样式改为 `startFill=1`，菱形位于整体端 |

## 布局与质量检查

- 父类/接口在上，实现类在下；核心类居中，依赖类置于两侧。
- 节点外框之间横向至少 180，纵向至少 140；避免关系线穿过类框。
- ID 唯一，边引用存在节点，文本正确 XML 转义，所有 `mxGeometry` 闭合。
- 必须包含 `0/1` 根节点并输出完整 `mxGraphModel`。
- 检查继承与实现方向、聚合/组合整体端、多重性、孤立元素和重复关系。
