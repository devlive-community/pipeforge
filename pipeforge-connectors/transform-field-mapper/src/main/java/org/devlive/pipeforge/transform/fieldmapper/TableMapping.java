/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.expression.ExpressionInfo;
import org.devlive.pipeforge.api.expression.OnError;
import org.devlive.pipeforge.api.expression.RowEvaluator;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorKind;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperOptions.Order;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperOptions.Others;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperOptions.Unmapped;
import org.jspecify.annotations.Nullable;

/**
 * How the columns of one input table become the output columns: derived while planning (the output
 * table) and again when the transform opens (to map rows).
 *
 * <p>Every output column is one of: an input column passed through, an expression over the input
 * row, or a constant. A {@code default} replaces NULL; a NOT NULL column that is still NULL makes
 * the row a bad row ({@code PF-DATA-0004}).
 */
final class TableMapping {

    /** Where the value of an output column comes from. */
    private enum Source {
        INPUT,
        EXPRESSION,
        CONSTANT
    }

    /**
     * An output column.
     *
     * @param input the input column passed through, or -1
     * @param expression the expression text, or null
     * @param constant the value of a constant column
     * @param fallback the value that replaces NULL ({@code default}), or null
     * @param entry the mapping entry, or null for a kept column
     * @param inputName the input column the value comes from unchanged (a kept or renamed column), or null
     */
    private record Output(
            Column column,
            Source source,
            int input,
            @Nullable String expression,
            @Nullable Object constant,
            @Nullable Object fallback,
            @Nullable MappingEntry entry,
            @Nullable String inputName) {}

    private final MapperConfig config;
    private final CatalogTable input;
    private final CatalogTable output;
    private final List<Output> outputs;
    /** Input columns read by some entry, by from or by an expression; schema changes to them cannot pass. */
    private final Set<String> read;

    private TableMapping(
            MapperConfig config, CatalogTable input, CatalogTable output, List<Output> outputs, Set<String> read) {
        this.config = config;
        this.input = input;
        this.output = output;
        this.outputs = outputs;
        this.read = read;
    }

    CatalogTable input() {
        return input;
    }

    CatalogTable output() {
        return output;
    }

    /** The output name of input column {@code name} when it passes through unchanged, or null. */
    @Nullable
    String passedAs(String name) {
        for (Output out : outputs) {
            if (name.equals(out.inputName())) {
                return out.column().name();
            }
        }
        return null;
    }

    /** Whether an entry reads input column {@code name}. */
    boolean reads(String name) {
        return read.contains(name);
    }

    /** The output name a column added to the input gets when it is kept, or null when it is not. */
    @Nullable
    String keptName(String name) {
        if (config.others() == Others.DROP || config.drop().stream().anyMatch(d -> d.equalsIgnoreCase(name))) {
            return null;
        }
        return Names.apply(config.nameCase(), name);
    }

    /** Derives the output of {@code table}; expressions are type-checked with {@code expressions}. */
    static TableMapping derive(MapperConfig config, CatalogTable table, ExpressionCompiler expressions) {
        return derive(config, table, expressions, true);
    }

    /**
     * Derives the output of {@code table}. Not {@code strict} after a schema change: a column of
     * {@code drop} that the input no longer has is simply gone.
     */
    static TableMapping derive(
            MapperConfig config, CatalogTable table, ExpressionCompiler expressions, boolean strict) {
        List<String> names = table.columnNames();
        Map<Integer, List<MappingEntry>> byInput = new LinkedHashMap<>();
        List<MappingEntry> computed = new ArrayList<>();
        Set<Integer> consumed = new HashSet<>();
        Set<String> read = new HashSet<>();
        for (MappingEntry entry : config.entries()) {
            String from = entry.from();
            if (from == null) {
                computed.add(entry);
                continue;
            }
            int index = Names.find(names, from);
            if (index < 0) {
                if (config.unmapped() == Unmapped.DROP) {
                    continue;
                }
                throw config.invalid(
                        FieldMapperOptions.MAPPING,
                        entry.label() + ": " + table.id() + " has no column " + from + Names.suggestion(from, names));
            }
            byInput.computeIfAbsent(index, i -> new ArrayList<>()).add(entry);
            consumed.add(index);
            read.add(names.get(index));
        }
        for (String name : config.drop()) {
            int index = Names.find(names, name);
            if (index < 0) {
                if (config.unmapped() == Unmapped.DROP || !strict) {
                    continue;
                }
                throw config.invalid(
                        FieldMapperOptions.DROP, table.id() + " has no column " + name + Names.suggestion(name, names));
            }
            consumed.add(index);
        }

        // the expressions of every entry, typed in one go
        Map<MappingEntry, String> texts = new LinkedHashMap<>();
        for (MappingEntry entry : config.entries()) {
            String text = expressionOf(entry, table);
            if (text != null
                    && (entry.from() == null || byInput.values().stream().anyMatch(l -> l.contains(entry)))) {
                texts.put(entry, text);
            }
        }
        List<ExpressionInfo> infos = expressions.analyze(List.copyOf(texts.values()), table.columns());
        Map<MappingEntry, ExpressionInfo> typed = new LinkedHashMap<>();
        int position = 0;
        for (MappingEntry entry : texts.keySet()) {
            typed.put(entry, infos.get(position++));
        }
        for (MappingEntry entry : texts.keySet()) {
            if (entry.expr() != null) {
                for (Column column : table.columns()) {
                    if (mentions(entry.expr(), column.name())) {
                        read.add(column.name());
                    }
                }
            }
        }

        List<Output> outputs = new ArrayList<>();
        if (config.order() == Order.SOURCE) {
            for (int i = 0; i < names.size(); i++) {
                List<MappingEntry> entries = byInput.get(i);
                if (entries != null) {
                    for (MappingEntry entry : entries) {
                        outputs.add(output(config, table, entry, typed.get(entry), i, expressions));
                    }
                } else if (!consumed.contains(i) && config.others() == Others.KEEP) {
                    outputs.add(kept(config, table, i));
                }
            }
            for (MappingEntry entry : computed) {
                outputs.add(output(config, table, entry, typed.get(entry), -1, expressions));
            }
        } else {
            for (MappingEntry entry : config.entries()) {
                if (entry.from() == null) {
                    outputs.add(output(config, table, entry, typed.get(entry), -1, expressions));
                } else {
                    int index = Names.find(names, entry.from());
                    if (index >= 0) {
                        outputs.add(output(config, table, entry, typed.get(entry), index, expressions));
                    }
                }
            }
            for (int i = 0; i < names.size(); i++) {
                if (!consumed.contains(i) && config.others() == Others.KEEP) {
                    outputs.add(kept(config, table, i));
                }
            }
        }

        Set<String> seen = new HashSet<>();
        for (Output out : outputs) {
            if (!seen.add(out.column().name())) {
                throw config.invalid(
                        out.entry() != null ? FieldMapperOptions.MAPPING : FieldMapperOptions.NAME_CASE,
                        "two output columns of " + table.id() + " are named "
                                + out.column().name());
            }
        }
        if (outputs.isEmpty()) {
            throw config.invalid(FieldMapperOptions.MAPPING, "no column of " + table.id() + " is left");
        }
        List<Column> columns = outputs.stream().map(Output::column).toList();
        CatalogTable result = new CatalogTable(
                table.id(),
                columns,
                keys(table.primaryKey(), outputs),
                keys(table.partitionKeys(), outputs),
                table.options(),
                table.comment());
        return new TableMapping(config, table, result, List.copyOf(outputs), Set.copyOf(read));
    }

    /** Output names of {@code keys} when every key column passes through unchanged; otherwise none. */
    private static List<String> keys(List<String> keys, List<Output> outputs) {
        List<String> mapped = new ArrayList<>();
        for (String key : keys) {
            String name = outputs.stream()
                    .filter(o -> key.equals(o.inputName()))
                    .map(o -> o.column().name())
                    .findFirst()
                    .orElse(null);
            if (name == null) {
                return List.of();
            }
            mapped.add(name);
        }
        return mapped;
    }

    /** Whether {@code expr} mentions column {@code name}, quoted or not (a cautious, textual check). */
    private static boolean mentions(String expr, String name) {
        String lower = expr.toLowerCase(java.util.Locale.ROOT);
        String target = name.toLowerCase(java.util.Locale.ROOT);
        int at = lower.indexOf(target);
        while (at >= 0) {
            boolean before = at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1)) && lower.charAt(at - 1) != '_';
            int end = at + target.length();
            boolean after =
                    end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end)) && lower.charAt(end) != '_';
            if (before && after) {
                return true;
            }
            at = lower.indexOf(target, at + 1);
        }
        return false;
    }

    /** The expression of an entry, or null when its value is the input column as it is. */
    private static @Nullable String expressionOf(MappingEntry entry, CatalogTable table) {
        String inner;
        if (entry.value() != null) {
            inner = Names.literal(entry.value());
            DataType type = entry.type() != null ? entry.type() : DataTypes.STRING();
            return "CAST(" + inner + " AS " + Names.castType(type) + ")";
        }
        if (entry.expr() != null) {
            inner = entry.expr();
        } else {
            int index = Names.find(table.columnNames(), Objects.requireNonNull(entry.from()));
            if (entry.type() == null || index < 0) {
                return null;
            }
            inner = Names.quote(table.columnNames().get(index));
        }
        return entry.type() == null ? inner : "CAST((" + inner + ") AS " + Names.castType(entry.type()) + ")";
    }

    private static Output kept(MapperConfig config, CatalogTable table, int index) {
        Column column = table.columns().get(index);
        return new Output(
                column.withName(Names.apply(config.nameCase(), column.name())),
                Source.INPUT,
                index,
                null,
                null,
                null,
                null,
                column.name());
    }

    private static Output output(
            MapperConfig config,
            CatalogTable table,
            MappingEntry entry,
            @Nullable ExpressionInfo info,
            int index,
            ExpressionCompiler expressions) {
        String name = entry.to() != null
                ? entry.to()
                : Names.apply(config.nameCase(), table.columnNames().get(index));
        Column base = index >= 0 ? table.columns().get(index) : null;
        DataType type;
        Source source;
        String expression = null;
        Object constant = null;
        String inputName = null;
        if (info == null) {
            // the input column as it is, renamed
            type = Objects.requireNonNull(base).type();
            source = Source.INPUT;
            inputName = base.name();
        } else if (entry.value() != null) {
            type = info.type();
            source = Source.CONSTANT;
            constant = constant(config, entry, info.text(), "value " + Names.literal(entry.value()), expressions);
            if (constant != null) {
                type = type.withNullability(false);
            }
        } else {
            type = info.type();
            source = Source.EXPRESSION;
            expression = info.text();
        }
        Object fallback = null;
        if (entry.defaultValue() != null) {
            DataType target = type.withNullability(true);
            fallback = constant(
                    config,
                    entry,
                    "CAST(" + Names.literal(entry.defaultValue()) + " AS " + Names.castType(target) + ")",
                    "default " + Names.literal(entry.defaultValue()),
                    expressions);
            type = type.withNullability(false);
        }
        if (entry.nullable() != null) {
            type = type.withNullability(entry.nullable());
        }
        String comment =
                entry.comment() != null ? entry.comment() : base != null && info == null ? base.comment() : null;
        Object columnDefault = fallback != null ? fallback : base != null && info == null ? base.defaultValue() : null;
        Column column = new Column(name, type, comment, columnDefault, Map.of());
        return new Output(column, source, index, expression, constant, fallback, entry, inputName);
    }

    /** Evaluates a constant expression once, while planning; a value that does not convert is an option error. */
    private static @Nullable Object constant(
            MapperConfig config, MappingEntry entry, String text, String what, ExpressionCompiler expressions) {
        try {
            RowEvaluator evaluator = expressions.compile(List.of(text), List.of(), OnError.FAIL);
            Object[] value = new Object[1];
            evaluator.evaluate(GenericRow.of(org.devlive.pipeforge.api.row.TableId.of("constant")), value);
            return value[0];
        } catch (PipeForgeException e) {
            Object reason = e.params().getOrDefault("reason", e.getMessage());
            throw config.invalid(
                    FieldMapperOptions.MAPPING, entry.label() + ": " + what + " does not convert: " + reason);
        }
    }

    // ------------------------------------------------------------------ run time

    /** Maps the rows of this table; one per parallel instance. */
    Mapper mapper(ExpressionCompiler expressions) {
        List<String> texts = new ArrayList<>();
        int[] slots = new int[outputs.size()];
        for (int i = 0; i < outputs.size(); i++) {
            Output out = outputs.get(i);
            if (out.source() == Source.EXPRESSION) {
                slots[i] = texts.size();
                texts.add(Objects.requireNonNull(out.expression()));
            } else {
                slots[i] = -1;
            }
        }
        RowEvaluator evaluator = texts.isEmpty() ? null : expressions.compile(texts, input.columns(), config.onError());
        return new Mapper(slots, evaluator);
    }

    /** Maps rows of the table to output rows. Not thread-safe. */
    final class Mapper {
        private final int[] slots;
        private final @Nullable RowEvaluator evaluator;
        private final @Nullable Object[] computed;

        private Mapper(int[] slots, @Nullable RowEvaluator evaluator) {
            this.slots = slots;
            this.evaluator = evaluator;
            this.computed = new Object[evaluator == null ? 0 : evaluator.size()];
        }

        Row map(Row row) {
            if (evaluator != null) {
                evaluator.evaluate(row, computed);
            }
            Object[] values = new Object[outputs.size()];
            for (int i = 0; i < values.length; i++) {
                Output out = outputs.get(i);
                Object value =
                        switch (out.source()) {
                            case INPUT -> out.input() < row.arity() ? row.get(out.input()) : null;
                            case EXPRESSION -> computed[slots[i]];
                            case CONSTANT -> out.constant();
                        };
                if (value == null) {
                    value = out.fallback();
                    if (value == null && !out.column().type().isNullable()) {
                        throw PipeForgeException.of(ErrorCode.CONSTRAINT_VIOLATION)
                                .param("table", output.id().toString())
                                .param("column", out.column().name())
                                .param("reason", "the column is NOT NULL, but the value is NULL")
                                .kind(ErrorKind.DATA)
                                .build();
                    }
                }
                values[i] = value;
            }
            return GenericRow.ofKind(row.table(), row.kind(), values);
        }
    }
}
