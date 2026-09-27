# A3 实现设计：死代码检测

本作业在单个方法内结合 CFG、常量传播和活跃变量分析，识别不可达语句与无用赋值。主要实现位于 [DeadCodeDetection.java](src/main/java/pascal/taie/analysis/dataflow/analysis/DeadCodeDetection.java)；判断标准另见 [prob.md](prob.md)。

## 复用前两次作业的分析结果

`analyze(IR)` 从方法的 `IR` 取得 CFG、常量传播结果和活跃变量分析结果。A3 中的 [LiveVariableAnalysis.java](src/main/java/pascal/taie/analysis/dataflow/analysis/LiveVariableAnalysis.java) 与 A1 相同，[ConstantPropagation.java](src/main/java/pascal/taie/analysis/dataflow/analysis/constprop/ConstantPropagation.java) 与 A2 相同。A3 的 [Solver.java](src/main/java/pascal/taie/analysis/dataflow/solver/Solver.java) 和 [WorkListSolver.java](src/main/java/pascal/taie/analysis/dataflow/solver/WorkListSolver.java) 则同时实现前向与后向的初始化和求解，让这两项分析都能运行。

## 从入口寻找可达语句

检测器把 CFG 的虚拟入口放入队列，并用 `reachable` 集合记录已经发现的节点。每取出一个节点，就根据其类型决定要加入哪些后继：

- 普通语句沿 CFG 的全部后继继续遍历。
- `if` 条件若可由当前语句的常量传播 `IN` 算出确定值，只遍历对应的 `IF_TRUE` 或 `IF_FALSE` 出边；否则遍历全部后继。
- `switch` 的选择值若为常量，只从匹配的 `case` 目标开始；没有匹配值时从 `default` 开始。若选择值未知，则将所有 `case` 和 `default` 目标加入队列。进入分支后的普通 CFG 遍历仍能覆盖 `case` 之间的顺序落入。

遍历结束后，CFG 中未被标记且不是虚拟入口、出口的语句，加入死代码结果集。`reachable` 和结果集均按语句的 IR 索引排序。

## 识别无用赋值

遍历可达语句时，检测器只检查左值为 `Var` 的 `AssignStmt`。如果该变量不在语句的活跃变量 `OUT` 中，且右值通过 `hasNoSideEffect()` 检查，就把语句加入结果集。数组和字段写入的左值不是 `Var`，不会按局部变量的无用赋值处理。

框架提供的 `hasNoSideEffect()` 将创建对象、类型转换、字段或数组读取，以及除法和取余视为可能有副作用或异常的操作。方法调用不属于这里检查的 `AssignStmt` 类型，也不会因为返回值未使用就被标记为无用赋值。

检测器只报告本轮分析直接识别出的死代码，不重复删除代码再重新分析。参考：[Tai-e 作业 3](https://tai-e.pascal-lab.net/pa3.html)。
