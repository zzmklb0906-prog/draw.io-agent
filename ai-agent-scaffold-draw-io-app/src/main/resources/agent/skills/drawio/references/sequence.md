# Draw.io 时序图

## 工作流程

1. 按交互顺序识别参与者、请求、响应、异步通知、条件和异常。
2. 从左到右排列参与者，从上到下排列消息；先构建消息清单，再生成 XML。
3. 用同步、异步和返回三种边型表达等待关系，不用文字代替箭头语义。
4. 仅在执行时段有明确价值时添加激活块，避免整条生命线全部激活。
5. 输出前核对消息方向、顺序号、返回匹配和 XML 完整性。

## 元素规范

- 生命线：`shape=umlLifeline;perimeter=lifelinePerimeter;whiteSpace=wrap;html=1;container=1;collapsible=0;recursiveResize=0;outlineConnect=0;fillColor=#dae8fc;strokeColor=#6c8ebf;`。
- 激活块：`html=1;points=[];perimeter=orthogonalPerimeter;fillColor=#fff2cc;strokeColor=#d6b656;`，作为生命线子节点。
- 同步消息：`html=1;verticalAlign=bottom;endArrow=block;edgeStyle=elbowEdgeStyle;elbow=vertical;`。
- 异步消息：`html=1;verticalAlign=bottom;endArrow=open;endSize=8;edgeStyle=elbowEdgeStyle;elbow=vertical;`。
- 返回消息：在异步消息样式上增加 `dashed=1`。

## 布局规范

- 生命线顶部 Y 坐标一致，宽度通常为 100–140，外框横向间隔至少 160。
- 第一条消息位于标题下方至少 80；后续消息的 Y 坐标至少递增 55。
- 消息标签使用 `1. action()`、`1.1 validate()` 等层级编号；返回可写结果或状态码。
- 自调用从生命线右侧绕回，至少预留 50 宽度，不能与下一条消息重叠。
- 条件、循环和并行片段用清晰边界与 `[condition]` 标签；复杂分支可拆成第二张图。

## XML 与质量检查

- ID 唯一，生命线先于其激活块定义，子节点 `parent` 指向正确生命线。
- 每条消息的 `source`、`target` 引用已存在节点；请求和响应方向正确。
- 用户文本必须 XML 转义；每个元素包含闭合的 `mxGeometry`。
- 必须包含 `0/1` 根节点并输出可加载的完整 `mxGraphModel`。
- 检查是否遗漏失败响应、超时、重试或回调；不要把静态依赖图伪装成时序图。
