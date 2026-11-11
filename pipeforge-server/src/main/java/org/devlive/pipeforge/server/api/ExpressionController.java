/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.expressions.EvaluationContext;
import org.devlive.pipeforge.expressions.Expression;
import org.devlive.pipeforge.expressions.ExpressionEngine;
import org.devlive.pipeforge.expressions.InputSchema;
import org.devlive.pipeforge.expressions.OnError;
import org.devlive.pipeforge.expressions.ValueText;
import org.devlive.pipeforge.server.config.ServerConfiguration.JobDefaults;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Evaluates an expression against one row given by the client. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "expressions", description = "Expression language and functions")
public class ExpressionController {

    private static final TableId ROW = TableId.of("input");

    private final ExpressionEngine engine;
    private final JobDefaults defaults;

    public ExpressionController(ExpressionEngine engine, JobDefaults defaults) {
        this.engine = engine;
        this.defaults = defaults;
    }

    /**
     * An expression and the row it reads.
     *
     * @param columns the input columns with their logical type and value as text (null for NULL)
     * @param timezone the job time zone; the server default when omitted
     */
    public record EvaluationRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String expression,
            @Nullable List<ColumnValue> columns,
            @Nullable String timezone) {}

    /** An input column: name, logical type such as INT64 or STRING(20), and the value as text. */
    public record ColumnValue(String name, String type, @Nullable String value) {}

    /**
     * The result: its logical type and the value as text, null for NULL.
     *
     * @param deterministic whether the expression gives the same result for the same row every time
     */
    public record EvaluationResult(String type, @Nullable String value, boolean deterministic) {}

    @PostMapping("/expressions/evaluate")
    @Operation(
            operationId = "evaluateExpression",
            summary = "Evaluate an expression",
            description = "Type-checks the expression against the given columns and evaluates it on their values."
                    + " Mistakes are PF-PLAN-0007, failures while evaluating PF-DATA-0006.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "表达式求值"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "按给定的列检查表达式的类型，并用这些列的取值求值。表达式有误时返回 PF-PLAN-0007，求值失败时返回 PF-DATA-0006。")
                            }))
    public EvaluationResult evaluate(@RequestBody EvaluationRequest request) {
        if (request == null
                || request.expression() == null
                || request.expression().isBlank()) {
            throw invalid("expression must not be empty");
        }
        ZoneId zone = zone(request.timezone());
        List<ColumnValue> input = request.columns() == null ? List.of() : request.columns();
        List<Column> columns = new ArrayList<>();
        Object[] values = new Object[input.size()];
        for (int i = 0; i < input.size(); i++) {
            ColumnValue column = input.get(i);
            if (column == null || column.name() == null || column.name().isBlank() || column.type() == null) {
                throw invalid("column " + (i + 1) + " needs a name and a type");
            }
            DataType type;
            try {
                type = DataTypes.parse(column.type());
            } catch (IllegalArgumentException e) {
                throw invalid("column '" + column.name() + "' has the unknown type '" + column.type() + "'");
            }
            columns.add(Column.of(column.name(), type));
            try {
                values[i] = ValueText.parse(column.value(), type, zone);
            } catch (IllegalArgumentException e) {
                throw invalid("the value of column '" + column.name() + "' is not a " + type + ": " + e.getMessage());
            }
        }
        Expression expression = engine.analyze(request.expression(), InputSchema.of(columns));
        Object result = engine.compile(List.of(expression), OnError.FAIL, EvaluationContext.of(zone))
                .evaluate(GenericRow.of(ROW, values))[0];
        return new EvaluationResult(
                expression.type().toString(),
                ValueText.render(result, expression.type(), zone),
                expression.deterministic());
    }

    private ZoneId zone(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return defaults.timeZone();
        }
        try {
            return ZoneId.of(text);
        } catch (DateTimeException e) {
            throw invalid("unknown time zone '" + text + "'");
        }
    }

    private static PipeForgeException invalid(String reason) {
        return PipeForgeException.of(ErrorCode.REQUEST_INVALID)
                .param("reason", reason)
                .build();
    }
}
