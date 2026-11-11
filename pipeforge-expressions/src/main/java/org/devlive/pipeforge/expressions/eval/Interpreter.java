/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.eval;

import java.time.ZoneId;
import java.util.List;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.expressions.ast.Node;
import org.devlive.pipeforge.expressions.function.FunctionCall;
import org.devlive.pipeforge.expressions.runtime.R;

/**
 * Evaluates expression trees directly. It is the reference semantics that the compiled code is
 * tested against, and the fallback where compilation is not worth it (constant folding, previews).
 */
public final class Interpreter implements RowFunctions {

    private final List<Node> roots;
    private final ZoneId zone;

    public Interpreter(List<Node> roots, ZoneId zone) {
        this.roots = List.copyOf(roots);
        this.zone = zone;
    }

    @Override
    public void reset() {}

    @Override
    public Object eval(int index, Row in) {
        return eval(roots.get(index), in);
    }

    @Override
    public int size() {
        return roots.size();
    }

    @SuppressWarnings("ReferenceEquality")
    private Object eval(Node node, Row in) {
        List<Node> args = node.args();
        DataType type = node.type();
        return switch (node.kind()) {
            case LITERAL -> node.payload();
            case INPUT -> in.get((Integer) node.payload());
            case FIELD -> R.field(eval(args.get(0), in), (Integer) node.payload());
            case ITEM -> R.item(eval(args.get(0), in), eval(args.get(1), in));
            case META -> R.meta(in, (String) node.payload());
            case CAST -> Boolean.TRUE.equals(node.payload())
                    ? R.tryCast(eval(args.get(0), in), args.get(0).type(), type, zone)
                    : R.cast(eval(args.get(0), in), args.get(0).type(), type, zone);
            case PLUS -> R.plus(eval(args.get(0), in), eval(args.get(1), in), type);
            case MINUS -> R.minus(eval(args.get(0), in), eval(args.get(1), in), type);
            case TIMES -> R.times(eval(args.get(0), in), eval(args.get(1), in), type);
            case DIVIDE -> R.divide(eval(args.get(0), in), eval(args.get(1), in), type);
            case MOD -> R.mod(eval(args.get(0), in), eval(args.get(1), in), type);
            case NEGATE -> R.negate(eval(args.get(0), in), type);
            case CONCAT -> R.concat(eval(args.get(0), in), eval(args.get(1), in));
            case EQ -> R.eq(eval(args.get(0), in), eval(args.get(1), in));
            case NE -> R.ne(eval(args.get(0), in), eval(args.get(1), in));
            case LT -> R.lt(eval(args.get(0), in), eval(args.get(1), in));
            case LE -> R.le(eval(args.get(0), in), eval(args.get(1), in));
            case GT -> R.gt(eval(args.get(0), in), eval(args.get(1), in));
            case GE -> R.ge(eval(args.get(0), in), eval(args.get(1), in));
            case IS_DISTINCT -> R.distinct(eval(args.get(0), in), eval(args.get(1), in));
            case IS_NOT_DISTINCT -> !R.distinct(eval(args.get(0), in), eval(args.get(1), in));
            case AND -> {
                Object a = eval(args.get(0), in);
                if (R.isFalse(a)) {
                    yield Boolean.FALSE;
                }
                Object b = eval(args.get(1), in);
                if (R.isFalse(b)) {
                    yield Boolean.FALSE;
                }
                yield (a == null || b == null) ? null : Boolean.TRUE;
            }
            case OR -> {
                Object a = eval(args.get(0), in);
                if (R.isTrue(a)) {
                    yield Boolean.TRUE;
                }
                Object b = eval(args.get(1), in);
                if (R.isTrue(b)) {
                    yield Boolean.TRUE;
                }
                yield (a == null || b == null) ? null : Boolean.FALSE;
            }
            case NOT -> R.not(eval(args.get(0), in));
            case IS_NULL -> eval(args.get(0), in) == null;
            case IS_NOT_NULL -> eval(args.get(0), in) != null;
            case IS_TRUE -> R.isTrue(eval(args.get(0), in));
            case IS_FALSE -> R.isFalse(eval(args.get(0), in));
            case IS_NOT_TRUE -> !R.isTrue(eval(args.get(0), in));
            case IS_NOT_FALSE -> !R.isFalse(eval(args.get(0), in));
            case IN -> {
                Object value = eval(args.get(0), in);
                Object[] items = new Object[args.size() - 1];
                for (int i = 1; i < args.size(); i++) {
                    items[i - 1] = eval(args.get(i), in);
                }
                yield R.in(value, items);
            }
            case LIKE -> R.like(
                    eval(args.get(0), in), eval(args.get(1), in), args.size() > 2 ? eval(args.get(2), in) : null);
            case RLIKE -> R.rlike(eval(args.get(0), in), eval(args.get(1), in));
            case CASE -> {
                int i = 0;
                while (i + 1 < args.size()) {
                    if (R.isTrue(eval(args.get(i), in))) {
                        yield eval(args.get(i + 1), in);
                    }
                    i += 2;
                }
                yield eval(args.get(args.size() - 1), in);
            }
            case COALESCE -> {
                for (Node arg : args) {
                    Object value = eval(arg, in);
                    if (value != null) {
                        yield value;
                    }
                }
                yield null;
            }
            case ARRAY, MAP -> {
                Object[] values = new Object[args.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = eval(args.get(i), in);
                }
                yield node.kind() == org.devlive.pipeforge.expressions.ast.Kind.ARRAY ? R.array(values) : R.map(values);
            }
            case FUNCTION -> {
                Object[] values = new Object[args.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = eval(args.get(i), in);
                }
                yield Invocations.invoke((FunctionCall) node.payload(), values);
            }
        };
    }
}
