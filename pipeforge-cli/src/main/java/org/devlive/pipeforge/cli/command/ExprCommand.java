/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.cli.BaseCommand;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Failure;
import org.devlive.pipeforge.cli.Output;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.expressions.EvaluationContext;
import org.devlive.pipeforge.expressions.Evaluator;
import org.devlive.pipeforge.expressions.Expression;
import org.devlive.pipeforge.expressions.ExpressionEngine;
import org.devlive.pipeforge.expressions.InputSchema;
import org.devlive.pipeforge.expressions.OnError;
import org.devlive.pipeforge.expressions.ValueText;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;

/** {@code pipeforge expr}: the expression language on the command line. */
@Command(name = "expr", mixinStandardHelpOptions = true, subcommands = ExprCommand.Eval.class)
public final class ExprCommand implements java.util.concurrent.Callable<Integer> {

    @picocli.CommandLine.Spec
    picocli.CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return ExitCode.USAGE.code();
    }

    /** {@code pipeforge expr eval}: evaluates an expression on one row, locally. */
    @Command(name = "eval", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Eval extends BaseCommand {

        private static final ObjectMapper JSON =
                new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        private static final TableId ROW = TableId.of("row");

        @Parameters(paramLabel = "<expression>", descriptionKey = "expr.expression")
        String expression;

        @Option(names = "--row", paramLabel = "<json>", descriptionKey = "expr.row")
        @Nullable
        String row;

        @Option(names = "--column", paramLabel = "<name TYPE>", descriptionKey = "expr.column")
        List<String> columns = new ArrayList<>();

        @Option(names = "--timezone", paramLabel = "<zone>", defaultValue = "UTC", descriptionKey = "expr.timezone")
        String timezone = "UTC";

        /** The result, with the fields of the REST API. */
        public record Result(String type, @Nullable String value, boolean deterministic) {}

        @Override
        protected ExitCode execute(Session session) {
            ZoneId zone;
            try {
                zone = ZoneId.of(timezone);
            } catch (DateTimeException e) {
                throw new ParameterException(
                        spec.commandLine(), session.messages().get("expr.bad-zone", timezone));
            }
            Map<String, DataType> declared = declared(session);
            Map<String, JsonNode> values = values(session);
            List<Column> schema = new ArrayList<>();
            List<Object> data = new ArrayList<>();
            for (Map.Entry<String, DataType> column : declared.entrySet()) {
                JsonNode value = values.remove(column.getKey());
                schema.add(Column.of(column.getKey(), column.getValue()));
                data.add(parse(session, column.getKey(), value, column.getValue(), zone));
            }
            for (Map.Entry<String, JsonNode> value : values.entrySet()) {
                DataType type = infer(value.getValue());
                schema.add(Column.of(value.getKey(), type));
                data.add(parse(session, value.getKey(), value.getValue(), type, zone));
            }
            ExpressionEngine engine = new ExpressionEngine();
            Expression analyzed;
            try {
                analyzed = engine.analyze(expression, InputSchema.of(schema));
            } catch (PipeForgeException e) {
                throw new Failure(e, ExitCode.INVALID);
            }
            Object result;
            try (Evaluator evaluator = engine.compile(List.of(analyzed), OnError.FAIL, EvaluationContext.of(zone))) {
                result = evaluator.evaluate(GenericRow.of(ROW, data.toArray()))[0];
            }
            Result outcome = new Result(
                    analyzed.type().toString(),
                    ValueText.render(result, analyzed.type(), zone),
                    analyzed.deterministic());
            String text = Output.columns(List.of(
                    List.of(session.messages().get("expr.value"), outcome.value() == null ? "NULL" : outcome.value()),
                    List.of(session.messages().get("expr.type"), outcome.type())));
            session.print(outcome, text);
            return ExitCode.SUCCESS;
        }

        private Map<String, DataType> declared(Session session) {
            Map<String, DataType> declared = new LinkedHashMap<>();
            for (String column : columns) {
                String[] parts = column.trim().split("\\s+", 2);
                if (parts.length < 2) {
                    throw new ParameterException(
                            spec.commandLine(), session.messages().get("expr.bad-column", column));
                }
                try {
                    declared.put(parts[0], DataTypes.parse(parts[1]));
                } catch (IllegalArgumentException e) {
                    throw new ParameterException(
                            spec.commandLine(), session.messages().get("expr.bad-type", parts[1], parts[0]));
                }
            }
            return declared;
        }

        private Map<String, JsonNode> values(Session session) {
            Map<String, JsonNode> values = new LinkedHashMap<>();
            if (row == null) {
                return values;
            }
            JsonNode node;
            try {
                node = JSON.readTree(row);
            } catch (JsonProcessingException e) {
                throw new ParameterException(
                        spec.commandLine(), session.messages().get("expr.bad-row", e.getOriginalMessage()));
            }
            if (node == null || !node.isObject()) {
                throw new ParameterException(
                        spec.commandLine(), session.messages().get("expr.bad-row", "not an object"));
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                values.put(field.getKey(), field.getValue());
            }
            return values;
        }

        /** The type of an undeclared value: by its JSON form. */
        static DataType infer(JsonNode value) {
            if (value.isBoolean()) {
                return DataTypes.BOOLEAN();
            }
            if (value.isIntegralNumber()) {
                return value.canConvertToLong() ? DataTypes.INT64() : DataTypes.DECIMAL(38, 0);
            }
            if (value.isNumber()) {
                BigDecimal decimal = value.decimalValue();
                int scale = Math.max(0, decimal.scale());
                int precision = Math.max(decimal.precision(), scale + 1);
                return precision <= DataTypes.MAX_DECIMAL_PRECISION
                        ? DataTypes.DECIMAL(precision, scale)
                        : DataTypes.FLOAT64();
            }
            if (value.isArray() || value.isObject()) {
                return DataTypes.JSON();
            }
            return DataTypes.STRING();
        }

        private static @Nullable Object parse(
                Session session, String name, @Nullable JsonNode value, DataType type, ZoneId zone) {
            if (value == null || value.isNull()) {
                return null;
            }
            String text = value.isTextual() ? value.textValue() : value.toString();
            try {
                return ValueText.parse(text, type, zone);
            } catch (IllegalArgumentException e) {
                throw new Failure(
                        PipeForgeException.of(ErrorCode.REQUEST_INVALID)
                                .param("reason", session.messages().get("expr.bad-value", name, type, e.getMessage()))
                                .build(),
                        ExitCode.USAGE);
            }
        }
    }
}
