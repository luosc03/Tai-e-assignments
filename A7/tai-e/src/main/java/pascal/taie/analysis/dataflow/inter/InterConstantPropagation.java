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

package pascal.taie.analysis.dataflow.inter;

import pascal.taie.World;
import pascal.taie.analysis.dataflow.analysis.constprop.CPFact;
import pascal.taie.analysis.dataflow.analysis.constprop.ConstantPropagation;
import pascal.taie.analysis.dataflow.analysis.constprop.Value;
import pascal.taie.analysis.graph.cfg.CFGBuilder;
import pascal.taie.analysis.graph.icfg.CallEdge;
import pascal.taie.analysis.graph.icfg.CallToReturnEdge;
import pascal.taie.analysis.graph.icfg.NormalEdge;
import pascal.taie.analysis.graph.icfg.ReturnEdge;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;

import java.util.*;

/**
 * Implementation of interprocedural constant propagation for int values.
 */
public class InterConstantPropagation extends
        AbstractInterDataflowAnalysis<JMethod, Stmt, CPFact> {

    public static final String ID = "inter-constprop";

    private final ConstantPropagation cp;

    public InterConstantPropagation(AnalysisConfig config) {
        super(config);
        cp = new ConstantPropagation(new AnalysisConfig(ConstantPropagation.ID));
    }

    private PointerAnalysisResult pta;

    private final Map<Obj, Set<StoreArray>> storeArraysStmt = new HashMap<>();
    private final Map<JField, Set<StoreField>> instanceStores = new HashMap<>();
    private final Map<JField, Set<StoreField>> staticStores = new HashMap<>();
    private final Map<Stmt, Set<Stmt>> dependentLoads = new HashMap<>();

    @Override
    protected void initialize() {
        String ptaId = getOptions().getString("pta");
        this.pta = World.get().getResult(ptaId);
        for (Stmt stmt : icfg) {
            if (stmt instanceof StoreArray store) {
                Var base = store.getArrayAccess().getBase();
                for (Obj obj : pta.getPointsToSet(base)) {
                    storeArraysStmt
                            .computeIfAbsent(obj, key -> new HashSet<>())
                            .add(store);
                }
            } else if (stmt instanceof StoreField store) {
                JField field = store.getFieldRef().resolve();
                Map<JField, Set<StoreField>> stores =
                        store.isStatic() ? staticStores : instanceStores;
                stores.computeIfAbsent(field, k -> new HashSet<>()).add(store);
            }
        }

        for (Stmt stmt : icfg) {
            if (stmt instanceof LoadArray load) {
                for (StoreArray store : getArrayStores(load)) {
                    dependentLoads.computeIfAbsent(store,
                            k -> new HashSet<>()).add(load);
                }
            } else if (stmt instanceof LoadField load) {
                for (StoreField store : getFieldStores(load)) {
                    dependentLoads.computeIfAbsent(store, key -> new HashSet<>())
                            .add(load);
                }
            }
        }
    }

    @Override
    public boolean isForward() {
        return cp.isForward();
    }

    @Override
    public CPFact newBoundaryFact(Stmt boundary) {
        IR ir = icfg.getContainingMethodOf(boundary).getIR();
        return cp.newBoundaryFact(ir.getResult(CFGBuilder.ID));
    }

    @Override
    public CPFact newInitialFact() {
        return cp.newInitialFact();
    }

    @Override
    public void meetInto(CPFact fact, CPFact target) {
        cp.meetInto(fact, target);
    }

    @Override
    protected boolean transferCallNode(Stmt stmt, CPFact in, CPFact out) {
        // TODO - finish me
        return out.copyFrom(in);
    }

    boolean mayAliasIndex(Value value1, Value value2) {
        if (value1.isUndef() || value2.isUndef()) {
            return false;
        }
        return value1.isNAC() || value2.isNAC() || value1.equals(value2);
    }

    private boolean transferLoad(Var lhs, Value value, CPFact in, CPFact out) {
        CPFact newOut = in.copy();
        newOut.update(lhs, value);
        if (out.equals(newOut)) {
            return false;
        }
        out.clear();
        out.copyFrom(newOut);
        return true;
    }

    private Set<StoreArray> getArrayStores(LoadArray load) {
        Set<StoreArray> stores = new HashSet<>();
        Var base = load.getArrayAccess().getBase();
        for (Obj array : pta.getPointsToSet(base)) {
            stores.addAll(storeArraysStmt.getOrDefault(array, Set.of()));
        }
        return stores;
    }

    private Set<StoreField> getFieldStores(LoadField load) {
        // find all alias field
        JField field = load.getFieldAccess().getFieldRef().resolve();
        if (load.isStatic()) {
            return staticStores.getOrDefault(field, Set.of());
        }
        Set<StoreField> stores = new HashSet<>();
        Var base = ((InstanceFieldAccess) load.getFieldAccess()).getBase();
        Set<Obj> pts = pta.getPointsToSet(base);
        for (StoreField store : instanceStores.getOrDefault(field, Set.of())) {
            Var storeBase = ((InstanceFieldAccess) store.getFieldAccess()).getBase();
            if (!Collections.disjoint(pts, pta.getPointsToSet(storeBase))) {
                stores.add(store);
            }
        }
        return stores;
    }

    @Override
    protected boolean transferNonCallNode(Stmt stmt, CPFact in, CPFact out) {
        // TODO - finish me
        if (stmt instanceof LoadArray loadArray
                && ConstantPropagation.canHoldInt(loadArray.getLValue())) {
            Value value1 = in.get(loadArray.getArrayAccess().getIndex());
            Value value = Value.getUndef();
            for (StoreArray storeArray : getArrayStores(loadArray)) {
                CPFact inFact = solver.getInFact(storeArray);
                Value value2 = inFact.get(storeArray.getArrayAccess().getIndex());
                if (mayAliasIndex(value1, value2)) {
                    value = cp.meetValue(value, inFact.get(storeArray.getRValue()));
                }
            }
            return transferLoad(loadArray.getLValue(), value, in, out);
        } else if (stmt instanceof LoadField loadField
                && ConstantPropagation.canHoldInt(loadField.getLValue())) {
            Value value = Value.getUndef();
            for (StoreField store : getFieldStores(loadField)) {
                CPFact inFact = solver.getInFact(store);
                value = cp.meetValue(value, inFact.get(store.getRValue()));
            }
            return transferLoad(loadField.getLValue(), value, in, out);
        }
        boolean changed = cp.transferNode(stmt, in, out);
        if (changed) {
            for (Stmt dependent : dependentLoads.getOrDefault(stmt, Set.of())) {
                solver.addWorkList(dependent);
            }
        }
        return changed;
    }

    @Override
    protected CPFact transferNormalEdge(NormalEdge<Stmt> edge, CPFact out) {
        return out.copy();
    }

    @Override
    protected CPFact transferCallToReturnEdge(CallToReturnEdge<Stmt> edge, CPFact out) {
        CPFact result = out.copy();
        if (edge.getSource().getDef().orElse(null) instanceof Var resultVar) {
            result.remove(resultVar);
        }
        return result;
    }

    @Override
    protected CPFact transferCallEdge(CallEdge<Stmt> edge, CPFact callSiteOut) {
        Invoke call = (Invoke) edge.getSource();
        List<Var> args = call.getInvokeExp().getArgs();
        List<Var> params = edge.getCallee().getIR().getParams();

        CPFact calleeIn = new CPFact();
        for (int i = 0; i < params.size(); i++) {
            calleeIn.update(params.get(i), callSiteOut.get(args.get(i)));
        }
        return calleeIn;
    }

    @Override
    protected CPFact transferReturnEdge(ReturnEdge<Stmt> edge, CPFact returnOut) {
        CPFact result = new CPFact();
        Var resultVar = (Var) edge.getCallSite().getDef().orElse(null);
        if (resultVar == null) {
            return result;
        }
        Value returnValue = Value.getUndef();
        for (Var returnVar : edge.getReturnVars()) {
            returnValue = cp.meetValue(returnValue, returnOut.get(returnVar));
        }
        result.update(resultVar, returnValue);
        return result;
    }
}
