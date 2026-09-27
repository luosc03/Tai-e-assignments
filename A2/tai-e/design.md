# A2 实现设计：常量传播与工作列表求解器

本作业对单个方法做前向常量传播。核心实现位于 [ConstantPropagation.java](src/main/java/pascal/taie/analysis/dataflow/analysis/constprop/ConstantPropagation.java)、[Solver.java](src/main/java/pascal/taie/analysis/dataflow/solver/Solver.java) 和 [WorkListSolver.java](src/main/java/pascal/taie/analysis/dataflow/solver/WorkListSolver.java)。

## 抽象值与边界条件

`CPFact` 保存变量到抽象值的映射。每个抽象值可能是 `UNDEF`、某个确定整数或 `NAC`；未记录的变量按 `UNDEF` 处理。分析范围由 `canHoldInt()` 限定为 `boolean`、`byte`、`short`、`char` 和 `int` 类型。

`newBoundaryFact()` 把方法中属于分析范围的参数设为 `NAC`，因为逐方法分析时参数的实际传入值未知；`newInitialFact()` 返回空映射。两个抽象值汇合时，`NAC` 优先；`UNDEF` 不改变另一侧的值；不同常量汇合为 `NAC`。`meetInto()` 对来源 fact 中记录的每个变量执行该汇合，并原地更新目标 fact。

## 语句转移与表达式求值

每条语句先复制 `IN`，作为新的 `OUT`。只有当语句定义了属于分析范围的 `Var` 时，才在副本中更新该变量；其他语句保持映射不变。求右值时使用当前语句的 `IN`，避免让本条赋值影响自身表达式的取值。

`evaluate()` 直接读取变量值和整数字面量；对于二元表达式，先读取两个操作数的抽象值，再处理算术、比较、位运算和移位。比较结果用 `1` 或 `0` 表示。除数确定为零时，除法和取余返回 `UNDEF`；不在本次分析范围内的表达式保守地返回 `NAC`。转移函数最终比较新旧 `OUT`，把是否变化返回给求解器。

## 工作列表求解

`Solver.initializeForward()` 把虚拟入口的 `OUT` 设为边界值、虚拟出口的 `IN` 设为初始值，并初始化普通节点的 `IN`、`OUT`。`WorkListSolver` 用 `LinkedHashSet` 保存待处理节点，既保持加入顺序，也避免同一节点在队列中重复出现。

取出节点后，求解器将所有前驱的 `OUT` 汇入该节点的 `IN`；对普通节点执行转移函数。只有 `OUT` 改变时才将后继重新加入工作列表，直到列表为空。A2 当前只实现前向求解；后向相关方法保留框架中的 `UnsupportedOperationException`。

参考：[Tai-e 作业 2](https://tai-e.pascal-lab.net/pa2.html)。
