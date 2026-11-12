/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every command with its output, in the formats it has, and its exit codes. */
class CommandsTest {

    static final String JOB =
            """
            version: 1
            name: users
            source:
              - { id: src, type: fake, table: crm.users, columns: [id INT64 NOT NULL, email STRING(24)], primary-key: [id], rows: 3 }
            transform:
              - id: tidy
                type: field-mapper
                mapping:
                  - { from: id, to: user_id }
                  - { from: email, expr: "LOWER(email)" }
            sink:
              - { id: out, type: blackhole }
            """;

    static final String BAD =
            """
            version: 1
            name: broken
            source:
              - { id: src, type: fake, rows: -1 }
            sink:
              - { id: out, type: blackhole }
            """;

    @TempDir
    Path dir;

    Shell shell;

    @BeforeEach
    void setUp() throws Exception {
        shell = new Shell(dir.resolve("work"));
        shell.file("jobs/users.yaml", JOB);
        shell.file("jobs/broken.yaml", BAD);
        shell.file("jobs/notes.txt", "not a job");
    }

    @Test
    void validatesFilesAndDirectories() throws Exception {
        Shell.Result result = shell.run("validate", "jobs");
        assertThat(result.exit()).isEqualTo(ExitCode.INVALID.code());
        shell.assertGolden("validate.txt", result.out());
        assertThat(result.err()).isEmpty();

        Shell.Result valid = shell.run("validate", "jobs/users.yaml");
        assertThat(valid.exit()).isZero();
        assertThat(valid.out()).startsWith("OK  jobs/users.yaml  users\n");
    }

    @Test
    void validationResultsAsJsonAndYaml() throws Exception {
        Shell.Result json = shell.run("-o", "json", "validate", "jobs/broken.yaml");
        assertThat(json.exit()).isEqualTo(3);
        shell.assertGolden("validate.json", json.out());
        Shell.Result yaml = shell.run("validate", "jobs/users.yaml", "--output", "YAML");
        assertThat(yaml.exit()).isZero();
        shell.assertGolden("validate.yaml", yaml.out());
    }

    @Test
    void aMissingFileIsNotFound() throws Exception {
        Shell.Result result = shell.run("validate", "jobs/users.yaml", "jobs/nothing.yaml");
        assertThat(result.exit()).isEqualTo(ExitCode.NOT_FOUND.code());
        assertThat(result.out()).isEmpty();
        assertThat(shell.normalize(result.err()))
                .isEqualTo("ERROR PF-CONF-0008 Cannot read <dir>/jobs/nothing.yaml: the file does not exist\n"
                        + "  Check the path and its permissions; relative paths start from the current directory.\n");

        Shell.Result json = shell.run("-o", "json", "explain", "jobs/nothing.yaml");
        assertThat(json.exit()).isEqualTo(6);
        assertThat(json.err()).contains("\"error\" : {").contains("\"code\" : \"PF-CONF-0008\"");
    }

    @Test
    void explainsAJob() throws Exception {
        Shell.Result result = shell.run("explain", "jobs/users.yaml", "--var", "unused=1");
        assertThat(result.exit()).isZero();
        shell.assertGolden("explain.txt", result.out());
        Shell.Result json = shell.run("-o", "json", "explain", "jobs/users.yaml");
        assertThat(json.out()).contains("\"plan\" : {").contains("\"text\" : \"Job users");
        Shell.Result invalid = shell.run("explain", "jobs/broken.yaml");
        assertThat(invalid.exit()).isEqualTo(3);
        assertThat(invalid.err()).startsWith("ERROR PF-CONF-0002 Option 'rows' of node 'src'");
    }

    @Test
    void runsAJobLocally() throws Exception {
        Shell.Result result = shell.run("run", "--local", "jobs/users.yaml");
        assertThat(result.exit()).isZero();
        assertThat(result.out()).startsWith("Job users SUCCESS in ").contains("read 3 rows, wrote 3 rows");

        Shell.Result analyzed = shell.run("-o", "table", "run", "--local", "--analyze", "jobs/users.yaml");
        assertThat(analyzed.out()).contains("reconcile OK");

        Shell.Result json = shell.run("run", "--local", "jobs/users.yaml", "-o", "json");
        assertThat(json.out())
                .contains("\"job\" : \"users\"")
                .contains("\"status\" : \"SUCCESS\"")
                .contains("\"reconciled\" : true")
                .contains("\"rowsWritten\" : 3")
                .contains("\"error\" : null");
    }

    @Test
    void aDryRunOnlyPlans() throws Exception {
        Shell.Result result = shell.run("run", "--dry-run", "jobs/users.yaml");
        assertThat(result.exit()).isZero();
        assertThat(result.out()).startsWith("Job users  mode=batch");
    }

    @Test
    void runningNeedsLocalInThisVersion() {
        Shell.Result result = shell.run("run", "jobs/users.yaml");
        assertThat(result.exit()).isEqualTo(ExitCode.USAGE.code());
        assertThat(result.err())
                .isEqualTo(
                        "Error: Running on a cluster is not available yet: add --local to run the job in this process,"
                                + " or --dry-run to only plan it.\nRun 'pipeforge run --help' for the usage.\n");
    }

    @Test
    void aFailingRunExitsWith4() throws Exception {
        shell.file(
                "jobs/overflow.yaml",
                """
                version: 1
                name: overflow
                source:
                  - { id: src, type: fake, columns: [id INT64 NOT NULL, n INT32], rows: 50 }
                transform:
                  - { id: narrow, type: field-mapper, mapping: [{ from: n, type: INT8 }], on-error: fail }
                sink:
                  - { id: out, type: blackhole }
                """);
        Shell.Result result = shell.run("run", "--local", "jobs/overflow.yaml");
        assertThat(result.exit()).isEqualTo(ExitCode.RUN_FAILED.code());
        assertThat(result.out()).startsWith("Job overflow FAILED in ").contains("ERROR PF-DATA-0006");

        Shell.Result invalid = shell.run("run", "--local", "jobs/broken.yaml");
        assertThat(invalid.exit()).isEqualTo(ExitCode.INVALID.code());
    }

    @Test
    void aRunPastItsTimeoutIsCancelled() throws Exception {
        shell.file(
                "jobs/stream.yaml",
                """
                version: 1
                name: stream
                source:
                  - { id: src, type: fake, unbounded: true, rows-per-second: 100 }
                sink:
                  - { id: out, type: blackhole }
                """);
        Shell.Result result = shell.run("run", "--local", "--timeout", "1s", "jobs/stream.yaml");
        assertThat(result.exit()).isEqualTo(ExitCode.TIMEOUT.code());
        assertThat(result.out()).contains("CANCELLED").contains("The run was cancelled after the timeout of PT1S.");
        Shell.Result bad = shell.run("run", "--local", "--timeout", "soon", "jobs/stream.yaml");
        assertThat(bad.exit()).isEqualTo(2);
        assertThat(bad.err()).contains("'soon' is not a duration");
    }

    @Test
    void evaluatesExpressions() throws Exception {
        Shell.Result result = shell.run("expr", "eval", "amount * 1.1", "--row", "{\"amount\": 1200}");
        assertThat(result.exit()).isZero();
        shell.assertGolden("expr.txt", result.out());

        Shell.Result typed = shell.run(
                "-o",
                "json",
                "expr",
                "eval",
                "CONCAT(city, '-', CAST(visit AS STRING))",
                "--row",
                "{\"city\": \"杭州\", \"visit\": \"2026-11-07\"}",
                "--column",
                "visit DATE");
        assertThat(typed.exit()).isZero();
        shell.assertGolden("expr.json", typed.out());

        Shell.Result now = shell.run("expr", "eval", "NOW() IS NOT NULL", "-o", "yaml", "--timezone", "Asia/Shanghai");
        assertThat(now.out()).isEqualTo("type: BOOLEAN NOT NULL\nvalue: 'TRUE'\ndeterministic: false\n");

        Shell.Result inferred = shell.run(
                "expr",
                "eval",
                "TYPEOF(a) || ' ' || TYPEOF(b) || ' ' || TYPEOF(c) || ' ' || TYPEOF(d)",
                "--row",
                "{\"a\": true, \"b\": 12345678901234567890123, \"c\": [1, 2], \"d\": null}");
        assertThat(inferred.out()).contains("BOOLEAN DECIMAL(38, 0) JSON STRING");
    }

    @Test
    void expressionMistakesAndBadInput() {
        Shell.Result invalid = shell.run("expr", "eval", "amount +");
        assertThat(invalid.exit()).isEqualTo(3);
        assertThat(invalid.err()).startsWith("ERROR PF-PLAN-0007");

        Shell.Result failing = shell.run("expr", "eval", "1 / 0");
        assertThat(failing.exit()).isEqualTo(1);
        assertThat(failing.err()).startsWith("ERROR PF-DATA-0006");

        assertThat(shell.run("expr", "eval", "a", "--row", "[1]").err())
                .contains("--row is not a JSON object: not an object");
        assertThat(shell.run("expr", "eval", "a", "--row", "{oops").exit()).isEqualTo(2);
        assertThat(shell.run("expr", "eval", "a", "--column", "a").err()).contains("must be a name and a type");
        assertThat(shell.run("expr", "eval", "a", "--column", "a NOPE").err())
                .contains("'NOPE' is not a type (column a)");
        assertThat(shell.run("expr", "eval", "a", "--timezone", "Mars/Base").err())
                .contains("'Mars/Base' is not a time zone");
        Shell.Result value = shell.run("expr", "eval", "d", "--row", "{\"d\": \"yesterday\"}", "--column", "d DATE");
        assertThat(value.exit()).isEqualTo(2);
        assertThat(value.err()).startsWith("ERROR PF-CONF-0007").contains("the value of column d is not a DATE");
        assertThat(shell.run("expr").exit()).isEqualTo(2);
    }

    @Test
    void showsTheVersions() throws Exception {
        Shell.Result result = shell.run("version");
        assertThat(result.exit()).isZero();
        assertThat(shell.normalize(result.out()))
                .startsWith("pipeforge      <version>\nconnector API  <version> (0.x)\njava           ");
        assertThat(shell.run("-o", "json", "version").out()).contains("\"pluginApi\" : \"0.x\"");
        assertThat(shell.normalize(shell.run("--version").out())).isEqualTo("pipeforge <version>\n");
    }

    @Test
    void doctorReportsWhatIsMissing() throws Exception {
        Shell.Result result = shell.run("doctor");
        assertThat(result.exit()).isZero();
        shell.assertGolden("doctor.txt", result.out().replaceAll("java +\\S.*", "java      <java>"));
        assertThat(shell.commands).anyMatch(c -> c.get(0).equals("docker"));

        shell.docker = Optional.of("29.0.0");
        shell.properties.put("pipeforge.home", dir.resolve("work").toString());
        shell.properties.put("stdout.encoding", "ISO-8859-1");
        shell.variables.put("PIPEFORGE_PLUGIN_PATH", dir.resolve("empty").toString());
        Shell.Result broken = shell.run("doctor", "-o", "json");
        assertThat(broken.exit()).isEqualTo(ExitCode.ERROR.code());
        assertThat(broken.out())
                .contains("\"check\" : \"docker\",\n    \"status\" : \"OK\",\n    \"detail\" : \"29.0.0\"")
                .contains("\"status\" : \"FAIL\"")
                .contains("the terminal uses ISO-8859-1");
    }

    @Test
    void printsCompletionScripts() {
        Shell.Result bash = shell.run("completion", "bash");
        assertThat(bash.exit()).isZero();
        assertThat(bash.out())
                .startsWith("#!/usr/bin/env bash")
                .contains("validate")
                .contains("--plugin-path");
        assertThat(shell.run("completion", "ZSH").out()).startsWith("autoload -U +X compinit && compinit\n");
        assertThat(shell.run("completion", "fish").exit()).isEqualTo(2);
    }

    @Test
    void usageErrorsExitWith2() {
        Shell.Result none = shell.run();
        assertThat(none.exit()).isEqualTo(2);
        assertThat(none.err()).startsWith("Usage: pipeforge");

        Shell.Result unknown = shell.run("validat", "x");
        assertThat(unknown.exit()).isEqualTo(2);
        assertThat(unknown.err()).contains("Did you mean: pipeforge validate");

        Shell.Result format = shell.run("-o", "xml", "version");
        assertThat(format.exit()).isEqualTo(2);
        assertThat(format.err()).contains("'xml' is not one of table, json, yaml");

        Shell.Result help = shell.run("validate", "--help");
        assertThat(help.exit()).isZero();
        assertThat(help.out()).contains("Check job files without running them").contains("--plugin-path=<dir>");
    }

    @Test
    void speaksChinese() throws Exception {
        Shell.Result help = shell.run("--lang", "zh-CN", "run", "--help");
        assertThat(help.out()).contains("用法：").contains("在本机运行作业文件并等待它结束");
        shell.variables.put("PIPEFORGE_LANG", "zh");
        Shell.Result invalid = shell.run("validate", "jobs/broken.yaml");
        assertThat(invalid.out()).contains("节点 'src' 的选项 'rows'").contains("0 个有效，1 个无效");
        Shell.Result missing = shell.run("--lang=zh-CN", "validate", "nothing.yaml");
        assertThat(missing.err()).startsWith("ERROR PF-CONF-0008 无法读取").contains("文件不存在");
    }

    @Test
    void verboseShowsCausesAndPluginsComeFromThePath() throws Exception {
        shell.variables.remove("PIPEFORGE_PLUGIN_PATH");
        Shell.Result none = shell.run("validate", "jobs/users.yaml");
        assertThat(none.exit()).isEqualTo(3);
        assertThat(none.out()).contains("PF-PLUG-0001");
        Shell.Result explicit = shell.run(
                "--plugin-path",
                Shell.PLUGINS.toAbsolutePath() + java.io.File.pathSeparator + "missing",
                "validate",
                "jobs/users.yaml",
                "-q",
                "--no-color");
        assertThat(explicit.exit()).isZero();
        Shell.Result verbose = shell.run("-v", "expr", "eval", "1 / 0");
        // The cause of the error follows it; expression errors carry no stack trace.
        assertThat(verbose.err()).contains("PF-DATA-0006").contains("ExprError: division by zero");
    }
}
