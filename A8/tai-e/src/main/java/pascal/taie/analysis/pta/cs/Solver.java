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

package pascal.taie.analysis.pta.cs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.PointerAnalysisResultImpl;
import pascal.taie.analysis.pta.core.cs.CSCallGraph;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.element.MapBasedCSManager;
import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.plugin.taint.TaintAnalysiss;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.StmtVisitor;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final AnalysisOptions options;

    private final HeapModel heapModel;

    private final ContextSelector contextSelector;

    private CSManager csManager;

    private CSCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private TaintAnalysiss taintAnalysis;

    private PointerAnalysisResult result;

    public Set<Edge<CSCallSite, CSMethod>> edges;

    Solver(AnalysisOptions options, HeapModel heapModel,
           ContextSelector contextSelector) {
        this.options = options;
        this.heapModel = heapModel;
        this.contextSelector = contextSelector;
        edges = new HashSet<>();
    }

    public AnalysisOptions getOptions() {
        return options;
    }

    public ContextSelector getContextSelector() {
        return contextSelector;
    }

    public CSManager getCSManager() {
        return csManager;
    }

    void solve() {
        initialize();
        analyze();
        taintAnalysis.onFinish();
    }

    private void initialize() {
        csManager = new MapBasedCSManager();
        callGraph = new CSCallGraph(csManager);
        pointerFlowGraph = new PointerFlowGraph();
        workList = new WorkList();
        taintAnalysis = new TaintAnalysiss(this);
        // process program entry, i.e., main method
        Context defContext = contextSelector.getEmptyContext();
        JMethod main = World.get().getMainMethod();
        CSMethod csMethod = csManager.getCSMethod(defContext, main);
        callGraph.addEntryMethod(csMethod);
        addReachable(csMethod);
    }

    /**
     * Processes new reachable context-sensitive method.
     */
    private void addReachable(CSMethod csMethod) {
        // TODO - finish me
        AtomicBoolean flag = new AtomicBoolean(true);
        callGraph.reachableMethods().forEach(reachableMethod -> {
            if (reachableMethod.equals(csMethod)) {
                flag.set(false);
            }
        });
        if (flag.get()) {
            callGraph.addReachableMethod(csMethod);
            csMethod.getMethod().getIR().forEach(stmt -> {
                Context context = csMethod.getContext();
                if (stmt instanceof Invoke invoke && invoke.isStatic()) {
                    // just static
                    JMethod jMethod = resolveCallee(null, invoke);
                    Context context_t = contextSelector.selectContext(
                            csManager.getCSCallSite(context, invoke), jMethod);

                    CSCallSite csCallSite = csManager.getCSCallSite(context, invoke);
                    CSMethod csCallee = csManager.getCSMethod(context_t, jMethod);
                    Edge<CSCallSite, CSMethod> csCallSiteCSMethodEdge = new Edge<>(
                            CallGraphs.getCallKind(invoke), csCallSite, csCallee);
                    callGraph.addEdge(csCallSiteCSMethodEdge);
                    addReachable(csCallee);
                    edges.add(csCallSiteCSMethodEdge);

                    int size = invoke.getInvokeExp().getArgs().size();
                    for (int i = 0; i < size; i++) {
                        Var actual = invoke.getInvokeExp().getArg(i); // 实参
                        Var formal = jMethod.getIR().getParam(i);      // 形参
                        CSVar target = csManager.getCSVar(context_t, formal);
                        CSVar source = csManager.getCSVar(context, actual);
                        addPFGEdge(source, target);
                    }
                    Var result = invoke.getResult();
                    if (result != null) {
                        jMethod.getIR().getReturnVars().forEach(var -> {
                            addPFGEdge(
                                    csManager.getCSVar(context_t, var),
                                    csManager.getCSVar(context, result)
                            );
                        });
                        // start
                        // delete 静态调用可能会产生source，进行处理
                        Type returnType = invoke.getInvokeExp().getMethodRef().getReturnType();
                        if (taintAnalysis.isSource(jMethod, returnType)) {
                            CSVar source = csManager.getCSVar(context, result);
                            CSObj obj = csManager.getCSObj(taintAnalysis.getEmptyContext(),
                                    taintAnalysis.makeTaint(invoke, returnType));
                            workList.addEntry(source, PointsToSetFactory.make(obj));
                        }
                    }
                    taintAnalysis.taintTransfer(csCallSiteCSMethodEdge);
                } else if  (stmt instanceof New newInstance) {
                    // no condition
                    Obj obj = heapModel.getObj(newInstance);
                    Context heapContext = contextSelector.selectHeapContext(csMethod, obj);
                    CSObj csObj = csManager.getCSObj(heapContext, obj);

                    CSVar csVar = csManager.getCSVar(context, newInstance.getLValue());
                    workList.addEntry(csVar, PointsToSetFactory.make(csObj));
                } else if (stmt instanceof Copy copy) {
                    // no condition
                    Var x = copy.getLValue();
                    Var y = copy.getRValue();
                    addPFGEdge(
                            csManager.getCSVar(context, y),
                            csManager.getCSVar(context, x)
                    );
                } else if (stmt instanceof StoreField storeField && storeField.isStatic()) {
                    // just static
                    JField field = storeField.getFieldRef().resolve();
                    Var rValue = storeField.getRValue();
                    addPFGEdge(
                            csManager.getCSVar(context, rValue),
                            csManager.getStaticField(field)
                    );
                } else if (stmt instanceof LoadField loadField && loadField.isStatic()) {
                    // just static
                    JField field = loadField.getFieldRef().resolve();
                    Var lValue = loadField.getLValue();
                    addPFGEdge(
                            csManager.getStaticField(field),
                            csManager.getCSVar(context, lValue)
                    );
                }
            });
        }
    }

    /**
     * Processes the statements in context-sensitive new reachable methods.
     */
    private class StmtProcessor implements StmtVisitor<Void> {

        private final CSMethod csMethod;

        private final Context context;

        private StmtProcessor(CSMethod csMethod) {
            this.csMethod = csMethod;
            this.context = csMethod.getContext();
        }

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

    public void addPointsTo(CSVar target, PointsToSet objects) {
        if (!objects.isEmpty()) {
            workList.addEntry(target, objects);
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
            if (entry.pointer() instanceof CSVar csVar) {
                Var x = csVar.getVar();
                if (delta != null) {
                    for (Edge<CSCallSite, CSMethod> edge :
                            taintAnalysis.getModify().getOrDefault(csVar, new HashSet<>())) {
                        taintAnalysis.taintTransfer(edge);
                    }
                    for (CSObj obj : delta) {
                        x.getLoadFields().forEach(loadField -> {
                            JField field = loadField.getFieldRef().resolve();
                            Var t = loadField.getLValue();
                            addPFGEdge(csManager.getInstanceField(obj, field),
                                    csManager.getCSVar(csVar.getContext(), t));
                        });
                        x.getStoreFields().forEach(storeField -> {
                            JField field = storeField.getFieldRef().resolve();
                            Var t = storeField.getRValue();
                            addPFGEdge(csManager.getCSVar(csVar.getContext(), t),
                                    csManager.getInstanceField(obj, field));
                        });
                        x.getLoadArrays().forEach(loadArray -> {
                            // y = x[i]
                            // context1:o_u[*] -> context0:y
                            Var lValue = loadArray.getLValue();
                            Context context0 = csVar.getContext();
                            Context context1 = obj.getContext();
                            addPFGEdge(
                                    csManager.getArrayIndex(csManager.getCSObj(context1, obj.getObject())),
                                    csManager.getCSVar(context0, lValue)
                            );
                        });
                        x.getStoreArrays().forEach(storeArray -> {
                            Var rValue = storeArray.getRValue();
                            Context context0 = csVar.getContext();
                            Context context1 = obj.getContext();
                            addPFGEdge(
                                    csManager.getCSVar(context0, rValue),
                                    csManager.getArrayIndex(csManager.getCSObj(context1, obj.getObject()))
                            );
                        });
                        processCall(csVar, obj);
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
            PointsToSet pointsToSet2 = PointsToSetFactory.make();
            for (CSObj obj : pointsToSet) {
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
     * @param recv    the receiver variable
     * @param recvObj set of new discovered objects pointed by the variable.
     */
    private void processCall(CSVar recv, CSObj recvObj) {
        // TODO - finish me
        Context context = recv.getContext();
        Context context1 = recvObj.getContext();
        recv.getVar().getInvokes().forEach(invoke -> {
            if (invoke.isStatic()) return;
            JMethod callee = resolveCallee(recvObj, invoke);
            CSCallSite csCallSite = csManager.getCSCallSite(context, invoke);
            Context context_t = contextSelector.selectContext(csCallSite, recvObj, callee);
            CSMethod ctm = csManager.getCSMethod(context_t, callee);
            Var aThis = callee.getIR().getThis();
            workList.addEntry(
                    csManager.getCSVar(context_t, aThis),
                    PointsToSetFactory.make(recvObj)
            );

            Edge<CSCallSite, CSMethod> csCallSiteCSMethodEdge =
                    new Edge<>(CallGraphs.getCallKind(invoke),
                            csManager.getCSCallSite(context, invoke), ctm);
            edges.add(csCallSiteCSMethodEdge);
            if (callGraph.addEdge(csCallSiteCSMethodEdge)) {
                addReachable(ctm);
                int size = invoke.getInvokeExp().getArgs().size();
                for (int i = 0; i < size; i++) {
                    Var actual = invoke.getInvokeExp().getArg(i); // 实参
                    Var formal = callee.getIR().getParam(i);      // 形参
                    CSVar target = csManager.getCSVar(context_t, formal);
                    CSVar source = csManager.getCSVar(context, actual);
                    addPFGEdge(source, target);
                }
                Var result1 = invoke.getResult();
                if (result1 != null) {
                    callee.getIR().getReturnVars().forEach(var -> {
                        addPFGEdge(
                                csManager.getCSVar(context_t, var),
                                csManager.getCSVar(context, result1)
                        );
                    });
                    // start
                    // delete 非static方法也可能产生source
                    Type returnType = invoke.getInvokeExp().getMethodRef().getReturnType();
                    if (taintAnalysis.isSource(callee, returnType)) {
                        CSVar source = csManager.getCSVar(context, result1);
                        CSObj obj = csManager.getCSObj(taintAnalysis.getEmptyContext(),
                                taintAnalysis.makeTaint(invoke, returnType));
                        workList.addEntry(source, PointsToSetFactory.make(obj));
                    }
                    // end
                }
                taintAnalysis.taintTransfer(csCallSiteCSMethodEdge);
            }
        });
    }

    /**
     * Resolves the callee of a call site with the receiver object.
     *
     * @param recv the receiver object of the method call. If the callSite
     *             is static, this parameter is ignored (i.e., can be null).
     * @param callSite the call site to be resolved.
     * @return the resolved callee.
     */
    private JMethod resolveCallee(CSObj recv, Invoke callSite) {
        Type type = recv != null ? recv.getObject().getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    public PointerAnalysisResult getResult() {
        if (result == null) {
            result = new PointerAnalysisResultImpl(csManager, callGraph);
        }
        return result;
    }
}
