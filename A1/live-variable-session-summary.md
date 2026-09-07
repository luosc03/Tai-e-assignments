# A1 活跃变量分析会话摘要

## 项目参考

- Tai-e 实验官网：<https://tai-e.pascal-lab.net/lectures.html>
- A1 作业说明：<https://tai-e.pascal-lab.net/pa1.html>

## Fact 类型

- A1 中每个 `Stmt` 节点关联的 `IN fact` 和 `OUT fact` 的具体类型是 `SetFact<Var>`。
- 在 `Solver<Node, Fact>` 或 `DataflowAnalysis<Node, Fact>` 中，`Fact` 只是泛型占位符；在 A1 中实际对应 `SetFact<Var>`。
- `LiveVariableAnalysis` 中参数类型已经明确为 `SetFact<Var>`，可以直接使用 `add`、`union` 等方法。
- 泛型 `Solver<Node, Fact>` 不应直接调用 `SetFact` 的方法，因为编译器不能假设 `Fact` 一定是 `SetFact<Var>`。应通过 `analysis` 提供的接口（如 `meetInto`、`transferNode`）操作 fact。

## `LValue`、`RValue` 与 `Var`

- `Var` 同时实现了 `LValue` 和 `RValue`。
- `stmt.getUses()` 返回 `List<RValue>`，其中不一定每个元素都是 `Var`，因此不能把整个列表直接强转成变量集合。
- 处理单个 use 时应先判断它是否为 `Var`，再转为 `Var`；不能写 `Fact instanceof SetFact<Var>`。
- `instanceof` 的左侧必须是变量，例如 `fact instanceof SetFact<?>`；而 `SetFact<Var>` 不能直接用于 `instanceof`，这是泛型擦除导致的。

## 后向活跃变量分析的关键关系

```text
OUT[B] = 所有后继节点 IN 的并集
IN[B]  = USE[B] ∪ (OUT[B] - DEF[B])
```

- 出口节点的边界事实是 `OUT[exit] = ∅`。
- 非边界节点通常从空集开始，因为活跃变量集合使用并集，空集是最小事实；迭代过程中信息逐渐增加。
- `IN` 和 `OUT` 不能互相替代：后向 transfer 的方向是 `OUT → IN`，而 `IN` 会沿控制流反向影响前驱节点的 `OUT`。
- `newInitialFact()` 表示非边界节点的初始 fact，不是只给 `IN` 使用；在 Tai-e A1 的设计中，节点的 `OUT` 也需要对应的初始 fact。

## 迭代停止条件的澄清

- 如果使用 worklist，或在处理当前节点后立即把其 `IN` 传播到前驱的 `OUT`，那么可以根据传播后目标 `OUT` 是否变化来决定是否继续处理：没变表示没有新信息，变了则可能需要更新前驱。
- 如果是普通整轮扫描，只检查本轮各节点自己的 `OUT` 是否变化，则依赖遍历顺序。若先处理前驱、后处理后继，后继本轮才更新的 `IN` 可能来不及影响前驱的 `OUT`，可能需要下一轮。
- 因此，“检查 `OUT` 是否变化”不是绝对错误，但必须结合传播方式和遍历顺序；检查后向 transfer 产生的 `IN` 变化通常更直接、稳妥。
