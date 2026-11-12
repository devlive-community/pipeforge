/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.example.upper;

import java.util.List;
import java.util.Locale;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.Options;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.transform.Collector;
import org.devlive.pipeforge.api.transform.Transform;
import org.devlive.pipeforge.api.transform.TransformContext;

/** Upper-cases one text column of every table. */
public final class UpperFactory implements TransformFactory {

    static final Option<String> COLUMN = Options.key("column")
            .stringType()
            .noDefault()
            .withDescription("The text column to upper-case.");

    @Override
    public String identifier() {
        return "upper";
    }

    @Override
    public OptionRule optionRule() {
        return OptionRule.builder().required(COLUMN).build();
    }

    @Override
    public Transform createTransform(FactoryContext context) {
        return new Upper(context.options().get(COLUMN));
    }

    /** The row {@code row} of {@code table} becomes. */
    public static Row upper(CatalogTable table, String column, Row row) {
        int index = table.columns().stream().map(Column::name).toList().indexOf(column);
        Object[] values = new Object[row.arity()];
        for (int i = 0; i < row.arity(); i++) {
            Object value = row.get(i);
            values[i] = i == index && value != null ? ((String) value).toUpperCase(Locale.ROOT) : value;
        }
        return GenericRow.ofKind(row.table(), row.kind(), values);
    }

    private static final class Upper implements Transform {
        private static final long serialVersionUID = 1L;
        private final String column;
        private transient List<CatalogTable> inputs = List.of();

        Upper(String column) {
            this.column = column;
        }

        @Override
        public List<CatalogTable> transformSchema(List<CatalogTable> tables) {
            return tables;
        }

        @Override
        public void open(TransformContext context) {
            inputs = context.inputTables();
        }

        @Override
        public void process(Row row, Collector<Row> out) {
            CatalogTable table = inputs.stream().filter(t -> t.id().equals(row.table())).findFirst().orElseThrow();
            out.collect(upper(table, column, row));
        }
    }
}
