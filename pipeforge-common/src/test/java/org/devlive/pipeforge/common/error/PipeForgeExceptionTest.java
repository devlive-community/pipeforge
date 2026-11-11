/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PipeForgeExceptionTest {

    private static PipeForgeException optionRequired() {
        return PipeForgeException.of(ErrorCode.OPTION_REQUIRED)
                .param("option", "url")
                .param("node", "source")
                .scope(ErrorScope.node("source").withJob("orders"))
                .build();
    }

    @Test
    void rendersTheEnglishMessageWithTheCode() {
        assertThat(optionRequired()).hasMessage("PF-CONF-0001 Required option 'url' is missing in node 'source'");
    }

    @Test
    void rendersLocalizedMessagesAndHints() {
        PipeForgeException e = optionRequired();
        assertThat(e.localizedMessage("zh-CN")).isEqualTo("节点 'source' 缺少必填选项 'url'");
        assertThat(e.hint("zh-CN")).isEqualTo("在节点中添加 'url'，或者在 env 或集群默认配置中设置它。");
        assertThat(e.localizedMessage("ja-JP")).isEqualTo("Required option 'url' is missing in node 'source'");
        assertThat(e.hint(null)).startsWith("Add 'url' to the node");
    }

    @Test
    void keepsParametersInDeclarationOrder() {
        PipeForgeException e = PipeForgeException.of(ErrorCode.OPTION_REQUIRED)
                .param("node", "n")
                .param("option", "o")
                .build();
        assertThat(e.params().keySet()).containsExactly("option", "node");
    }

    @Test
    void rejectsMissingOrUnknownParameters() {
        assertThatThrownBy(() -> PipeForgeException.of(ErrorCode.OPTION_REQUIRED)
                        .param("option", "x")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PF-CONF-0001 expects parameters [option, node]");
        assertThatThrownBy(() -> new PipeForgeException(ErrorCode.INTERNAL, Map.of("detail", "x", "extra", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesTheRegisteredKindUnlessOverridden() {
        assertThat(optionRequired().kind()).isEqualTo(ErrorKind.FATAL);
        PipeForgeException overridden = PipeForgeException.of(ErrorCode.INTERNAL)
                .param("detail", "flaky")
                .kind(ErrorKind.TRANSIENT)
                .build();
        assertThat(overridden.kind()).isEqualTo(ErrorKind.TRANSIENT);
    }

    @Test
    void producesTheStructuredErrorObject() {
        IOException root = new IOException("disk");
        PipeForgeException e = PipeForgeException.of(ErrorCode.INTERNAL)
                .param("detail", "boom")
                .cause(new IllegalStateException("wrapper", root))
                .build();
        ErrorInfo info = e.toErrorInfo("zh-CN");
        assertThat(info.code()).isEqualTo("PF-SYS-0001");
        assertThat(info.name()).isEqualTo("INTERNAL");
        assertThat(info.params()).containsEntry("detail", "boom");
        assertThat(info.scope()).isEmpty();
        assertThat(info.kind()).isEqualTo(ErrorKind.FATAL);
        assertThat(info.message()).isEqualTo("内部错误：boom");
        assertThat(info.hint()).contains("缺陷");
        assertThat(info.causeChain())
                .containsExactly("java.lang.IllegalStateException: wrapper", "java.io.IOException: disk");
        assertThat(optionRequired().toErrorInfo("en-US").scope())
                .containsExactly(Map.entry("job", "orders"), Map.entry("node", "source"));
    }

    @Test
    void causeChainStopsAtCycles() {
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);
        assertThat(PipeForgeException.causeChain(new RuntimeException(a))).hasSize(2);
    }

    @Test
    void wrapsUnexpectedExceptionsAsInternalErrors() {
        PipeForgeException wrapped = PipeForgeException.wrap(new ArithmeticException("/ by zero"));
        assertThat(wrapped.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(wrapped.getMessage())
                .isEqualTo("PF-SYS-0001 Internal error: java.lang.ArithmeticException: / by zero");
        PipeForgeException original = optionRequired();
        assertThat(PipeForgeException.wrap(original)).isSameAs(original);
    }

    @Test
    void fallsBackWhenACodeIsNotRegistered() {
        ErrorCode unknown = ErrorCode.define("PF-SRC-0999", "UNKNOWN_SOURCE_ERROR", "x");
        PipeForgeException e = new PipeForgeException(unknown, Map.of("x", 1));
        assertThat(e.getMessage()).isEqualTo("PF-SRC-0999 UNKNOWN_SOURCE_ERROR {x=1}");
        assertThat(e.localizedMessage("zh-CN")).isEqualTo("UNKNOWN_SOURCE_ERROR {x=1}");
        assertThat(e.hint("en-US")).isEmpty();
        assertThat(e.kind()).isEqualTo(ErrorKind.FATAL);
    }

    @Test
    void nullParameterValuesAreRenderedAsNull() {
        PipeForgeException e =
                PipeForgeException.of(ErrorCode.INTERNAL).param("detail", null).build();
        assertThat(e.getMessage()).isEqualTo("PF-SYS-0001 Internal error: null");
    }
}
