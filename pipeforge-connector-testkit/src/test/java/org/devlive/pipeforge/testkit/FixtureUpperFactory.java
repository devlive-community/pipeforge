/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.Options;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.transform.Collector;
import org.devlive.pipeforge.api.transform.Transform;
import org.devlive.pipeforge.api.transform.TransformContext;
import org.devlive.pipeforge.api.type.DataTypes;

/**
 * A transform for the testkit's own tests: upper-cases a text column and appends a NOT NULL column
 * {@code seen}. It can fail on every row, or lose the row with id 0 to show that the contract
 * notices.
 */
public final class FixtureUpperFactory implements TransformFactory {

    static final Option<String> COLUMN = Options.key("column").stringType().noDefault();
    static final Option<Boolean> FAIL = Options.key("fail").booleanType().defaultValue(false);
    static final Option<Boolean> DROP_FIRST =
            Options.key("drop-first").booleanType().defaultValue(false);

    @Override
    public String identifier() {
        return "fixture-upper";
    }

    @Override
    public OptionRule optionRule() {
        return OptionRule.builder().required(COLUMN).optional(FAIL, DROP_FIRST).build();
    }

    @Override
    public Transform createTransform(FactoryContext context) {
        return new Upper(context.options());
    }

    /** The output of {@code table}: the same columns and {@code seen BOOLEAN NOT NULL}. */
    static CatalogTable output(CatalogTable table) {
        List<Column> columns = new ArrayList<>(table.columns());
        columns.add(Column.of("seen", DataTypes.BOOLEAN().notNull()));
        return table.withColumns(columns);
    }

    /** The row {@code row} of {@code table} becomes. */
    static Row output(CatalogTable table, String column, Row row) {
        int index = table.columns().stream().map(Column::name).toList().indexOf(column);
        Object[] values = new Object[row.arity() + 1];
        for (int i = 0; i < row.arity(); i++) {
            Object value = row.get(i);
            values[i] = i == index && value != null ? ((String) value).toUpperCase(Locale.ROOT) : value;
        }
        values[row.arity()] = true;
        return GenericRow.ofKind(row.table(), row.kind(), values);
    }

    private static final class Upper implements Transform {
        private static final long serialVersionUID = 1L;
        private final transient ReadonlyConfig config;
        private transient List<CatalogTable> inputs = List.of();

        Upper(ReadonlyConfig config) {
            this.config = config;
        }

        @Override
        public List<CatalogTable> transformSchema(List<CatalogTable> tables) {
            return tables.stream().map(FixtureUpperFactory::output).toList();
        }

        @Override
        public void open(TransformContext context) {
            inputs = context.inputTables();
        }

        @Override
        public void process(Row row, Collector<Row> out) {
            if (config.get(FAIL)) {
                throw new IllegalStateException("fixture-upper fails on purpose");
            }
            if (config.get(DROP_FIRST) && Long.valueOf(0L).equals(row.get(0))) {
                return;
            }
            CatalogTable table = inputs.stream()
                    .filter(t -> t.id().equals(row.table()))
                    .findFirst()
                    .orElseThrow();
            out.collect(output(table, config.get(COLUMN), row));
        }
    }
}
