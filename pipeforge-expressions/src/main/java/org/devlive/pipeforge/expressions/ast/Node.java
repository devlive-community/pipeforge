/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.ast;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.expressions.function.FunctionCall;
import org.jspecify.annotations.Nullable;

/**
 * A node of a typed expression tree.
 *
 * @param kind what the node does
 * @param type the result type
 * @param args operands, in evaluation order
 * @param payload kind-specific data (literal value, column position, function, ...)
 * @param deterministic whether the same input always gives the same result
 * @param line 1-based line of the node in the expression text
 * @param column 1-based column of the node in the expression text
 */
public record Node(
        Kind kind,
        DataType type,
        List<Node> args,
        @Nullable Object payload,
        boolean deterministic,
        int line,
        int column) {

    public Node {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        args = List.copyOf(args);
    }

    /** Creates a node; it is deterministic when all operands are and the kind or function is. */
    public static Node of(Kind kind, DataType type, List<Node> args, @Nullable Object payload, int line, int column) {
        boolean deterministic = args.stream().allMatch(Node::deterministic)
                && !(payload instanceof FunctionCall f && !f.definition().deterministic());
        return new Node(kind, type, args, payload, deterministic, line, column);
    }

    public static Node literal(@Nullable Object value, DataType type, int line, int column) {
        return new Node(Kind.LITERAL, type, List.of(), value, true, line, column);
    }

    public Node arg(int index) {
        return args.get(index);
    }

    public boolean isLiteral() {
        return kind == Kind.LITERAL;
    }

    /**
     * A canonical text that is equal for structurally equal subtrees, used to find common
     * subexpressions. Positions are ignored.
     */
    public String key() {
        String p =
                switch (kind) {
                    case LITERAL -> payload == null
                            ? "null"
                            : payload.getClass().getSimpleName() + ":" + literalText(payload);
                    case FUNCTION -> String.valueOf(payload);
                    default -> payload == null ? "" : String.valueOf(payload);
                };
        String children = args.stream().map(Node::key).collect(Collectors.joining(","));
        return kind + "<" + type + ">[" + p + "](" + children + ")";
    }

    private static String literalText(Object value) {
        return value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value);
    }

    @Override
    public String toString() {
        return key();
    }
}
