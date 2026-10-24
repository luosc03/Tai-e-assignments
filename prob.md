# A6 问题整理

本笔记整理 A6 讨论中的概念问题、实现错误及修正结论。初次整理：2026-10-22；更新：2026-10-24。第 6 节保留早期 Solver 问题的排查记录，后续章节补充上下文选择策略与最终验证。

## 1. 空上下文应该返回什么

应返回 `ListContext.make()`，不能用 `null` 表示空上下文。

```java
@Override
public Context getEmptyContext() {
    return ListContext.make();
}
```

无参数的 `make()` 返回共享的 `EMPTY_CONTEXT`，它是一个有效的 `Context` 对象：长度为 0，打印为 `[]`。`null` 表示没有对象，后续对它调用 `getLength()` 等方法会导致空指针异常。

在 selector 内部，需要空上下文时也可以调用已经实现好的 `getEmptyContext()`。框架提供的 `CISelector` 就采用这种写法。

对于 A6 的一层敏感策略，堆上下文为零层，因此其 `selectHeapContext(...)` 应返回空上下文对象。

源码参考：[ListContext.java](A6/tai-e/src/main/java/pascal/taie/analysis/pta/core/cs/context/ListContext.java)、[CISelector.java](A6/tai-e/src/main/java/pascal/taie/analysis/pta/core/cs/selector/CISelector.java)。

## 2. 为什么 selectHeapContext 接收 CSMethod

`selectHeapContext(CSMethod method, Obj obj)` 中的 `method` 表示包含该分配点的方法及其当前上下文，不是新对象的构造方法。这里没有把 `new` 抽象成函数调用。

三个选择接口分别负责：

| 接口 | 作用 |
| --- | --- |
| `selectContext(CSCallSite, JMethod)` | 为静态调用的目标方法选择上下文 |
| `selectContext(CSCallSite, CSObj, JMethod)` | 为实例调用的目标方法选择上下文 |
| `selectHeapContext(CSMethod, Obj)` | 为当前方法中分配的对象选择堆上下文 |

处理 `new` 时，目标方法的上下文已经选好了。需要根据当前 `CSMethod` 的上下文决定对象的堆上下文，而不是再次从某个调用点推导方法上下文。

分配点用于标识基础抽象对象 `Obj`；堆上下文与 `Obj` 共同标识 `CSObj`。相同分配点在不同的方法上下文中被分析时，可以产生不同的上下文敏感对象。

A6 采用 `k` 层方法上下文、`k-1` 层堆上下文。因此一层策略使用空堆上下文，两层策略从所在方法上下文中保留最近的一层。[A6 官方说明 §3.3](https://tai-e.pascal-lab.net/pa6.html)

## 3. 为什么接口有 callee，课上规则却不需要

接口提供信息，不代表每种策略都会使用所有参数。A6 的六种策略不需要使用 `callee`；官方说明 §3.3 的提示 3 也明确说明，`selectContext(...)` 与 `selectHeapContext(...)` 的最后一个参数在本作业中不使用。

保留目标方法信息可以支持更一般的策略，例如根据被调用方法决定上下文层数。这是说明接口用途的例子，并非 A6 的额外实现要求。对实例调用，同一调用点也可能解析出不同的目标方法。

代码确实把课上的调用者上下文 `c` 与调用点 `l` 打包成了 `CSCallSite`：

| 课上的信息 | 代码中的获取方式 |
| --- | --- |
| 调用者上下文 `c` | `callSite.getContext()` |
| 调用点 `l` | `callSite.getCallSite()`，类型为 `Invoke` |

`CSCallSite` 中保存的是调用者的上下文；`selectContext(...)` 返回的才是被调用者的上下文。

源码参考：[ContextSelector.java](A6/tai-e/src/main/java/pascal/taie/analysis/pta/core/cs/selector/ContextSelector.java)、[CSCallSite.java](A6/tai-e/src/main/java/pascal/taie/analysis/pta/core/cs/element/CSCallSite.java)。

## 4. 为什么 receiver 到 this 直接传对象，普通参数却连边

对于 `x.m(arg)`，处理接收者时已经从 `pt(x)` 中取出了具体的 `CSObj`，并据此解析目标方法、选择目标上下文。应把当前这个对象加入对应 `this` 的指针集，通过 worklist 继续传播。

假设 `pt(x) = {o1, o2}`，对象敏感策略为 `m` 分别选择 `[o1]` 和 `[o2]` 两个上下文，则接收者传递应满足：

```text
pt([o1]:this) 包含 o1
pt([o2]:this) 包含 o2
```

如果连 `x -> [o1]:this`，就会把 `x` 指向的其他对象（如 `o2`）也传进去，混淆接收者与目标上下文的对应关系。

普通参数需要传递实参变量的整个指针集，因此建立：

```text
调用者上下文:实参变量 -> 被调用者上下文:形参变量
```

这条边还能传播实参以后新获得的对象。PFG 边连接的是指针与指针；对象是指针集中的元素，所以添加一个具体 receiver 对象与建立变量间的传播边是不同操作。

## 5. LoadArray 和 StoreArray 的处理

本次检查的两处数组连边方向与上下文使用是正确的。处理 `c:x` 新指向的数组对象 `obj` 时：

```text
Load： y = x[i]    obj[*] -> c:y
Store：x[i] = y    c:y -> obj[*]
```

`obj` 已经是带堆上下文的 `CSObj`，可以直接调用 `csManager.getArrayIndex(obj)`。先取出它的上下文与基础对象，再调用 `getCSObj(...)` 取回同一个抽象对象，属于冗余写法，不影响这两处逻辑。

数组索引节点使用数组对象的堆上下文；局部变量节点使用当前方法上下文，二者不能混用。这与 [A6 官方说明 §2.2](https://tai-e.pascal-lab.net/pa6.html) 的数组规则一致。

## 6. Solver 检查中发现的四处逻辑问题

以下保留首次检查时发现的问题及其修正方式。这四项均已修正，不表示当前仍有这些错误。首次检查仅核对源码；后续复查与实际测试结果见第 12 节。

相关文件：[Solver.java](A6/tai-e/src/main/java/pascal/taie/analysis/pta/cs/Solver.java)。

### 6.1 addReachable：混用了入口方法与可达方法登记接口

原实现用 `callGraph.addEntryMethod(csMethod)` 登记新发现的方法。这个接口只加入入口方法集合，不更新可达方法集合，也不设置调用点所属的 `CSMethod`。

这会使可达方法去重失效，调用图信息不完整，静态递归调用还可能导致重复递归处理并栈溢出。

应使用 `addReachableMethod(...)`。它的返回值可以直接用于判断是否首次发现该方法：

```java
private void addReachable(CSMethod csMethod) {
    if (callGraph.addReachableMethod(csMethod)) {
        // 处理该方法中的语句。
    }
}
```

这个结构可以替换原来的遍历判断与登记逻辑。`initialize()` 中将 `main` 登记为入口方法的 `addEntryMethod(...)` 应保留。

### 6.2 addReachable：静态调用遗漏调用图边

只处理目标方法和实参传递，不能代替登记调用关系。选好目标方法上下文后，需要增加调用图边。

把原来的 `addReachable(csManager.getCSMethod(context_t, jMethod));` 替换为：

```java
CSCallSite csCallSite = csManager.getCSCallSite(context, invoke);
CSMethod csCallee = csManager.getCSMethod(context_t, jMethod);

callGraph.addEdge(new Edge<>(
        CallGraphs.getCallKind(invoke), csCallSite, csCallee));
addReachable(csCallee);
```

### 6.3 addReachable：New 分支直接使用方法上下文创建对象

原实现直接把当前方法上下文作为堆上下文，绕过了 `selectHeapContext(...)`，不符合 A6 的堆上下文选择要求。

`New` 分支应按以下方式处理：

```java
Obj obj = heapModel.getObj(newInstance);
Context heapContext = contextSelector.selectHeapContext(csMethod, obj);
CSObj csObj = csManager.getCSObj(heapContext, obj);

CSVar csVar = csManager.getCSVar(context, newInstance.getLValue());
workList.addEntry(csVar, PointsToSetFactory.make(csObj));
```

局部变量仍使用方法上下文 `context`，新对象使用选择出来的 `heapContext`。

### 6.4 addReachable 与 processCall：返回值边方向反了

静态调用分支与实例调用处理中都曾把返回值边连反。正确方向是：

```text
被调用者上下文:返回变量 -> 调用者上下文:接收结果的变量
```

两处 `getReturnVars().forEach(...)` 内的连边均应写成：

```java
addPFGEdge(
        csManager.getCSVar(context_t, var),
        csManager.getCSVar(context, result)
);
```

## 7. 类型敏感中的 InType 应该如何获取

`InType(o)` 是对象 `o` 的分配点所在的类，不是对象自身的类型，也不是目标方法的声明类。

```java
Type inType = recv.getObject().getContainerType();
```

例如：

| 分配语句的位置 | `getType()` | `getContainerType()`，即 `InType` |
| --- | --- | --- |
| `A.make()` 中的 `new C()` | `C` | `A` |
| `B.make()` 中的 `new C()` | `C` | `B` |

一层类型敏感应返回：

```java
return ListContext.make(recv.getObject().getContainerType());
```

曾使用 `getType()`，这会把对象类型相同、分配点所在类不同的接收者合并到同一个方法上下文。应直接使用 `getContainerType()`，不必自行通过分配语句或所属方法推导；框架也为特殊对象定义了该 API 的行为。

源码参考：[Obj.java](A6/tai-e/src/main/java/pascal/taie/analysis/pta/core/heap/Obj.java)。

## 8. 静态方法没有 receiver，如何使用对象或类型敏感

静态调用没有接收者，不能新增基于接收者的 `Obj` 或 `InType` 元素。A6 规定，对象敏感和类型敏感的静态调用直接继承调用者的方法上下文：

```java
@Override
public Context selectContext(CSCallSite callSite, JMethod callee) {
    return callSite.getContext();
}
```

例如 `[A]:Service.run()` 调用静态 `Util.helper()`，目标上下文仍为 `[A]`；从 `[B]:Service.run()` 调用则为 `[B]`。静态调用虽然不提供新的接收者信息，已有的上下文仍可保留不同调用环境的区别。调用者上下文为空时，目标上下文也为空。

这里不能写成：

```java
return ListContext.make(callSite.getContext());
```

`make(...)` 不会展开已有上下文，而是把它当成一个元素。例如 `[A, B]` 会被包装成 `[[A, B]]`，其中的元素变成 `Context`，破坏原有结构。

调用点敏感的静态调用规则不同：仍需在调用者上下文后追加当前 `Invoke`，并按 `k` 截断。不能把对象/类型敏感的静态规则直接用于调用点敏感。

## 9. 方法上下文与堆上下文是否使用相同的元素

A6 中两者的元素种类一致，但长度和所描述的实体不同：

| 策略 | 方法上下文的元素 | 堆上下文的元素 |
| --- | --- | --- |
| 调用点敏感 | `Invoke` | `Invoke` |
| 对象敏感 | 普通抽象对象 `Obj` | 普通抽象对象 `Obj` |
| 类型敏感 | `Type` | `Type` |

方法上下文最多保留 `k` 层；对象的堆上下文从分配点所在方法的上下文中保留最近的 `k-1` 层。例如方法上下文为 `[e1, e2]` 时，在该方法内创建的对象使用堆上下文 `[e2]`。

需要区分三个对象各自的 `getContext()`：

| 调用 | 含义 |
| --- | --- |
| `callSite.getContext()` | 调用点所在方法的上下文 |
| `method.getContext()` | 当前 `CSMethod` 的方法上下文 |
| `recv.getContext()` | 接收者 `CSObj` 自身的堆上下文 |

元素类型统一，不代表选择上下文时可以互换这三个来源。`CSObj` 是“堆上下文 + Obj”的配对，不能直接作为对象敏感的上下文元素；应放入 `recv.getObject()`。

## 10. 为什么两层对象/类型敏感从接收者的堆上下文取上一层

对象可以离开创建它的方法，经过返回、赋值、传参，再被其他方法使用。堆上下文保存创建时的信息，调用者上下文表示当前使用它的环境；两者可能不同。

下面用同一程序说明。`FA`、`FB`、`P` 分别标记三个分配点对应的普通 `Obj`：

```java
class A {
    static Product build() {
        Factory f = new Factory(); // FA
        return f.make();
    }
}

class B {
    static Product build() {
        Factory f = new Factory(); // FB
        return f.make();
    }
}

class Factory {
    Product make() {
        return new Product();     // P：两条路径执行同一个分配点
    }
}

class Product {
    void use() {}
}

class Demo {
    static void consume(Product p) {
        p.use();                  // 两条路径最终经过同一个调用点
    }

    public static void main(String[] args) {
        Product x = A.build();
        Product y = B.build();
        consume(x);
        consume(y);
    }
}
```

### 10.1 两层对象敏感的推演

`[FA]:P` 表示带堆上下文 `[FA]` 的对象 `P`；`[FA]:make` 表示方法 `make` 的上下文为 `[FA]`。二者使用相似记号，但上下文属于不同实体。

按 A6 的 2-object / 1-heap 规则：

| 分析阶段 | 从 `A.build()` 来的路径 | 从 `B.build()` 来的路径 |
| --- | --- | --- |
| 创建的 Factory 对象 | `[]:FA` | `[]:FB` |
| `Factory.make()` 的方法上下文 | `[FA]` | `[FB]` |
| 在分配点 `P` 创建的 Product 对象 | `[FA]:P` | `[FB]:P` |
| `consume()` 的方法上下文 | `[]` | `[]` |
| `Product.use()` 的方法上下文 | `[FA, P]` | `[FB, P]` |

过程分为三步：

1. `new Product()` 发生时，从创建者方法的上下文中保留一层，作为 Product 对象的堆上下文。因此同一分配点 `P` 对应两个不同的 `CSObj`。
2. 对象返回并传入 `consume()` 时，传播的是原来的 `CSObj`，不会把它的堆上下文改成 `consume()` 的方法上下文。于是同一个 `[]:consume/p` 同时指向 `{[FA]:P, [FB]:P}`。
3. 对两个接收者分别处理 `p.use()`：保留各自的堆上下文，再追加普通对象 `P`，得到 `[FA, P]` 与 `[FB, P]`。

如果使用 `callSite.getContext()`，拿到的都是 `consume()` 的 `[]`，两条路径都会选出 `[P]`，创建来源的区别随之消失。因此这里取的是 `recv.getContext()`。

### 10.2 两层类型敏感的推演

同一程序中：

```text
InType(FA) = A        // new Factory() 写在 A 中
InType(FB) = B        // new Factory() 写在 B 中
InType(P)  = Factory  // new Product() 写在 Factory 中
```

所以类型敏感的对应过程为：

| 分析阶段 | 从 `A.build()` 来的路径 | 从 `B.build()` 来的路径 |
| --- | --- | --- |
| `Factory.make()` 的方法上下文 | `[A]` | `[B]` |
| Product 对象 | `[A]:P` | `[B]:P` |
| `consume()` 的方法上下文 | `[]` | `[]` |
| `Product.use()` 的方法上下文 | `[A, Factory]` | `[B, Factory]` |

接收者的堆上下文已经存放了上一层类型 `[A]` 或 `[B]`，调用时只需追加 `InType(P)`。若取当前调用者上下文，两条路径都会变成 `[Factory]`。

两层对象敏感沿对象的创建关系扩展上下文；两层类型敏感将这条关系中的对象抽象成分配点所在类型；调用点敏感则沿调用关系扩展上下文。这是三种策略选择上下文来源不同的原因。

## 11. Selector 实现中实际遇到的错误

以下问题均已修正：

- `_1TypeSelector` 使用了 `getType()`：应使用 `getContainerType()` 获取 `InType`。
- `_2ObjSelector` 和 `_2TypeSelector` 的实例分支从 `callSite.getContext()` 取上一层：应取 `recv.getContext()`。
- `_2ObjSelector` 把 `recv` 本身作为上下文元素：应使用普通对象 `recv.getObject()`。有堆上下文和空堆上下文两个分支都要修改；曾只改到前一个分支，导致 `TwoObject` 仍然失败。
- 两层对象/类型敏感的静态分支使用 `ListContext.make(callSite.getContext())`：应直接返回 `callSite.getContext()`，避免嵌套 `Context`。

规则汇总如下。设 `c` 为调用者的方法上下文，`l` 为当前 `Invoke`，`h` 为接收者的堆上下文，`o` 为 `recv.getObject()`，`T = InType(o)`；`tailN` 表示保留最近的至多 N 个元素：

| 策略 | 实例方法上下文 | 静态方法上下文 |
| --- | --- | --- |
| 1-call | `[l]` | `[l]` |
| 2-call | `tail2(c + [l])` | `tail2(c + [l])` |
| 1-object | `[o]` | `c` |
| 2-object | `tail2(h + [o])` | `c` |
| 1-type | `[T]` | `c` |
| 2-type | `tail2(h + [T])` | `c` |

各策略的 `selectHeapContext` 均从分配点所在方法的上下文保留最近的至多 `k-1` 层。上下文元素按从旧到新的顺序排列。

## 12. Solver 后续复查与完成验证

修正第 6 节的历史问题后，后续检查没有发现新的确定功能性错误。以下处理是正确的：

- 在递归分析静态目标方法之前先登记当前方法可达，避免重复扫描。
- `CSCallGraph.addReachableMethod()` 会设置该方法中调用点的所属 `CSMethod`，不必重复设置。
- `processCall()` 把当前 receiver 对象传给 `this`，位于“是否新增调用边”的判断之外。多个 receiver 可能共享同一调用边，但每个对象仍然需要传播给 `this`。
- 实参边和返回值边分别使用对应的调用者、被调用者上下文；字段与数组节点保留所属 `CSObj` 的堆上下文。
- `addPFGEdge()` 给新边补传源指针已有的对象，`propagate()` 只向后继传播新增对象。

可达性判断中的线性遍历与 `AtomicBoolean` 可以用 `addReachableMethod()` 的布尔返回值简化；重复获取同一个数组 `CSObj`、为后继加入空差集也只是额外开销，不应当作结果错误。

排查时曾出现 13 个测试中 11 个通过，失败项为 `TwoObject`、`TwoType`；修正大部分 selector 问题后为 12 个通过，剩余失败来自 `_2ObjSelector` 的空堆上下文分支仍使用 `CSObj`。临时副本验证用于定位这些错误，完成状态以最终源码的测试为准。

2026-10-24，在最终源码的 `A6/tai-e` 目录执行：

```powershell
.\gradlew.bat --offline test --tests pascal.taie.analysis.pta.CSPTATest --console=plain
```

结果为 `BUILD SUCCESSFUL`，现有 13 个测试全部通过，失败、错误与跳过均为 0。测试涵盖 New、Assign、StoreLoad、Call、InstanceField、StaticField、Array，以及六种一层/两层上下文敏感策略。A6 实现已完成。

## 参考资料

- [Tai-e 官方课程资料入口](https://tai-e.pascal-lab.net/lectures.html)
- [A6：上下文敏感的指针分析](https://tai-e.pascal-lab.net/pa6.html)：§2.2 的分析规则、§2.3 的框架接口，以及 §3.3 的上下文策略要求。
