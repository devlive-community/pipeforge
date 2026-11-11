/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.error;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Every user-visible error: a registered {@link ErrorCode}, the parameters for its message, where
 * it happened and how the engine should treat it. The exception message is the English text;
 * {@link #toErrorInfo(String)} renders the structured object in any supported language.
 *
 * <pre>{@code
 * throw PipeForgeException.of(ErrorCode.OPTION_REQUIRED)
 *         .param("option", "url")
 *         .param("node", "source")
 *         .scope(ErrorScope.node("source"))
 *         .build();
 * }</pre>
 */
@SuppressFBWarnings(value = "SE_BAD_FIELD", justification = "Parameters and scope are rendered to text before transfer")
public final class PipeForgeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final Map<String, Object> params;
    private final ErrorScope scope;
    private final @Nullable ErrorKind kind;

    private PipeForgeException(
            ErrorCode code,
            Map<String, Object> params,
            ErrorScope scope,
            @Nullable ErrorKind kind,
            @Nullable Throwable cause) {
        super(render(code, params, ErrorDefinition.DEFAULT_LANGUAGE), cause);
        this.code = code;
        this.params = params;
        this.scope = scope;
        this.kind = kind;
    }

    /**
     * Creates an exception with the given parameters.
     *
     * @throws IllegalArgumentException if the parameters differ from those the code declares
     */
    public PipeForgeException(ErrorCode code, Map<String, ?> params) {
        this(code, checked(code, params), ErrorScope.none(), null, null);
    }

    /** Starts building an exception for {@code code}. */
    public static Builder of(ErrorCode code) {
        return new Builder(code);
    }

    /** Wraps an unexpected exception as {@link ErrorCode#INTERNAL}, keeping existing PipeForge errors. */
    public static PipeForgeException wrap(Throwable throwable) {
        if (throwable instanceof PipeForgeException e) {
            return e;
        }
        return of(ErrorCode.INTERNAL)
                .param("detail", throwable.getClass().getName() + ": " + throwable.getMessage())
                .cause(throwable)
                .build();
    }

    private static Map<String, Object> checked(ErrorCode code, Map<String, ?> params) {
        Set<String> declared = new HashSet<>(code.params());
        if (!declared.equals(params.keySet())) {
            throw new IllegalArgumentException(
                    code.id() + " expects parameters " + code.params() + " but got " + params.keySet());
        }
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String name : code.params()) {
            ordered.put(name, params.get(name));
        }
        return Collections.unmodifiableMap(ordered);
    }

    private static String render(ErrorCode code, Map<String, Object> params, String language) {
        Optional<ErrorDefinition> definition = ErrorRegistry.global().find(code);
        String text = definition
                .map(d -> MessageTemplate.render(d.message(language), params))
                .orElseGet(() -> code.name() + " " + params);
        return code.id() + " " + text;
    }

    public ErrorCode code() {
        return code;
    }

    /** Parameters in the order the code declares them. */
    public Map<String, Object> params() {
        return params;
    }

    public ErrorScope scope() {
        return scope;
    }

    /** The kind set by the thrower, else the registered kind, else the category default. */
    public ErrorKind kind() {
        if (kind != null) {
            return kind;
        }
        return ErrorRegistry.global()
                .find(code)
                .map(ErrorDefinition::kind)
                .orElse(code.category().defaultKind());
    }

    /** The message without the code, in {@code language} (falls back to English). */
    public String localizedMessage(@Nullable String language) {
        return ErrorRegistry.global()
                .find(code)
                .map(d -> MessageTemplate.render(d.message(language), params))
                .orElseGet(() -> code.name() + " " + params);
    }

    /** The troubleshooting hint in {@code language} (falls back to English). */
    public String hint(@Nullable String language) {
        return ErrorRegistry.global()
                .find(code)
                .map(d -> MessageTemplate.render(d.hint(language), params))
                .orElse("");
    }

    /** The structured error object, with texts in {@code language}. */
    public ErrorInfo toErrorInfo(@Nullable String language) {
        return new ErrorInfo(
                code.id(),
                code.name(),
                params,
                scope.toMap(),
                kind(),
                localizedMessage(language),
                hint(language),
                causeChain(this));
    }

    /** {@code Class: message} for every cause below {@code throwable}, without cycles. */
    static List<String> causeChain(Throwable throwable) {
        List<String> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable.getCause();
        while (current != null && seen.add(current)) {
            chain.add(current.getClass().getName() + ": " + current.getMessage());
            current = current.getCause();
        }
        return chain;
    }

    /** Builder for {@link PipeForgeException}. */
    public static final class Builder {
        private final ErrorCode code;
        private final Map<String, Object> params = new LinkedHashMap<>();
        private ErrorScope scope = ErrorScope.none();
        private @Nullable ErrorKind kind;
        private @Nullable Throwable cause;

        private Builder(ErrorCode code) {
            this.code = code;
        }

        public Builder param(String name, @Nullable Object value) {
            params.put(name, value == null ? "null" : value);
            return this;
        }

        public Builder scope(ErrorScope value) {
            this.scope = value;
            return this;
        }

        /** Overrides the registered kind, e.g. a constraint violation that is fatal in context. */
        public Builder kind(ErrorKind value) {
            this.kind = value;
            return this;
        }

        public Builder cause(@Nullable Throwable value) {
            this.cause = value;
            return this;
        }

        /**
         * Builds the exception.
         *
         * @throws IllegalArgumentException if a declared parameter is missing or an unknown one is set
         */
        public PipeForgeException build() {
            return new PipeForgeException(code, checked(code, params), scope, kind, cause);
        }
    }
}
