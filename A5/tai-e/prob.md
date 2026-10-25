# A5：指针分析踩坑记录

## 为什么调用语句的左值可能是 null

处理 `Invoke` 时，需要先判断调用结果是否被变量接收。`r = T.m(a)` 和 `r = x.m(a)` 有结果变量 `r`；单独的 `T.m(a)` 或 `x.m(a)` 没有接收变量，`invoke.getLValue()` 返回 `null`。

没有接收变量时，不创建调用结果变量的 PFG 节点，也不添加“被调用方法的返回变量 → 调用结果变量”的 PFG 边。这不代表可以跳过整个调用：调用图边、被调用方法的可达性、实参到形参的传播，以及实例调用的 `this` 传播仍然需要处理。

静态调用和实例调用都有这种情况，连接返回值之前先检查接收变量：

```java
Var result = invoke.getLValue();
if (result != null) {
    for (Var returnVar : callee.getIR().getReturnVars()) {
        addPFGEdge(
            pointerFlowGraph.getVarPtr(returnVar),
            pointerFlowGraph.getVarPtr(result)
        );
    }
}
```

这里检查的是调用语句是否存在接收变量，而不是判断返回值之后是否会被使用。只要 IR 中有结果变量，就按分析规则建立对应的返回值传播边。

参考：[Tai-e 作业 5：非上下文敏感指针分析](https://tai-e.pascal-lab.net/pa5.html)。
