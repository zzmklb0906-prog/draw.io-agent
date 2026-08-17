# Draw.io 流程图

## 工作流程

1. 从需求中提取参与者、起点、终点、步骤、判断条件、正常路径和异常路径。
2. 信息不足且会改变流程结构时先请求补充；可安全推断时明确假设并继续。
3. 先建立节点和边的逻辑清单，再计算坐标，最后生成 XML。
4. 主干从上到下，异常分支向左右展开；同层节点对齐。
5. 输出前执行完整性检查，修复断边、重复 ID、重叠与非法 XML。

## 节点规范

| 语义 | Draw.io 样式 |
| --- | --- |
| 开始/结束 | `rounded=1;arcSize=50;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontStyle=1;` |
| 处理步骤 | `rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;` |
| 判断条件 | `rhombus;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;` |
| 输入/输出 | `shape=parallelogram;perimeter=parallelogramPerimeter;whiteSpace=wrap;html=1;fixedSize=1;fillColor=#e1d5e7;strokeColor=#9673a6;` |
| 文档 | `shape=document;whiteSpace=wrap;html=1;boundedLbl=1;fillColor=#f8cecc;strokeColor=#b85450;` |

判断节点的所有出口必须标注互斥条件，如“是/否”“成功/失败”。错误处理节点使用浅红色 `#f8cecc` 和描边 `#b85450`。

## 连线与布局

- 默认边使用 `edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;endArrow=classic;`。
- 节点宽度通常为 140–220，高度为 60–90；长文本应扩大节点，不压缩字号。
- 节点外框之间横向至少留 150，纵向至少留 120；坐标间距必须计入节点尺寸。
- 回路从主干外侧绕行，避免穿过节点或与主干重合。
- 泳道仅在参与者归属对理解流程有帮助时使用。

## XML 约束

- 根节点必须包含 `<mxCell id="0"/>` 和 `<mxCell id="1" parent="0"/>`。
- 每个 `mxCell` 的 ID 唯一；每条边的 `source`、`target` 必须引用已存在节点。
- 用户文本写入 XML 属性前转义 `& < > " '`；换行使用 `&lt;br&gt;`。
- 每个节点和边必须包含闭合的 `mxGeometry`。
- 最终 XML 必须形成一个可直接加载的完整 `mxGraphModel`。

## 完成检查

- 起点和终点存在，所有可达分支都有去向。
- 判断出口标签完整且不含歧义。
- 无重复节点、重复边、孤立节点、重叠节点或越界布局。
- 图中只保留必要文字，详细解释放在图外。
