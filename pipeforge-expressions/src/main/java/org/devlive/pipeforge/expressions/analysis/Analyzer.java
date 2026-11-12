/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.calcite.sql.SqlBasicTypeNameSpec;
import org.apache.calcite.sql.SqlBinaryStringLiteral;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCharStringLiteral;
import org.apache.calcite.sql.SqlCollectionTypeNameSpec;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlIntervalLiteral;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlNumericLiteral;
import org.apache.calcite.sql.SqlTypeNameSpec;
import org.apache.calcite.sql.SqlUnknownLiteral;
import org.apache.calcite.sql.fun.SqlBetweenOperator;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.fun.SqlLikeOperator;
import org.apache.calcite.sql.type.SqlTypeName;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.expressions.InputSchema;
import org.devlive.pipeforge.expressions.ast.Kind;
import org.devlive.pipeforge.expressions.ast.Node;
import org.devlive.pipeforge.expressions.function.FunctionCall;
import org.devlive.pipeforge.expressions.function.FunctionDefinition;
import org.devlive.pipeforge.expressions.function.FunctionRegistry;
import org.devlive.pipeforge.expressions.parse.ExpressionParser;
import org.devlive.pipeforge.expressions.runtime.ExprError;
import org.devlive.pipeforge.expressions.runtime.R;
import org.jspecify.annotations.Nullable;

/**
 * Turns a parsed expression into a typed {@link Node} tree: resolves columns, nested fields and
 * pseudo-columns, checks operand types, inserts implicit casts, and resolves functions.
 * Every problem is reported as {@code PF-PLAN-0007} with the line and column it occurs at.
 */
public final class Analyzer {

    /** Metadata pseudo-columns that can be derived from the row. */
    public static final Set<String> PSEUDO_COLUMNS = Set.of("$table", "$db", "$schema", "$rowkind");

    private final String text;
    private final ExpressionParser.Parsed parsed;
    private final InputSchema schema;
    private final FunctionRegistry functions;

    private Analyzer(String text, ExpressionParser.Parsed parsed, InputSchema schema, FunctionRegistry functions) {
        this.text = text;
        this.parsed = parsed;
        this.schema = schema;
        this.functions = functions;
    }

    /** Parses and analyzes an expression. */
    public static Node analyze(String text, InputSchema schema, FunctionRegistry functions) {
        ExpressionParser.Parsed parsed = ExpressionParser.parse(text);
        return new Analyzer(text, parsed, schema, functions).node(parsed.node());
    }

    private PipeForgeException error(SqlNode at, String reason) {
        int line = at.getParserPosition().getLineNum();
        int column = parsed.originalColumn(line, at.getParserPosition().getColumnNum());
        return ExpressionParser.invalid(text, Math.max(line, 1), Math.max(column, 1), reason);
    }

    private static int line(SqlNode node) {
        return node.getParserPosition().getLineNum();
    }

    private int column(SqlNode node) {
        return parsed.originalColumn(line(node), node.getParserPosition().getColumnNum());
    }

    private Node make(Kind kind, DataType type, List<Node> args, @Nullable Object payload, SqlNode at) {
        return Node.of(kind, type, args, payload, line(at), column(at));
    }

    // ------------------------------------------------------------------ dispatch

    private Node node(SqlNode sql) {
        if (sql instanceof SqlIdentifier identifier) {
            return identifier(identifier);
        }
        if (sql instanceof SqlLiteral literal) {
            return literal(literal);
        }
        if (sql instanceof SqlCase sqlCase) {
            return caseWhen(sqlCase);
        }
        if (sql instanceof SqlCall call) {
            return call(call);
        }
        throw error(sql, "unsupported expression '" + sql + "'");
    }

    private Node call(SqlCall call) {
        List<SqlNode> ops = call.getOperandList();
        return switch (call.getKind()) {
            case PLUS -> arithmetic(Kind.PLUS, "+", call);
            case MINUS -> arithmetic(Kind.MINUS, "-", call);
            case TIMES -> arithmetic(Kind.TIMES, "*", call);
            case DIVIDE -> arithmetic(Kind.DIVIDE, "/", call);
            case MOD -> arithmetic(Kind.MOD, "%", call);
            case MINUS_PREFIX -> negate(call);
            case PLUS_PREFIX -> {
                Node operand = node(ops.get(0));
                requireNumeric(operand, ops.get(0), "unary +");
                yield operand;
            }
            case EQUALS -> comparison(Kind.EQ, call, true);
            case NOT_EQUALS -> comparison(Kind.NE, call, true);
            case LESS_THAN -> comparison(Kind.LT, call, false);
            case LESS_THAN_OR_EQUAL -> comparison(Kind.LE, call, false);
            case GREATER_THAN -> comparison(Kind.GT, call, false);
            case GREATER_THAN_OR_EQUAL -> comparison(Kind.GE, call, false);
            case IS_DISTINCT_FROM -> comparison(Kind.IS_DISTINCT, call, true);
            case IS_NOT_DISTINCT_FROM -> comparison(Kind.IS_NOT_DISTINCT, call, true);
            case AND -> logical(Kind.AND, call);
            case OR -> logical(Kind.OR, call);
            case NOT -> make(
                    Kind.NOT, DataTypes.BOOLEAN().withNullability(true), List.of(bool(ops.get(0))), null, call);
            case IS_NULL -> predicate(Kind.IS_NULL, node(ops.get(0)), call);
            case IS_NOT_NULL -> predicate(Kind.IS_NOT_NULL, node(ops.get(0)), call);
            case IS_TRUE -> predicate(Kind.IS_TRUE, bool(ops.get(0)), call);
            case IS_FALSE -> predicate(Kind.IS_FALSE, bool(ops.get(0)), call);
            case IS_NOT_TRUE -> predicate(Kind.IS_NOT_TRUE, bool(ops.get(0)), call);
            case IS_NOT_FALSE -> predicate(Kind.IS_NOT_FALSE, bool(ops.get(0)), call);
            case BETWEEN -> between(call);
            case IN, NOT_IN -> in(call, call.getKind() == org.apache.calcite.sql.SqlKind.NOT_IN);
            case LIKE -> like(call, Kind.LIKE);
            case RLIKE -> like(call, Kind.RLIKE);
            case CAST -> cast(call, false);
            case SAFE_CAST -> cast(call, true);
            case ITEM -> item(call);
            case ARRAY_VALUE_CONSTRUCTOR -> array(call);
            case MAP_VALUE_CONSTRUCTOR -> map(call);
            case TRIM -> trim(call);
            case POSITION -> {
                if (call.operandCount() > 2) {
                    throw error(call, "POSITION ... FROM is not supported");
                }
                yield registered("POSITION", List.of(node(call.operand(0)), node(call.operand(1))), call);
            }
            case EXTRACT -> extract(call);
            case TIMESTAMP_DIFF -> {
                Node unit = Node.literal(
                        call.operand(0).toString(), DataTypes.STRING().notNull(), line(call), column(call));
                yield registered("TIMESTAMPDIFF", List.of(unit, node(call.operand(1)), node(call.operand(2))), call);
            }
            default -> {
                String name = call.getOperator().getName();
                if (name.equals("||")) {
                    yield concat(call);
                }
                if (call.getOperator() instanceof SqlFunction) {
                    yield function(call);
                }
                throw error(call, "unsupported operator " + name);
            }
        };
    }

    // ------------------------------------------------------------------ names

    private Node identifier(SqlIdentifier id) {
        String first = id.names.get(0);
        if (first.startsWith("$") && id.names.size() == 1) {
            String name = first.toLowerCase(Locale.ROOT);
            if (!PSEUDO_COLUMNS.contains(name)) {
                throw error(
                        id,
                        "unknown pseudo-column " + first + "; available: "
                                + String.join(
                                        ", ", PSEUDO_COLUMNS.stream().sorted().toList()));
            }
            DataType type = name.equals("$rowkind") ? DataTypes.STRING().notNull() : DataTypes.STRING();
            return make(Kind.META, type, List.of(), name, id);
        }
        InputSchema.Lookup lookup = schema.lookup(first, id.isComponentQuoted(0));
        if (!lookup.found()) {
            Optional<FunctionDefinition> niladic = functions.find(String.join(".", id.names));
            if (niladic.isPresent()) {
                return resolveFunction(niladic.get(), List.of(), id);
            }
            throw error(id, lookup.problem());
        }
        Node current = make(Kind.INPUT, schema.type(lookup.index()), List.of(), lookup.index(), id);
        for (int i = 1; i < id.names.size(); i++) {
            current = field(current, id.names.get(i), id.isComponentQuoted(i), id);
        }
        return current;
    }

    private Node field(Node row, String name, boolean quoted, SqlNode at) {
        if (row.type().root() != TypeRoot.ROW) {
            throw error(at, "'" + name + "' is a field access, but the value is " + row.type() + ", not a ROW");
        }
        List<DataType.Field> fields = row.type().fields();
        int match = -1;
        for (int i = 0; i < fields.size(); i++) {
            String candidate = fields.get(i).name();
            if (quoted ? candidate.equals(name) : candidate.equalsIgnoreCase(name)) {
                if (match >= 0) {
                    throw error(at, "field '" + name + "' is ambiguous");
                }
                match = i;
            }
        }
        if (match < 0) {
            throw error(
                    at,
                    "the ROW has no field '" + name + "'; fields: "
                            + fields.stream().map(DataType.Field::name).collect(Collectors.joining(", ")));
        }
        DataType type = fields.get(match).type().withNullability(true);
        return make(Kind.FIELD, type, List.of(row), match, at);
    }

    private Node item(SqlCall call) {
        Node container = node(call.operand(0));
        Node key = node(call.operand(1));
        DataType type = container.type();
        if (type.root() == TypeRoot.ARRAY) {
            if (!key.type().root().isIntegral() && key.type().root() != TypeRoot.NULL) {
                throw error(call.operand(1), "an array index must be an integer, got " + key.type());
            }
            return make(
                    Kind.ITEM,
                    type.elementType().nullable(),
                    List.of(container, castTo(key, DataTypes.INT64(), call.operand(1))),
                    null,
                    call);
        }
        if (type.root() == TypeRoot.MAP) {
            Node castKey = coerce(key, type.keyType(), call.operand(1), "map key");
            return make(Kind.ITEM, type.valueType().nullable(), List.of(container, castKey), null, call);
        }
        throw error(call, "[] needs an ARRAY or a MAP, got " + type);
    }

    // ------------------------------------------------------------------ literals

    private Node literal(SqlLiteral literal) {
        int line = line(literal);
        int column = column(literal);
        if (literal instanceof SqlNumericLiteral number) {
            BigDecimal value = number.getValueAs(BigDecimal.class);
            if (!number.isExact()) {
                return Node.literal(value.doubleValue(), DataTypes.FLOAT64().notNull(), line, column);
            }
            if (value.scale() <= 0) {
                BigDecimal integral = value.setScale(0);
                if (integral.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0
                        && integral.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) >= 0) {
                    return Node.literal(
                            integral.intValueExact(), DataTypes.INT32().notNull(), line, column);
                }
                if (integral.toBigInteger().bitLength() < 64) {
                    return Node.literal(
                            integral.longValueExact(), DataTypes.INT64().notNull(), line, column);
                }
            }
            if (value.precision() > DataTypes.MAX_DECIMAL_PRECISION) {
                throw error(literal, "the number " + value.toPlainString() + " has more than 38 digits");
            }
            int scale = Math.max(value.scale(), 0);
            int precision = Math.max(value.precision(), scale);
            return Node.literal(
                    value.setScale(scale),
                    DataTypes.DECIMAL(Math.max(precision, 1), scale).notNull(),
                    line,
                    column);
        }
        if (literal instanceof SqlCharStringLiteral string) {
            String value = string.getValueAs(String.class);
            DataType type =
                    value.isEmpty() ? DataTypes.STRING() : DataTypes.STRING(value.codePointCount(0, value.length()));
            return Node.literal(value, type.notNull(), line, column);
        }
        if (literal instanceof SqlBinaryStringLiteral binary) {
            byte[] bytes = binary.getBitString().getAsByteArray();
            return Node.literal(
                    bytes,
                    (bytes.length == 0 ? DataTypes.BYTES() : DataTypes.BYTES(bytes.length)).notNull(),
                    line,
                    column);
        }
        if (literal instanceof SqlIntervalLiteral) {
            throw error(literal, "INTERVAL values are not supported yet; they arrive with the date functions");
        }
        if (literal instanceof SqlUnknownLiteral unknown) {
            return typedLiteral(unknown.tag, unknown.getValue(), literal);
        }
        if (literal.getTypeName() == SqlTypeName.NULL) {
            return Node.literal(null, DataTypes.NULL(), line, column);
        }
        if (literal.getTypeName() == SqlTypeName.BOOLEAN) {
            return Node.literal(literal.booleanValue(), DataTypes.BOOLEAN().notNull(), line, column);
        }
        throw error(literal, "unsupported literal " + literal);
    }

    /** {@code DATE '...'}, {@code TIME '...'}, {@code TIMESTAMP '...'} and {@code TIMESTAMP_LTZ('...')}. */
    private Node typedLiteral(String tag, String value, SqlNode at) {
        try {
            return switch (tag.toUpperCase(Locale.ROOT)) {
                case "DATE" -> Node.literal(
                        LocalDate.parse(value.trim()), DataTypes.DATE().notNull(), line(at), column(at));
                case "TIME" -> Node.literal(
                        LocalTime.parse(value.trim()),
                        DataTypes.TIME(fractionDigits(value)).notNull(),
                        line(at),
                        column(at));
                case "TIMESTAMP" -> Node.literal(
                        R.parseTimestamp(value),
                        DataTypes.TIMESTAMP(fractionDigits(value)).notNull(),
                        line(at),
                        column(at));
                default -> throw error(at, "unsupported literal type " + tag);
            };
        } catch (java.time.DateTimeException e) {
            throw error(at, "'" + value + "' is not a valid " + tag.toUpperCase(Locale.ROOT) + " value");
        }
    }

    private static int fractionDigits(String value) {
        int dot = value.lastIndexOf('.');
        int colon = value.lastIndexOf(':');
        if (dot < 0 || dot < colon) {
            return 0;
        }
        int digits = 0;
        for (int i = dot + 1; i < value.length() && Character.isDigit(value.charAt(i)); i++) {
            digits++;
        }
        return Math.min(digits, DataTypes.MAX_TIME_PRECISION);
    }

    // ------------------------------------------------------------------ operators

    private Node arithmetic(Kind kind, String operator, SqlCall call) {
        Node a = node(call.operand(0));
        Node b = node(call.operand(1));
        requireNumeric(a, call.operand(0), operator);
        requireNumeric(b, call.operand(1), operator);
        DataType result = TypeRules.arithmetic(operator, a.type(), b.type())
                .orElseThrow(() -> error(call, "cannot apply " + operator + " to " + a.type() + " and " + b.type()));
        DataType operandA = operandType(result, a.type());
        DataType operandB = operandType(result, b.type());
        return make(
                kind,
                result,
                List.of(castTo(a, operandA, call.operand(0)), castTo(b, operandB, call.operand(1))),
                null,
                call);
    }

    /** The type an arithmetic operand is converted to before the operation. */
    private static DataType operandType(DataType result, DataType operand) {
        if (result.root() == TypeRoot.DECIMAL) {
            return operand.root() == TypeRoot.NULL ? result : TypeRules.asDecimal(operand);
        }
        return result.withNullability(operand.isNullable());
    }

    private Node negate(SqlCall call) {
        Node operand = node(call.operand(0));
        requireNumeric(operand, call.operand(0), "unary -");
        DataType type = operand.type().root() == TypeRoot.NULL ? DataTypes.INT32() : operand.type();
        if (operand.isLiteral() && operand.payload() != null) {
            Object negated = R.negate(operand.payload(), type);
            return Node.literal(negated, type, line(call), column(call));
        }
        return make(Kind.NEGATE, type, List.of(castTo(operand, type, call.operand(0))), null, call);
    }

    private Node concat(SqlCall call) {
        Node a = node(call.operand(0));
        Node b = node(call.operand(1));
        for (int i = 0; i < 2; i++) {
            Node operand = i == 0 ? a : b;
            TypeRoot root = operand.type().root();
            if (root != TypeRoot.STRING && root != TypeRoot.NULL) {
                throw error(
                        call.operand(i),
                        "|| concatenates strings; " + operand.type() + " needs an explicit CAST(... AS STRING)");
            }
        }
        Integer x =
                a.type().root() == TypeRoot.NULL ? Integer.valueOf(0) : a.type().length();
        Integer y =
                b.type().root() == TypeRoot.NULL ? Integer.valueOf(0) : b.type().length();
        DataType type = x == null || y == null || x + y == 0 ? DataTypes.STRING() : DataTypes.STRING(x + y);
        type = type.withNullability(a.type().isNullable() || b.type().isNullable());
        return make(
                Kind.CONCAT,
                type,
                List.of(castTo(a, DataTypes.STRING(), call.operand(0)), castTo(b, DataTypes.STRING(), call.operand(1))),
                null,
                call);
    }

    private Node comparison(Kind kind, SqlCall call, boolean equality) {
        Node a = node(call.operand(0));
        Node b = node(call.operand(1));
        DataType common = TypeRules.common(a.type(), b.type())
                .orElseThrow(() -> error(
                        call,
                        "cannot compare " + a.type().nullable() + " with "
                                + b.type().nullable()
                                + "; strings, numbers and dates are not converted implicitly, use CAST"));
        TypeRoot root = common.root();
        if (!equality
                && (root == TypeRoot.MAP || root == TypeRoot.ROW || root == TypeRoot.ARRAY || root == TypeRoot.JSON)) {
            throw error(call, common.nullable() + " values can only be compared for equality");
        }
        boolean nullable = (kind != Kind.IS_DISTINCT && kind != Kind.IS_NOT_DISTINCT)
                && (a.type().isNullable() || b.type().isNullable());
        return make(
                kind,
                DataTypes.BOOLEAN().withNullability(nullable),
                List.of(castTo(a, common, call.operand(0)), castTo(b, common, call.operand(1))),
                null,
                call);
    }

    private Node logical(Kind kind, SqlCall call) {
        List<SqlNode> operands = call.getOperandList();
        Node result = bool(operands.get(0));
        for (int i = 1; i < operands.size(); i++) {
            Node next = bool(operands.get(i));
            result = make(
                    kind,
                    DataTypes.BOOLEAN()
                            .withNullability(
                                    result.type().isNullable() || next.type().isNullable()),
                    List.of(result, next),
                    null,
                    call);
        }
        return result;
    }

    private Node predicate(Kind kind, Node operand, SqlCall call) {
        return make(kind, DataTypes.BOOLEAN().notNull(), List.of(operand), null, call);
    }

    /** An operand that must be BOOLEAN (or NULL). */
    private Node bool(SqlNode sql) {
        Node node = node(sql);
        TypeRoot root = node.type().root();
        if (root == TypeRoot.NULL) {
            return castTo(node, DataTypes.BOOLEAN(), sql);
        }
        if (root != TypeRoot.BOOLEAN) {
            throw error(sql, "expected a BOOLEAN condition, got " + node.type().nullable());
        }
        return node;
    }

    private Node between(SqlCall call) {
        SqlBetweenOperator operator = (SqlBetweenOperator) call.getOperator();
        if (operator.flag == SqlBetweenOperator.Flag.SYMMETRIC) {
            throw error(call, "BETWEEN SYMMETRIC is not supported");
        }
        Node value = node(call.operand(0));
        Node low = node(call.operand(1));
        Node high = node(call.operand(2));
        Node ge = compareNodes(Kind.GE, value, low, call);
        Node le = compareNodes(Kind.LE, value, high, call);
        Node both = make(
                Kind.AND,
                DataTypes.BOOLEAN()
                        .withNullability(ge.type().isNullable() || le.type().isNullable()),
                List.of(ge, le),
                null,
                call);
        return operator.isNegated() ? make(Kind.NOT, both.type(), List.of(both), null, call) : both;
    }

    private Node compareNodes(Kind kind, Node a, Node b, SqlCall at) {
        DataType common = TypeRules.common(a.type(), b.type())
                .orElseThrow(() -> error(
                        at,
                        "cannot compare " + a.type().nullable() + " with "
                                + b.type().nullable() + "; use CAST"));
        return make(
                kind,
                DataTypes.BOOLEAN()
                        .withNullability(a.type().isNullable() || b.type().isNullable()),
                List.of(castTo(a, common, at), castTo(b, common, at)),
                null,
                at);
    }

    private Node in(SqlCall call, boolean negated) {
        Node value = node(call.operand(0));
        if (!(call.operand(1) instanceof SqlNodeList list)) {
            throw error(call.operand(1), "IN needs a list of values; subqueries are not supported");
        }
        List<Node> items = new ArrayList<>();
        for (SqlNode item : list) {
            items.add(node(item));
        }
        List<DataType> types = new ArrayList<>();
        types.add(value.type());
        items.forEach(n -> types.add(n.type()));
        DataType common = TypeRules.common(types)
                .orElseThrow(() -> error(
                        call,
                        "the values of IN have no common type: "
                                + types.stream()
                                        .map(t -> t.nullable().toString())
                                        .distinct()
                                        .collect(Collectors.joining(", "))));
        List<Node> args = new ArrayList<>();
        args.add(castTo(value, common, call.operand(0)));
        for (int i = 0; i < items.size(); i++) {
            args.add(castTo(items.get(i), common, list.get(i)));
        }
        boolean nullable = types.stream().anyMatch(DataType::isNullable);
        Node in = make(Kind.IN, DataTypes.BOOLEAN().withNullability(nullable), args, null, call);
        return negated ? make(Kind.NOT, in.type(), List.of(in), null, call) : in;
    }

    private Node like(SqlCall call, Kind kind) {
        SqlLikeOperator operator = (SqlLikeOperator) call.getOperator();
        List<Node> args = new ArrayList<>();
        for (int i = 0; i < call.operandCount(); i++) {
            Node operand = node(call.operand(i));
            TypeRoot root = operand.type().root();
            if (root != TypeRoot.STRING && root != TypeRoot.NULL) {
                throw error(
                        call.operand(i),
                        (kind == Kind.LIKE ? "LIKE" : "RLIKE") + " works on strings, got "
                                + operand.type().nullable());
            }
            args.add(castTo(operand, DataTypes.STRING(), call.operand(i)));
        }
        if (kind == Kind.RLIKE && args.get(1).isLiteral() && args.get(1).payload() != null) {
            try {
                R.regex((String) args.get(1).payload());
            } catch (ExprError e) {
                throw error(call.operand(1), e.getMessage());
            }
        }
        boolean nullable = args.stream().anyMatch(n -> n.type().isNullable());
        Node match = make(kind, DataTypes.BOOLEAN().withNullability(nullable), args, null, call);
        return operator.isNegated() ? make(Kind.NOT, match.type(), List.of(match), null, call) : match;
    }

    private Node caseWhen(SqlCase sqlCase) {
        if (sqlCase.getValueOperand() != null) {
            throw error(sqlCase, "simple CASE is expected to be rewritten by the parser");
        }
        List<Node> conditions = new ArrayList<>();
        List<Node> results = new ArrayList<>();
        List<SqlNode> resultSql = new ArrayList<>();
        for (int i = 0; i < sqlCase.getWhenOperands().size(); i++) {
            conditions.add(bool(sqlCase.getWhenOperands().get(i)));
            results.add(node(sqlCase.getThenOperands().get(i)));
            resultSql.add(sqlCase.getThenOperands().get(i));
        }
        SqlNode elseSql = sqlCase.getElseOperand();
        Node otherwise =
                elseSql == null ? Node.literal(null, DataTypes.NULL(), line(sqlCase), column(sqlCase)) : node(elseSql);
        return buildCase(conditions, results, resultSql, otherwise, elseSql == null ? sqlCase : elseSql, sqlCase);
    }

    private Node buildCase(
            List<Node> conditions,
            List<Node> results,
            List<SqlNode> resultSql,
            Node otherwise,
            SqlNode otherwiseSql,
            SqlNode at) {
        List<DataType> types = new ArrayList<>();
        results.forEach(r -> types.add(r.type()));
        types.add(otherwise.type());
        DataType common = TypeRules.common(types)
                .orElseThrow(() -> error(
                        at,
                        "the results have no common type: "
                                + types.stream()
                                        .map(t -> t.nullable().toString())
                                        .distinct()
                                        .collect(Collectors.joining(", "))));
        if (common.root() == TypeRoot.NULL) {
            common = DataTypes.STRING();
        }
        List<Node> args = new ArrayList<>();
        for (int i = 0; i < conditions.size(); i++) {
            args.add(conditions.get(i));
            args.add(castTo(results.get(i), common, resultSql.get(i)));
        }
        args.add(castTo(otherwise, common, otherwiseSql));
        return make(Kind.CASE, common, args, null, at);
    }

    private Node cast(SqlCall call, boolean safe) {
        Node operand = node(call.operand(0));
        DataType target = typeSpec((SqlDataTypeSpec) call.operand(1));
        if (!TypeRules.castable(operand.type(), target)) {
            throw error(call, "cannot cast " + operand.type().nullable() + " to " + target);
        }
        DataType type = target.withNullability(safe || operand.type().isNullable());
        return make(Kind.CAST, type, List.of(operand), safe, call);
    }

    private DataType typeSpec(SqlDataTypeSpec spec) {
        List<String> names = spec.getTypeNameSpec().getTypeName().names;
        Optional<DataType> logical = names.size() == 1 ? parsed.castType(names.get(0)) : Optional.empty();
        if (logical.isPresent()) {
            return logical.get();
        }
        String typeText = typeText(spec.getTypeNameSpec());
        try {
            return DataTypes.parse(typeText);
        } catch (IllegalArgumentException e) {
            throw error(spec, "unknown type " + typeText);
        }
    }

    /** Calcite's type name in the syntax of {@link DataTypes#parse}. */
    private static String typeText(SqlTypeNameSpec spec) {
        if (spec instanceof SqlCollectionTypeNameSpec collection) {
            return "ARRAY<" + typeText(collection.getElementTypeName()) + ">";
        }
        List<String> names = spec.getTypeName().names;
        String name = names.get(names.size() - 1).toUpperCase(Locale.ROOT);
        String precision = "";
        if (spec instanceof SqlBasicTypeNameSpec basic && basic.getPrecision() >= 0) {
            precision = "(" + basic.getPrecision() + (basic.getScale() >= 0 ? ", " + basic.getScale() : "") + ")";
        }
        return switch (name) {
            case "INTEGER" -> "INT32";
            case "TIMESTAMP_WITH_LOCAL_TIME_ZONE" -> "TIMESTAMP_LTZ" + precision;
            default -> name + precision;
        };
    }

    private Node array(SqlCall call) {
        List<Node> items = new ArrayList<>();
        for (SqlNode operand : call.getOperandList()) {
            items.add(node(operand));
        }
        DataType element = TypeRules.common(items.stream().map(Node::type).toList())
                .orElseThrow(() -> error(call, "the ARRAY elements have no common type"));
        if (element.root() == TypeRoot.NULL) {
            element = DataTypes.STRING();
        }
        List<Node> args = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            args.add(castTo(items.get(i), element, call.operand(i)));
        }
        return make(Kind.ARRAY, DataTypes.ARRAY(element).notNull(), args, null, call);
    }

    private Node map(SqlCall call) {
        List<SqlNode> operands = call.getOperandList();
        if (operands.size() % 2 != 0) {
            throw error(call, "MAP needs key and value pairs");
        }
        List<Node> keys = new ArrayList<>();
        List<Node> values = new ArrayList<>();
        for (int i = 0; i < operands.size(); i += 2) {
            keys.add(node(operands.get(i)));
            values.add(node(operands.get(i + 1)));
        }
        DataType key = TypeRules.common(keys.stream().map(Node::type).toList())
                .orElseThrow(() -> error(call, "the MAP keys have no common type"));
        DataType value = TypeRules.common(values.stream().map(Node::type).toList())
                .orElseThrow(() -> error(call, "the MAP values have no common type"));
        if (value.root() == TypeRoot.NULL) {
            value = DataTypes.STRING();
        }
        List<Node> args = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            args.add(castTo(keys.get(i), key.notNull(), operands.get(2 * i)));
            args.add(castTo(values.get(i), value, operands.get(2 * i + 1)));
        }
        return make(Kind.MAP, DataTypes.MAP(key.notNull(), value).notNull(), args, null, call);
    }

    // ------------------------------------------------------------------ functions

    private Node function(SqlCall call) {
        String rawName = call.getOperator().getNameAsId().names.stream()
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.joining("."));
        String name = rawName.equals("TRANSLATE3") ? "TRANSLATE" : rawName;
        List<SqlNode> operands = call.getOperandList();
        switch (name) {
            case "MOD" -> {
                if (operands.size() != 2) {
                    throw error(call, "MOD needs 2 arguments");
                }
                return arithmetic(Kind.MOD, "%", call);
            }
            case "GREATEST", "LEAST" -> {
                return extremum(name, call);
            }
            case "TYPEOF" -> {
                if (operands.size() != 1) {
                    throw error(call, "TYPEOF needs 1 argument");
                }
                Node operand = node(operands.get(0));
                return Node.literal(
                        operand.type().nullable().toString(), DataTypes.STRING().notNull(), line(call), column(call));
            }
            case "FORMAT" -> {
                return format(call);
            }
            case "IF" -> {
                if (operands.size() != 3) {
                    throw error(call, "IF needs 3 arguments: condition, value if true, value if false");
                }
                return buildCase(
                        List.of(bool(operands.get(0))),
                        List.of(node(operands.get(1))),
                        List.of(operands.get(1)),
                        node(operands.get(2)),
                        operands.get(2),
                        call);
            }
            case "COALESCE", "IFNULL" -> {
                if (operands.isEmpty() || (name.equals("IFNULL") && operands.size() != 2)) {
                    throw error(
                            call,
                            name + (name.equals("IFNULL") ? " needs 2 arguments" : " needs at least one argument"));
                }
                List<Node> args = new ArrayList<>();
                operands.forEach(o -> args.add(node(o)));
                DataType common = TypeRules.common(args.stream().map(Node::type).toList())
                        .orElseThrow(() -> error(call, "the arguments of " + name + " have no common type"));
                if (common.root() == TypeRoot.NULL) {
                    common = DataTypes.STRING();
                }
                boolean nullable = args.stream().allMatch(a -> a.type().isNullable());
                List<Node> cast = new ArrayList<>();
                for (int i = 0; i < args.size(); i++) {
                    cast.add(castTo(args.get(i), common, operands.get(i)));
                }
                return make(Kind.COALESCE, common.withNullability(nullable), cast, null, call);
            }
            case "NULLIF" -> {
                if (operands.size() != 2) {
                    throw error(call, "NULLIF needs 2 arguments");
                }
                Node a = node(operands.get(0));
                Node equal = compareNodes(Kind.EQ, a, node(operands.get(1)), call);
                Node isEqual = make(Kind.IS_TRUE, DataTypes.BOOLEAN().notNull(), List.of(equal), null, call);
                Node nil = Node.literal(null, DataTypes.NULL(), line(call), column(call));
                return buildCase(List.of(isEqual), List.of(nil), List.of(call), a, operands.get(0), call);
            }
            case ExpressionParser.LTZ_LITERAL -> {
                if (operands.size() == 1 && operands.get(0) instanceof SqlCharStringLiteral literal) {
                    String value = literal.getValueAs(String.class);
                    if (!value.trim().matches(".*(Z|[+-]\\d{2}:?\\d{2})$")) {
                        throw error(call, "a TIMESTAMP_LTZ literal needs an offset such as +08:00 or Z");
                    }
                    try {
                        return Node.literal(
                                R.parseInstant(value, ZoneId.of("UTC")),
                                DataTypes.TIMESTAMP_LTZ(fractionDigits(value)).notNull(),
                                line(call),
                                column(call));
                    } catch (java.time.DateTimeException e) {
                        throw error(call, "'" + value + "' is not a valid TIMESTAMP_LTZ value");
                    }
                }
            }
            default -> {
                // registered functions below
            }
        }
        List<Node> args = new ArrayList<>();
        FunctionDefinition definition = functions.find(name).orElseThrow(() -> error(call, "unknown function " + name));
        operands.forEach(o -> args.add(node(o)));
        return resolveFunction(definition, args, call);
    }

    private Node registered(String name, List<Node> args, SqlNode at) {
        FunctionDefinition definition = functions.find(name).orElseThrow(() -> error(at, "unknown function " + name));
        return resolveFunction(definition, args, at);
    }

    /** {@code TRIM([BOTH | LEADING | TRAILING] [chars] FROM value)}. */
    private Node trim(SqlCall call) {
        String flag = call.operand(0).toString().toUpperCase(Locale.ROOT);
        String function =
                switch (flag) {
                    case "LEADING" -> "LTRIM";
                    case "TRAILING" -> "RTRIM";
                    default -> "TRIM";
                };
        return registered(function, List.of(node(call.operand(2)), node(call.operand(1))), call);
    }

    /** {@code EXTRACT(unit FROM value)}. */
    private Node extract(SqlCall call) {
        String unit = call.operand(0).toString().toUpperCase(Locale.ROOT);
        String function =
                switch (unit) {
                    case "YEAR",
                            "QUARTER",
                            "MONTH",
                            "WEEK",
                            "HOUR",
                            "MINUTE",
                            "SECOND",
                            "DAYOFWEEK",
                            "DAYOFYEAR" -> unit;
                    case "DAY" -> "DAYOFMONTH";
                    case "DOW" -> "DAYOFWEEK";
                    case "DOY" -> "DAYOFYEAR";
                    default -> throw error(
                            call,
                            "EXTRACT does not support " + unit
                                    + "; use YEAR, QUARTER, MONTH, WEEK, DAY, DOW, DOY, HOUR, MINUTE or SECOND");
                };
        return registered(function, List.of(node(call.operand(1))), call);
    }

    /** {@code GREATEST} and {@code LEAST}: arguments of any comparable type, converted to their common type. */
    private Node extremum(String name, SqlCall call) {
        List<SqlNode> operands = call.getOperandList();
        if (operands.isEmpty()) {
            throw error(call, name + " needs at least one argument");
        }
        List<Node> args = new ArrayList<>();
        operands.forEach(o -> args.add(node(o)));
        DataType common = TypeRules.common(args.stream().map(Node::type).toList())
                .orElseThrow(() -> error(call, "the arguments of " + name + " have no common type"));
        TypeRoot root = common.root();
        if (root == TypeRoot.MAP || root == TypeRoot.ROW || root == TypeRoot.ARRAY || root == TypeRoot.JSON) {
            throw error(call, name + " cannot order " + common.nullable() + " values");
        }
        if (root == TypeRoot.NULL) {
            common = DataTypes.STRING();
        }
        List<Node> cast = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            cast.add(castTo(args.get(i), common, operands.get(i)));
        }
        String method = name.equals("GREATEST") ? "greatest" : "least";
        return special(
                name,
                method,
                cast,
                common.withNullability(args.stream().anyMatch(a -> a.type().isNullable())),
                true,
                call);
    }

    /** {@code FORMAT(pattern, values...)} with values of any type. */
    private Node format(SqlCall call) {
        List<SqlNode> operands = call.getOperandList();
        if (operands.isEmpty()) {
            throw error(call, "FORMAT needs a pattern");
        }
        List<Node> args = new ArrayList<>();
        args.add(coerce(node(operands.get(0)), DataTypes.STRING(), operands.get(0), "FORMAT pattern"));
        for (int i = 1; i < operands.size(); i++) {
            args.add(node(operands.get(i)));
        }
        return special("FORMAT", "format", args, DataTypes.STRING(), false, call);
    }

    /** A call of a syntax-level function implemented by a variadic {@code Object...} method. */
    private Node special(String name, String method, List<Node> args, DataType result, boolean nullOnNull, SqlNode at) {
        java.lang.reflect.Method implementation;
        try {
            Class<?> owner = name.equals("FORMAT")
                    ? org.devlive.pipeforge.expressions.function.builtin.StringFunctions.class
                    : org.devlive.pipeforge.expressions.function.builtin.ComparisonFunctions.class;
            implementation = name.equals("FORMAT")
                    ? owner.getMethod(method, String.class, Object[].class)
                    : owner.getMethod(method, Object[].class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        List<DataType> parameters = args.stream().map(Node::type).toList();
        FunctionDefinition.Signature signature = new FunctionDefinition.Signature(
                parameters, false, FunctionDefinition.ReturnType.fixed(result), implementation);
        FunctionDefinition definition = new FunctionDefinition(name, List.of(signature), true, nullOnNull);
        return make(Kind.FUNCTION, result, args, new FunctionCall(definition, signature), at);
    }

    private Node resolveFunction(FunctionDefinition definition, List<Node> args, SqlNode at) {
        for (FunctionDefinition.Signature signature : definition.signatures()) {
            List<DataType> parameters = expand(signature, args.size());
            if (parameters == null) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < args.size() && matches; i++) {
                matches = signature.exact(i)
                        ? TypeRules.implicitlyCastable(args.get(i).type(), parameters.get(i))
                        : accepts(parameters.get(i), args.get(i).type());
            }
            if (!matches) {
                continue;
            }
            List<Node> cast = new ArrayList<>();
            List<DataType> argTypes = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                Node arg = args.get(i);
                DataType target = !signature.exact(i) && sameFamily(parameters.get(i), arg.type())
                        ? arg.type()
                        : parameters.get(i);
                Node converted = castTo(arg, target.withNullability(arg.type().isNullable()), at);
                cast.add(converted);
                argTypes.add(converted.type());
            }
            DataType result = signature.returns().infer(argTypes);
            boolean anyNullable = argTypes.stream().anyMatch(DataType::isNullable);
            if (definition.nullOnNull() && anyNullable) {
                result = result.nullable();
            }
            return make(Kind.FUNCTION, result, cast, new FunctionCall(definition, signature), at);
        }
        String given = args.stream().map(a -> a.type().nullable().toString()).collect(Collectors.joining(", "));
        String expected = definition.signatures().stream()
                .map(s -> s.parameters().stream().map(DataType::toString).collect(Collectors.joining(", "))
                        + (s.variadic() ? ", ..." : ""))
                .map(s -> definition.name() + "(" + s + ")")
                .collect(Collectors.joining(" or "));
        throw error(at, definition.name() + " does not accept (" + given + "); expected " + expected);
    }

    private static @Nullable List<DataType> expand(FunctionDefinition.Signature signature, int count) {
        List<DataType> parameters = signature.parameters();
        if (!signature.variadic()) {
            return parameters.size() == count ? parameters : null;
        }
        if (count < parameters.size() - 1) {
            return null;
        }
        List<DataType> expanded = new ArrayList<>(parameters.subList(0, parameters.size() - 1));
        while (expanded.size() < count) {
            expanded.add(parameters.get(parameters.size() - 1));
        }
        return expanded;
    }

    /** Parameters of parameterized families (DECIMAL, STRING, ...) accept any member of the family. */
    private static boolean sameFamily(DataType parameter, DataType argument) {
        return parameter.root() == argument.root()
                && switch (parameter.root()) {
                    case DECIMAL, STRING, BYTES, TIME, TIMESTAMP, TIMESTAMP_LTZ, ARRAY, MAP, ROW -> true;
                    default -> false;
                };
    }

    private static boolean accepts(DataType parameter, DataType argument) {
        return sameFamily(parameter, argument) || TypeRules.implicitlyCastable(argument, parameter);
    }

    // ------------------------------------------------------------------ casts

    private void requireNumeric(Node node, SqlNode at, String operator) {
        TypeRoot root = node.type().root();
        if (!root.isNumeric() && root != TypeRoot.NULL) {
            throw error(
                    at,
                    operator + " needs numbers, got " + node.type().nullable()
                            + (root == TypeRoot.STRING ? "; strings are not converted implicitly, use CAST" : ""));
        }
    }

    private Node coerce(Node node, DataType target, SqlNode at, String what) {
        if (!TypeRules.implicitlyCastable(node.type(), target) && !sameFamily(target, node.type())) {
            throw error(
                    at,
                    "the " + what + " must be " + target.nullable() + ", got "
                            + node.type().nullable());
        }
        return castTo(node, target, at);
    }

    /** Wraps a node in an implicit cast unless it already has the target type. */
    private Node castTo(Node node, DataType target, SqlNode at) {
        DataType wanted =
                target.withNullability(node.type().isNullable() || node.type().root() == TypeRoot.NULL);
        if (node.type().equalsIgnoreNullability(wanted)) {
            return node;
        }
        boolean zoneDependent = node.type().root() == TypeRoot.TIMESTAMP_LTZ || wanted.root() == TypeRoot.TIMESTAMP_LTZ;
        if (node.isLiteral() && !zoneDependent) {
            Object value =
                    node.payload() == null ? null : R.cast(node.payload(), node.type(), wanted, ZoneId.of("UTC"));
            return Node.literal(value, wanted, node.line(), node.column());
        }
        return Node.of(Kind.CAST, wanted, List.of(node), Boolean.FALSE, line(at), column(at));
    }
}
