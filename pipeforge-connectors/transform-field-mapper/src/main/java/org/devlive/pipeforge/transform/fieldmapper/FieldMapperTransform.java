/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.AddColumn;
import org.devlive.pipeforge.api.table.AlterColumnComment;
import org.devlive.pipeforge.api.table.AlterColumnType;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.table.CreateTable;
import org.devlive.pipeforge.api.table.DropColumn;
import org.devlive.pipeforge.api.table.DropTable;
import org.devlive.pipeforge.api.table.RenameColumn;
import org.devlive.pipeforge.api.table.RenameTable;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.api.table.TruncateTable;
import org.devlive.pipeforge.api.transform.Collector;
import org.devlive.pipeforge.api.transform.Transform;
import org.devlive.pipeforge.api.transform.TransformContext;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/**
 * Maps every row of every table with the {@link TableMapping} of its table.
 *
 * <p>Schema changes follow the mapping: a column added at the end is kept (renamed like the other
 * kept columns) or left out like them; changes to a column that passes through are renamed and
 * passed on; changes to columns that are dropped or unused disappear; a change to a column that an
 * entry reads fails with {@code PF-PLAN-0002}, because the entry could no longer be computed.
 */
final class FieldMapperTransform implements Transform {

    private static final long serialVersionUID = 1L;

    private final transient MapperConfig config;
    private final transient @Nullable ExpressionCompiler planning;
    private transient @Nullable ExpressionCompiler expressions;
    /** The mapping of every input table and its row mapper; created by {@link #open}. */
    private transient @Nullable Map<TableId, TableMapping> tables;

    private transient @Nullable Map<TableId, TableMapping.Mapper> mappers;

    FieldMapperTransform(MapperConfig config, @Nullable ExpressionCompiler planning) {
        this.config = config;
        this.planning = planning;
    }

    @Override
    public List<CatalogTable> transformSchema(List<CatalogTable> inputs) {
        ExpressionCompiler compiler = planning;
        if (compiler == null) {
            throw new IllegalStateException("The field mapper needs the expression language of the engine");
        }
        List<CatalogTable> outputs = new ArrayList<>();
        for (CatalogTable table : inputs) {
            outputs.add(TableMapping.derive(config, table, compiler).output());
        }
        return outputs;
    }

    @Override
    public void open(TransformContext context) {
        expressions = context.expressions();
        tables = new HashMap<>();
        mappers = new HashMap<>();
        for (CatalogTable table : context.inputTables()) {
            install(table);
        }
    }

    private TableMapping install(CatalogTable table) {
        return install(table, true);
    }

    private TableMapping install(CatalogTable table, boolean strict) {
        ExpressionCompiler compiler = expressions;
        if (compiler == null) {
            throw new IllegalStateException("The field mapper is not open");
        }
        TableMapping mapping = TableMapping.derive(config, table, compiler, strict);
        tables().put(table.id(), mapping);
        mappers().put(table.id(), mapping.mapper(compiler));
        return mapping;
    }

    @Override
    public void process(Row row, Collector<Row> out) {
        TableMapping.Mapper mapper = mappers().get(row.table());
        if (mapper == null) {
            throw new IllegalStateException("A row of " + row.table() + " arrived, but the input has no such table");
        }
        out.collect(mapper.map(row));
    }

    @Override
    public @Nullable SchemaChangeEvent transformSchemaChange(SchemaChangeEvent event) {
        if (event instanceof CreateTable create) {
            return new CreateTable(install(create.definition()).output());
        }
        if (event instanceof DropTable || event instanceof TruncateTable) {
            return event;
        }
        if (event instanceof RenameTable rename) {
            TableMapping mapping = tables().remove(rename.table());
            mappers().remove(rename.table());
            if (mapping != null) {
                install(mapping.input().withId(rename.newId()));
            }
            return event;
        }
        TableId id = event.table();
        TableMapping mapping = tables().get(id);
        if (mapping == null) {
            return event;
        }
        CatalogTable input = mapping.input();
        SchemaChangeEvent mapped;
        CatalogTable changed;
        switch (event) {
            case AddColumn add -> {
                List<Column> columns = new ArrayList<>(input.columns());
                String after = add.afterColumn();
                if (after != null
                        && !after.equals(columns.get(columns.size() - 1).name())) {
                    throw incompatible(id, "a column can only be added at the end of the table, not after " + after);
                }
                columns.add(add.column());
                changed = input.withColumns(columns);
                String name = mapping.keptName(add.column().name());
                if (name == null) {
                    install(changed, false);
                    return null;
                }
                // with order: source the kept column may land before computed columns: say where
                List<Column> output = install(changed, false).output().columns();
                int at = output.stream().map(Column::name).toList().indexOf(name);
                if (at == output.size() - 1) {
                    return new AddColumn(id, output.get(at), null);
                }
                if (at <= 0) {
                    throw incompatible(id, "the added column " + name + " would become the first output column");
                }
                return new AddColumn(id, output.get(at), output.get(at - 1).name());
            }
            case DropColumn drop -> {
                check(mapping, id, drop.column(), "drops");
                List<Column> columns = new ArrayList<>(input.columns());
                columns.removeIf(c -> c.name().equals(drop.column()));
                changed = new CatalogTable(
                        id,
                        columns,
                        without(input.primaryKey(), drop.column()),
                        without(input.partitionKeys(), drop.column()),
                        input.options(),
                        input.comment());
                String name = mapping.passedAs(drop.column());
                mapped = name == null ? null : new DropColumn(id, name);
            }
            case RenameColumn rename -> {
                check(mapping, id, rename.column(), "renames");
                changed = new CatalogTable(
                        id,
                        input.columns().stream()
                                .map(c -> c.name().equals(rename.column()) ? c.withName(rename.newName()) : c)
                                .toList(),
                        renamed(input.primaryKey(), rename),
                        renamed(input.partitionKeys(), rename),
                        input.options(),
                        input.comment());
                String name = mapping.passedAs(rename.column());
                String renamed = mapping.keptName(rename.newName());
                mapped = name == null || renamed == null ? null : new RenameColumn(id, name, renamed);
            }
            case AlterColumnType alter -> {
                check(mapping, id, alter.column(), "changes the type of");
                changed = input.withColumns(input.columns().stream()
                        .map(c -> c.name().equals(alter.column()) ? c.withType(alter.newType()) : c)
                        .toList());
                String name = mapping.passedAs(alter.column());
                mapped = name == null ? null : new AlterColumnType(id, name, alter.newType());
            }
            case AlterColumnComment comment -> {
                changed = input.withColumns(input.columns().stream()
                        .map(c -> c.name().equals(comment.column()) ? c.withComment(comment.comment()) : c)
                        .toList());
                String name = mapping.passedAs(comment.column());
                mapped = name == null ? null : new AlterColumnComment(id, name, comment.comment());
            }
            default -> {
                return event;
            }
        }
        install(changed, false);
        return mapped;
    }

    private Map<TableId, TableMapping> tables() {
        Map<TableId, TableMapping> current = tables;
        if (current == null) {
            throw new IllegalStateException("The field mapper is not open");
        }
        return current;
    }

    private Map<TableId, TableMapping.Mapper> mappers() {
        Map<TableId, TableMapping.Mapper> current = mappers;
        if (current == null) {
            throw new IllegalStateException("The field mapper is not open");
        }
        return current;
    }

    private void check(TableMapping mapping, TableId id, String column, String verb) {
        if (mapping.reads(column)) {
            throw incompatible(id, "the schema change " + verb + " column " + column + ", which the mapping reads");
        }
    }

    private PipeForgeException incompatible(TableId table, String reason) {
        return PipeForgeException.of(ErrorCode.SCHEMA_INCOMPATIBLE)
                .param("node", config.node())
                .param("upstream", table.toString())
                .param("reason", reason)
                .build();
    }

    /** The keys without {@code column}; a key that loses a column is no key at all. */
    private static List<String> without(List<String> keys, String column) {
        return keys.contains(column) ? List.of() : keys;
    }

    private static List<String> renamed(List<String> keys, RenameColumn rename) {
        return keys.stream()
                .map(k -> k.equals(rename.column()) ? rename.newName() : k)
                .toList();
    }
}
