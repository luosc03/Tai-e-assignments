/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta.ci;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.DefaultCallGraph;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.ReferenceLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.FieldRef;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ReferenceType;
import pascal.taie.language.type.Type;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final HeapModel heapModel;

    private DefaultCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private StmtProcessor stmtProcessor;

    private ClassHierarchy hierarchy;

    Solver(HeapModel heapModel) {
        this.heapModel = heapModel;
    }

    /**
     * Runs pointer analysis algorithm.
     */
    void solve() {
        initialize();
        analyze();
    }

    /**
     * Initializes pointer analysis.
     */
    private void initialize() {
        workList = new WorkList();
        pointerFlowGraph = new PointerFlowGraph();
        callGraph = new DefaultCallGraph();
        stmtProcessor = new StmtProcessor();
        hierarchy = World.get().getClassHierarchy();
        // initialize main method
        JMethod main = World.get().getMainMethod();
        callGraph.addEntryMethod(main);
        addReachable(main);
    }

    /**
     * Processes new reachable method.
     */
    private void addReachable(JMethod method) {
        // TODO - finish me
        AtomicBoolean flag = new AtomicBoolean(false);
        callGraph.reachableMethods().forEach(reachableMethod -> {
            if (reachableMethod.equals(method)) {
                flag.set(true);
            }
        });
        if (!flag.get()) {
            callGraph.addReachableMethod(method);
            method.getIR().getStmts().forEach(stmt -> {
                if (stmt instanceof Invoke invoke && invoke.isStatic()) {
                    // just static
                    JMethod jMethod = resolveCallee(null, invoke);
                    callGraph.addEdge(new Edge<>(CallGraphs.getCallKind(invoke),invoke, jMethod));
                    addReachable(jMethod);
                    int size = invoke.getInvokeExp().getArgs().size();
                    for (int i = 0; i < size; i++) {
                        Var actual = invoke.getInvokeExp().getArg(i); // 实参
                        Var formal = jMethod.getIR().getParam(i);      // 形参
                        Pointer source = pointerFlowGraph.getVarPtr(actual);
                        Pointer target = pointerFlowGraph.getVarPtr(formal);
                        addPFGEdge(source, target);
                    }
                    Var result = invoke.getLValue();
                    if (result != null) {
                        for (Var returnVar : jMethod.getIR().getReturnVars()) {
                            addPFGEdge(
                                    pointerFlowGraph.getVarPtr(returnVar),
                                    pointerFlowGraph.getVarPtr(result)
                            );
                        }
                    }
                } else if  (stmt instanceof New newInstance) {
                    // no condition
                    VarPtr varPtr = pointerFlowGraph.getVarPtr(newInstance.getLValue());
                    workList.addEntry(varPtr, new PointsToSet(heapModel.getObj(newInstance)));
                } else if (stmt instanceof Copy copy) {
                    // no condition
                    Var x = copy.getLValue();
                    Var y = copy.getRValue();
                    addPFGEdge(pointerFlowGraph.getVarPtr(y), pointerFlowGraph.getVarPtr(x));
                } else if (stmt instanceof StoreField storeField && storeField.isStatic()) {
                    // just static
                    JField field = storeField.getFieldRef().resolve();
                    addPFGEdge(
                            pointerFlowGraph.getVarPtr(storeField.getRValue()), // y
                            pointerFlowGraph.getStaticField(field)             // T.f
                    );
                } else if (stmt instanceof LoadField loadField && loadField.isStatic()) {
                    // just static
                    JField field = loadField.getFieldRef().resolve();
                    addPFGEdge(
                            pointerFlowGraph.getStaticField(field),           // T.f
                            pointerFlowGraph.getVarPtr(loadField.getLValue()) // y
                    );
                }
            });
        }
    }

    /**
     * Processes statements in new reachable methods.
     */
    private class StmtProcessor implements StmtVisitor<Void> {
        // TODO - if you choose to implement addReachable()
        //  via visitor pattern, then finish me

    }

    /**
     * Adds an edge "source -> target" to the PFG.
     */
    private void addPFGEdge(Pointer source, Pointer target) {
        // TODO - finish me
        boolean contains = pointerFlowGraph.getSuccsOf(source).contains(target);
        if (contains) {
            return;
        }
        pointerFlowGraph.addEdge(source, target);
        if(!source.getPointsToSet().isEmpty()) {
            workList.addEntry(target, source.getPointsToSet());
        }
    }

    /**
     * Processes work-list entries until the work-list is empty.
     */
    private void analyze() {
        // TODO - finish me
        while (!workList.isEmpty()) {
            WorkList.Entry entry = workList.pollEntry();
            PointsToSet delta = propagate(entry.pointer(), entry.pointsToSet());
            if (entry.pointer() instanceof VarPtr varPtr) {
                Var x = varPtr.getVar();
                if (delta != null) {
                    for (Obj obj : delta) {
                        x.getLoadFields().forEach(loadField -> {
                            JField field = loadField.getFieldRef().resolve();
                            Var t = loadField.getLValue();
                            addPFGEdge(pointerFlowGraph.getInstanceField(obj, field), pointerFlowGraph.getVarPtr(t));
                        });
                        x.getStoreFields().forEach(storeField -> {
                            JField field = storeField.getFieldRef().resolve();
                            Var t = storeField.getRValue();
                            addPFGEdge(pointerFlowGraph.getVarPtr(t), pointerFlowGraph.getInstanceField(obj, field));
                        });
                        x.getLoadArrays().forEach(loadArray -> {
                            Var base = loadArray.getRValue().getBase();
                            Var lValue = loadArray.getLValue();
                            addPFGEdge(pointerFlowGraph.getArrayIndex(obj), pointerFlowGraph.getVarPtr(lValue));
                        });
                        x.getStoreArrays().forEach(storeArray -> {
                            Var base = storeArray.getLValue().getBase();
                            Var rValue = storeArray.getRValue();
                            addPFGEdge(pointerFlowGraph.getVarPtr(rValue), pointerFlowGraph.getArrayIndex(obj));
                        });
                        processCall(x, obj);
                    }
                }
            }
        }
    }

    /**
     * Propagates pointsToSet to pt(pointer) and its PFG successors,
     * returns the difference set of pointsToSet and pt(pointer).
     */
    private PointsToSet propagate(Pointer pointer, PointsToSet pointsToSet) {
        // TODO - finish me
        if (!pointsToSet.isEmpty()) {
            PointsToSet pointsToSet1 = pointer.getPointsToSet();
            PointsToSet pointsToSet2 = new PointsToSet();
            for (Obj obj : pointsToSet) {
                if(!pointsToSet1.contains(obj)) {
                    pointsToSet2.addObject(obj);
                }
                pointsToSet1.addObject(obj);
            }
            pointerFlowGraph.getSuccsOf(pointer).forEach(s->
                    workList.addEntry(s, pointsToSet2)
            );
            return pointsToSet2;
        }

        return null;
    }

    /**
     * Processes instance calls when points-to set of the receiver variable changes.
     *
     * @param var the variable that holds receiver objects
     * @param recv a new discovered object pointed by the variable.
     */
    private void processCall(Var var, Obj recv) {
        // TODO - finish me
        var.getInvokes().forEach(invoke -> {
            JMethod callee = resolveCallee(recv, invoke);
            if (!callee.isStatic()) {
                Var thisVar = callee.getIR().getThis();
                workList.addEntry(
                        pointerFlowGraph.getVarPtr(thisVar),
                        new PointsToSet(recv)
                );
                Edge<Invoke, JMethod> edge = new Edge<>(
                        CallGraphs.getCallKind(invoke), invoke, callee
                );

                if (callGraph.addEdge(edge)) {
                    addReachable(callee);
                    // 添加实参 → 形参的 PFG 边
                    int size = invoke.getInvokeExp().getArgs().size();
                    for (int i = 0; i < size; i++) {
                        Var actual = invoke.getInvokeExp().getArg(i); // 实参
                        Var formal = callee.getIR().getParam(i);      // 形参
                        Pointer source = pointerFlowGraph.getVarPtr(actual);
                        Pointer target = pointerFlowGraph.getVarPtr(formal);
                        addPFGEdge(source, target);
                    }
                    // 添加返回变量 → 调用结果的 PFG 边
                    Var result = invoke.getLValue();
                    if (result != null) {
                        for (Var returnVar : callee.getIR().getReturnVars()) {
                            addPFGEdge(
                                    pointerFlowGraph.getVarPtr(returnVar),
                                    pointerFlowGraph.getVarPtr(result)
                            );
                        }
                    }
                }
            }
        });
    }

    /**
     * Resolves the callee of a call site with the receiver object.
     *
     * @param recv     the receiver object of the method call. If the callSite
     *                 is static, this parameter is ignored (i.e., can be null).
     * @param callSite the call site to be resolved.
     * @return the resolved callee.
     */
    private JMethod resolveCallee(Obj recv, Invoke callSite) {
        Type type = recv != null ? recv.getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    CIPTAResult getResult() {
        return new CIPTAResult(pointerFlowGraph, callGraph);
    }
}
