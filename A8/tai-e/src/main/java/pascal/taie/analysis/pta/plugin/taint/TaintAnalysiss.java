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

package pascal.taie.analysis.pta.plugin.taint;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.*;
import pascal.taie.analysis.pta.core.heap.MockObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.cs.Solver;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;

import java.util.*;

public class TaintAnalysiss {

    private static final Logger logger = LogManager.getLogger(TaintAnalysiss.class);

    private final TaintManager manager;

    private final TaintConfig config;

    private final Solver solver;

    private final CSManager csManager;

    private final Context emptyContext;

    private final Set<Source> sources;
    private final Set<Sink> sinks;

    private final Set<TaintTransfer> transfers;

    private final Map<CSVar, Set<Edge<CSCallSite, CSMethod>>> modify;

    public TaintAnalysiss(Solver solver) {
        manager = new TaintManager();
        this.solver = solver;
        csManager = solver.getCSManager();
        emptyContext = solver.getContextSelector().getEmptyContext();
        config = TaintConfig.readConfig(
                solver.getOptions().getString("taint-config"),
                World.get().getClassHierarchy(),
                World.get().getTypeSystem());
        logger.info(config);
        sources = config.getSources();
        transfers = config.getTransfers();
        sinks = config.getSinks();
        modify = new HashMap<>();

    }

    // TODO - finish me
    public boolean isTaint(Obj obj) {
        return manager.isTaint(obj);
    }

    public boolean isSource(JMethod m, Type u) {
        return sources.contains(new Source(m, u));
    }

    public Obj makeTaint(Invoke source, Type type) {
        return manager.makeTaint(source, type);
    }


    public void onFinish() {
        Set<TaintFlow> taintFlows = collectTaintFlows();
        solver.getResult().storeResult(getClass().getName(), taintFlows);
    }

    private Set<TaintFlow> collectTaintFlows() {
        Set<TaintFlow> taintFlows = new TreeSet<>();
        PointerAnalysisResult result = solver.getResult();
        // TODO - finish me
        // You could query pointer analysis results you need via variable result.
        for (Edge<CSCallSite, CSMethod> edge : solver.edges) {
            CSCallSite callSite = edge.getCallSite();
            CSMethod callee = edge.getCallee();
            for (Sink sink : sinks) {
                JMethod method = callee.getMethod();
                if (!sink.method().equals(method)) {
                    continue;
                }
                System.out.println(method);
                int index = sink.index();
                Var arg = callSite.getCallSite().getInvokeExp().getArg(index);
                if (arg == null) {
                    continue;
                }
                CSVar t = csManager.getCSVar(callSite.getContext(), arg);
                for (CSObj csObj : t.getPointsToSet()) {
                    if (isTaint(csObj.getObject())) {
                        int j = manager.getSourceCall(csObj.getObject()).getIndex();
                        Invoke sourceCall = manager.getSourceCall(csObj.getObject());
                        taintFlows.add(new TaintFlow(sourceCall, callSite.getCallSite(),index));
                    }
                }
            }
        }
        return taintFlows;
    }

    public Context getEmptyContext() {
        return emptyContext;
    }

    private CSVar getVar(CSCallSite site, int signal) {
        Var answer;
        Invoke invoke = site.getCallSite();
        if (signal == TaintTransfer.BASE) {
            if (!(invoke.getInvokeExp() instanceof InvokeInstanceExp instanceExp)) {
                return null;
            }
            answer = instanceExp.getBase();
        } else if (signal == TaintTransfer.RESULT) {
            answer = invoke.getResult();
        } else {
            answer = invoke.getInvokeExp().getArg(signal);
        }

        return answer == null ? null : csManager.getCSVar(site.getContext(), answer);
    }

    public void taintTransfer(Edge<CSCallSite, CSMethod> edge) {
        // 实现污点传播
        CSCallSite callSite = edge.getCallSite();
        CSMethod callee = edge.getCallee();
        for (TaintTransfer transfer : transfers) {
            if (!callee.getMethod().equals(transfer.method())) {
                continue;
            }
            CSVar from = getVar(callSite, transfer.from());
            CSVar to = getVar(callSite, transfer.to());
            if (from == null || to == null) {
                continue;
            }
            // 从from传播到to
            modify.computeIfAbsent(from, k -> new HashSet<>()).add(edge);
            PointsToSet make = PointsToSetFactory.make();
            PointsToSet pts = to.getPointsToSet();
            for (CSObj csObj : from.getPointsToSet()) {
                if (pts.contains(csObj)) {continue;}
                Obj object = csObj.getObject();
                if (isTaint(object)) {
                    Obj transferred = manager.makeTaint(
                            manager.getSourceCall(object),
                            transfer.type());

                    make.addObject(csManager.getCSObj(emptyContext, transferred));
                }
            }
            solver.addPointsTo(to, make);
        }
    }

    public Map<CSVar, Set<Edge<CSCallSite, CSMethod>>> getModify() {
        return modify;
    }
}
