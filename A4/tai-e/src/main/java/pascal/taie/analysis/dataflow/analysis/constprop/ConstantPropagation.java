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

package pascal.taie.analysis.dataflow.analysis.constprop;

import pascal.taie.analysis.dataflow.analysis.AbstractDataflowAnalysis;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.stmt.DefinitionStmt;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;
import pascal.taie.util.AnalysisException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static pascal.taie.ir.exp.ArithmeticExp.Op.REM;
import static pascal.taie.ir.exp.BitwiseExp.Op.*;

public class ConstantPropagation extends
        AbstractDataflowAnalysis<Stmt, CPFact> {

    public static final String ID = "constprop";

    public ConstantPropagation(AnalysisConfig config) {
        super(config);
    }

    @Override
    public boolean isForward() {
        return true;
    }

    @Override
    public CPFact newBoundaryFact(CFG<Stmt> cfg) {
        // TODO - finish me
        CPFact fact = new CPFact();
        List<Var> params = cfg.getIR().getParams();
        params.forEach(param -> {
            if (canHoldInt(param))
                fact.update(param, Value.getNAC());
        });

        return fact;
    }

    @Override
    public CPFact newInitialFact() {
        // TODO - finish me
        return new CPFact();
    }

    @Override
    public void meetInto(CPFact fact, CPFact target) {
        // TODO - finish me
        for (Var key : fact.keySet()) {
            target.update(key, meetValue(fact.get(key), target.get(key)));
        }
    }

    /**
     * Meets two Values.
     */
    public Value meetValue(Value v1, Value v2) {
        // TODO - finish me
        if (v1.isNAC() || v2.isNAC()) {
            return Value.getNAC();
        }
        if (v1.isUndef()) {
            return v2;
        }
        if (v2.isUndef()) {
            return v1;
        }
        return v1.equals(v2) ? v1 : Value.getNAC();
    }

    @Override
    public boolean transferNode(Stmt stmt, CPFact in, CPFact out) {
        CPFact newOut = in.copy();

        if (stmt instanceof DefinitionStmt<?, ?> definition
                && definition.getLValue() instanceof Var lhs
                && canHoldInt(lhs)) {
            newOut.update(lhs, evaluate(definition.getRValue(), in));
        }

        if (out.equals(newOut)) {
            return false;
        }
        out.clear();
        out.copyFrom(newOut);
        return true;
    }

    /**
     * @return true if the given variable can hold integer value, otherwise false.
     */
    public static boolean canHoldInt(Var var) {
        Type type = var.getType();
        if (type instanceof PrimitiveType) {
            switch ((PrimitiveType) type) {
                case BYTE:
                case SHORT:
                case INT:
                case CHAR:
                case BOOLEAN:
                    return true;
            }
        }
        return false;
    }

    /**
     * Evaluates the {@link Value} of given expression.
     *
     * @param exp the expression to be evaluated
     * @param in  IN fact of the statement
     * @return the resulting {@link Value}
     */
    public static Value evaluate(Exp exp, CPFact in) {

        if (exp instanceof Var var) {
            return in.get(var);
        }
        if (exp instanceof IntLiteral literal) {
            return Value.makeConstant(literal.getValue());
        }
        if (!(exp instanceof BinaryExp binary)) {
            return Value.getNAC();
        }

        if (!(binary instanceof ArithmeticExp
                || binary instanceof BitwiseExp
                || binary instanceof ConditionExp
                || binary instanceof ShiftExp)) {
            return Value.getNAC();
        }

        Var operand1 = binary.getOperand1();
        Var operand2 = binary.getOperand2();
        Value value1 = in.get(operand1);
        Value value2 = in.get(operand2);
        if (!canHoldInt(operand1) || !canHoldInt(operand2)) {
            return Value.getNAC();
        }
        if (binary instanceof ArithmeticExp arithmetic
                && (arithmetic.getOperator() == ArithmeticExp.Op.DIV
                || arithmetic.getOperator() == ArithmeticExp.Op.REM)
                && value2.isConstant()
                && value2.getConstant() == 0) {
            return Value.getUndef();
        }

        if (value1.isNAC() || value2.isNAC()) {
            return Value.getNAC();
        }
        if (!value1.isConstant() || !value2.isConstant()) {
            return Value.getUndef();
        }

        int c1 = value1.getConstant();
        int c2 = value2.getConstant();

        if (binary instanceof ArithmeticExp arithmetic) {
            return switch (arithmetic.getOperator()) {
                case ADD -> Value.makeConstant(c1 + c2);
                case SUB -> Value.makeConstant(c1 - c2);
                case MUL -> Value.makeConstant(c1 * c2);
                case DIV -> c2 == 0
                        ? Value.getUndef()
                        : Value.makeConstant(c1 / c2);
                case REM -> c2 == 0
                        ? Value.getUndef()
                        : Value.makeConstant(c1 % c2);
            };
        }

        if (binary instanceof BitwiseExp bitwise) {
            return Value.makeConstant(switch (bitwise.getOperator()) {
                case OR -> c1 | c2;
                case AND -> c1 & c2;
                case XOR -> c1 ^ c2;
            });
        }

        if (binary instanceof ConditionExp condition) {
            boolean result = switch (condition.getOperator()) {
                case EQ -> c1 == c2;
                case NE -> c1 != c2;
                case LT -> c1 < c2;
                case LE -> c1 <= c2;
                case GT -> c1 > c2;
                case GE -> c1 >= c2;
            };
            return Value.makeConstant(result ? 1 : 0);
        }

        ShiftExp shift = (ShiftExp) binary;
        return Value.makeConstant(switch (shift.getOperator()) {
            case SHL -> c1 << c2;
            case SHR -> c1 >> c2;
            case USHR -> c1 >>> c2;
        });

    }
}
