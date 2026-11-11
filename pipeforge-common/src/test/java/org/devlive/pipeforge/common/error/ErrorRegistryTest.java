/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorRegistryTest {

    private static final String VALID =
            """
            - code: PF-CONF-0101
              name: SAMPLE
              message:
                en-US: "Value {a} of {b}"
                zh-CN: "{b} 的值 {a}"
              hint:
                en-US: "Fix {a}."
                zh-CN: "修正 {a}。"
            """;

    private static List<String> problems(String yaml) {
        return ErrorRegistry.parse(Map.of("errors/conf.yaml", yaml)).problems();
    }

    private static String replace(String target, String replacement) {
        return VALID.replace(target, replacement);
    }

    @Test
    void coreRegistryIsValid() {
        ErrorRegistry registry = ErrorRegistry.load(getClass().getClassLoader());
        assertThat(registry.problems()).isEmpty();
        assertThat(registry.all()).extracting(ErrorDefinition::code).contains("PF-CONF-0001", "PF-SYS-0001");
    }

    @Test
    void everyConstantIsRegisteredWithMatchingNameAndParameters() {
        ErrorRegistry registry = ErrorRegistry.load(getClass().getClassLoader());
        assertThat(registry.checkCodes(ErrorRegistry.constantsOf(ErrorCode.class)))
                .isEmpty();
    }

    @Test
    void everyRegisteredCoreCodeHasAConstant() {
        Set<String> constants = ErrorRegistry.constantsOf(ErrorCode.class).stream()
                .map(ErrorCode::id)
                .collect(Collectors.toSet());
        assertThat(ErrorRegistry.load(getClass().getClassLoader()).all())
                .filteredOn(d -> d.source().startsWith("errors/"))
                .extracting(ErrorDefinition::code)
                .allMatch(constants::contains);
    }

    @Test
    void parsesAValidEntry() {
        ErrorRegistry registry = ErrorRegistry.parse(Map.of("errors/conf.yaml", VALID));
        assertThat(registry.problems()).isEmpty();
        ErrorDefinition definition = registry.find("PF-CONF-0101").orElseThrow();
        assertThat(definition.name()).isEqualTo("SAMPLE");
        assertThat(definition.kind()).isEqualTo(ErrorKind.FATAL);
        assertThat(definition.params()).containsExactly("a", "b");
        assertThat(definition.docs()).isEqualTo("reference/errors#pf-conf-0101");
        assertThat(definition.deprecated()).isFalse();
        assertThat(definition.message("zh-CN")).isEqualTo("{b} 的值 {a}");
        assertThat(definition.message("fr-FR")).isEqualTo("Value {a} of {b}");
        assertThat(definition.hint(null)).isEqualTo("Fix {a}.");
    }

    @Test
    void acceptsKindDeprecationAndDocsOverrides() {
        ErrorRegistry registry = ErrorRegistry.parse(Map.of(
                "errors/conf.yaml",
                VALID.replace("  name: SAMPLE", "  name: SAMPLE\n  kind: transient\n  deprecated: true\n  docs: x/y")));
        ErrorDefinition definition = registry.find("PF-CONF-0101").orElseThrow();
        assertThat(definition.kind()).isEqualTo(ErrorKind.TRANSIENT);
        assertThat(definition.deprecated()).isTrue();
        assertThat(definition.docs()).isEqualTo("x/y");
    }

    @Test
    void rejectsMissingAndEmptyTranslations() {
        assertThat(problems(replace("    zh-CN: \"{b} 的值 {a}\"\n", "")))
                .anyMatch(p -> p.contains("message lacks zh-CN"));
        assertThat(problems(replace("    zh-CN: \"修正 {a}。\"", "    zh-CN: \"  \"")))
                .anyMatch(p -> p.contains("hint in zh-CN is empty"));
        assertThat(problems(replace("    en-US: \"Fix {a}.\"", "    en-US: \"Fix {a}.\"\n    fr-FR: \"x\"")))
                .anyMatch(p -> p.contains("unsupported language 'fr-FR'"));
        assertThat(problems(replace("  hint:\n    en-US: \"Fix {a}.\"\n    zh-CN: \"修正 {a}。\"\n", "  hint: none\n")))
                .anyMatch(p -> p.contains("hint must map languages to texts"));
    }

    @Test
    void rejectsParametersThatDifferBetweenLanguages() {
        assertThat(problems(replace("\"{b} 的值 {a}\"", "\"{b} 的值\"")))
                .anyMatch(p -> p.contains("message in zh-CN uses [b] but en-US uses [a, b]"));
        assertThat(problems(replace("\"修正 {a}。\"", "\"修正 {c}。\"")))
                .anyMatch(p -> p.contains("hint in zh-CN uses parameters [c]"));
    }

    @Test
    void rejectsMalformedEntries() {
        assertThat(problems(replace("PF-CONF-0101", "CONF-101"))).anyMatch(p -> p.contains("code must look like"));
        assertThat(problems(replace("PF-CONF-0101", "PF-XYZ-0101"))).anyMatch(p -> p.contains("unknown category"));
        assertThat(problems(replace("PF-CONF-0101", "PF-PLAN-0101")))
                .anyMatch(p -> p.contains("belongs in errors/plan.yaml"));
        assertThat(problems(replace("SAMPLE", "sample"))).anyMatch(p -> p.contains("upper snake case"));
        assertThat(problems(replace("  name: SAMPLE", "  name: SAMPLE\n  kind: maybe")))
                .anyMatch(p -> p.contains("unknown kind"));
        assertThat(problems(replace("  name: SAMPLE", "  name: SAMPLE\n  extra: 1")))
                .anyMatch(p -> p.contains("unknown key 'extra'"));
        assertThat(problems(replace("  name: SAMPLE", "  name: SAMPLE\n  deprecated: soon")))
                .anyMatch(p -> p.contains("deprecated must be true or false"));
    }

    @Test
    void rejectsBrokenDocuments() {
        assertThat(problems("code: [")).anyMatch(p -> p.contains("not valid YAML"));
        assertThat(problems("code: PF-CONF-0001")).anyMatch(p -> p.contains("must be a list"));
        assertThat(problems("- just text")).anyMatch(p -> p.contains("must be a mapping"));
        assertThat(problems("")).isEmpty();
    }

    @Test
    void rejectsDuplicateCodesAndNames() {
        List<String> duplicateCode = problems(VALID + VALID);
        assertThat(duplicateCode).anyMatch(p -> p.contains("duplicate code PF-CONF-0101"));
        List<String> duplicateName = problems(VALID + VALID.replace("PF-CONF-0101", "PF-CONF-0102"));
        assertThat(duplicateName).anyMatch(p -> p.contains("name SAMPLE is used by PF-CONF-0101 and PF-CONF-0102"));
    }

    @Test
    void reportsCodesThatDisagreeWithTheRegistry() {
        ErrorRegistry registry = ErrorRegistry.parse(Map.of("errors/conf.yaml", VALID));
        assertThat(registry.checkCodes(List.of(
                        ErrorCode.define("PF-CONF-0101", "OTHER", "a", "b"),
                        ErrorCode.define("PF-CONF-0101", "SAMPLE", "a"),
                        ErrorCode.define("PF-CONF-0999", "MISSING"))))
                .containsExactly(
                        "PF-CONF-0101 is registered as SAMPLE but declared as OTHER",
                        "PF-CONF-0101 declares parameters [a] but its message uses [a, b]",
                        "PF-CONF-0999 (MISSING) is not registered");
    }

    @Test
    void loadsPluginRegistriesFromTheClassPath(@TempDir Path dir) throws IOException {
        Path resource = dir.resolve(ErrorRegistry.PLUGIN_RESOURCE);
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, VALID.replace("PF-CONF-0101", "PF-SINK-K17-0001"), StandardCharsets.UTF_8);
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
            ErrorRegistry registry = ErrorRegistry.load(loader);
            assertThat(registry.problems()).isEmpty();
            assertThat(registry.find("PF-SINK-K17-0001")).isPresent();
            assertThat(registry.find("PF-CONF-0001")).isPresent();

            ErrorRegistry global = ErrorRegistry.include(loader);
            assertThat(global.find("PF-SINK-K17-0001")).isPresent();
            assertThat(ErrorRegistry.global().find("PF-SINK-K17-0001")).isPresent();
            assertThat(ErrorRegistry.include(loader).problems()).isEmpty();
        }
    }

    @Test
    void findsConstantsByReflection() {
        assertThat(ErrorRegistry.constantsOf(ErrorCode.class))
                .contains(ErrorCode.OPTION_REQUIRED, ErrorCode.INTERNAL)
                .doesNotHaveDuplicates();
    }
}
