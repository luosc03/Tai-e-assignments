# A1 实现设计：活跃变量分析与迭代求解器

本作业以单个方法的控制流图（CFG）为输入，计算每条语句执行前后的活跃变量集合。核心实现位于 [LiveVariableAnalysis.java](src/main/java/pascal/taie/analysis/dataflow/analysis/LiveVariableAnalysis.java)、[Solver.java](src/main/java/pascal/taie/analysis/dataflow/solver/Solver.java) 和 [IterativeSolver.java](src/main/java/pascal/taie/analysis/dataflow/solver/IterativeSolver.java)。

## 活跃变量的数据流定义

- 分析方向是后向：变量在语句执行后是否活跃，取决于后继语句执行前是否还会读取它。
- `IN` 和 `OUT` 都用 `SetFact<Var>` 表示；出口边界值和普通节点的初始值都是空集。
- 汇合操作是并集。`meetInto(fact, target)` 将 `fact` 并入 `target`，用来合并多个后继的 `IN`。
- 单条语句的转移函数为 `IN[s] = USE[s] ∪ (OUT[s] − DEF[s])`。实现从 `OUT` 复制出新集合，删除 `getDef()` 中定义的变量，再把 `getUses()` 中的 `Var` 加入集合；最后比较并更新原来的 `IN`，返回它是否变化。

只把 `Var` 纳入活跃变量集合。语句的左值也可能是字段或数组访问，不能把所有 `LValue` 都当作局部变量定义。

## 迭代求解

`Solver.initializeBackward()` 把虚拟出口的 `IN` 设为边界值、虚拟入口的 `OUT` 设为初始值，并为普通 CFG 节点分别建立空的 `IN`、`OUT`。入口和出口节点不执行语句转移。

`IterativeSolver.doSolveBackward()` 先把 CFG 节点按反向顺序排列，然后重复遍历：将每个后继的 `IN` 并入当前节点的 `OUT`，再调用转移函数更新当前节点的 `IN`。只要一轮遍历中有节点的 `IN` 改变，就继续下一轮；全部不变时得到不动点。这里使用通用的 `DataflowAnalysis` 接口，求解器本身不依赖活跃变量分析的具体规则。

A1 当前只实现后向求解；前向相关方法保留框架中的 `UnsupportedOperationException`。

参考：[Tai-e 作业 1](https://tai-e.pascal-lab.net/pa1.html)。
