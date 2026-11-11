/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.web;

import jakarta.servlet.http.HttpServletRequest;
import org.devlive.pipeforge.common.error.ErrorCategory;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorInfo;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every failure into the structured error object, localized by {@code Accept-Language}.
 *
 * <table>
 *   <caption>HTTP status by error</caption>
 *   <tr><td>{@code PF-CONF-0007} REQUEST_INVALID</td><td>400 (404, 405, 415 for unknown paths, methods, media types)</td></tr>
 *   <tr><td>{@code PF-PLUG-0001} PLUGIN_NOT_FOUND</td><td>404</td></tr>
 *   <tr><td>other CONF, PLAN, PLUG and DATA errors</td><td>422</td></tr>
 *   <tr><td>everything else</td><td>500</td></tr>
 * </table>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(PipeForgeException.class)
    public ResponseEntity<ErrorInfo> pipeforge(PipeForgeException e, HttpServletRequest http) {
        HttpStatus status = status(e);
        if (status.is5xxServerError()) {
            LOG.error("Request failed", e);
        }
        return ResponseEntity.status(status)
                .body(e.toErrorInfo(Languages.of(http.getHeader(HttpHeaders.ACCEPT_LANGUAGE))));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorInfo> unreadable(HttpMessageNotReadableException e, HttpServletRequest http) {
        return request(HttpStatus.BAD_REQUEST, "the body is missing or is not valid JSON for this endpoint", http);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorInfo> parameter(Exception e, HttpServletRequest http) {
        return request(HttpStatus.BAD_REQUEST, String.valueOf(e.getMessage()), http);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorInfo> notFound(NoResourceFoundException e, HttpServletRequest http) {
        return request(HttpStatus.NOT_FOUND, "there is no endpoint /" + e.getResourcePath(), http);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorInfo> method(HttpRequestMethodNotSupportedException e, HttpServletRequest http) {
        return request(HttpStatus.METHOD_NOT_ALLOWED, "the endpoint does not support " + e.getMethod(), http);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorInfo> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest http) {
        return request(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "send the body as application/json", http);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorInfo> unexpected(Exception e, HttpServletRequest http) {
        return pipeforge(PipeForgeException.wrap(e), http);
    }

    static HttpStatus status(PipeForgeException e) {
        ErrorCode code = e.code();
        if (code.equals(ErrorCode.REQUEST_INVALID)) {
            return HttpStatus.BAD_REQUEST;
        }
        if (code.equals(ErrorCode.PLUGIN_NOT_FOUND)) {
            return HttpStatus.NOT_FOUND;
        }
        ErrorCategory category = code.category();
        return switch (category) {
            case CONF, PLAN, PLUG, DATA -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static ResponseEntity<ErrorInfo> request(HttpStatus status, String reason, HttpServletRequest http) {
        PipeForgeException error = PipeForgeException.of(ErrorCode.REQUEST_INVALID)
                .param("reason", reason)
                .build();
        return ResponseEntity.status(status)
                .body(error.toErrorInfo(Languages.of(http.getHeader(HttpHeaders.ACCEPT_LANGUAGE))));
    }
}
