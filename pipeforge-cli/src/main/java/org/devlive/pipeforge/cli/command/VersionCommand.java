/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;
import org.devlive.pipeforge.api.ApiVersion;
import org.devlive.pipeforge.cli.BaseCommand;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Output;
import org.devlive.pipeforge.cli.Session;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

/** {@code pipeforge version}: the versions of the command, the connector API and Java. */
@Command(name = "version", mixinStandardHelpOptions = true)
public final class VersionCommand extends BaseCommand {

    /**
     * The versions, as in the server's {@code GET /api/v1/version}.
     *
     * @param pluginApi the api-version that plugins must declare, e.g. 0.x
     */
    public record Result(String version, String connectorApi, String pluginApi, String java) {}

    /** The version of this build. */
    public static String version() {
        Properties properties = new Properties();
        try (InputStream in =
                VersionCommand.class.getResourceAsStream("/org/devlive/pipeforge/cli/version.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.getProperty("version", "unknown");
    }

    static Result result() {
        Runtime.Version java = Runtime.version();
        return new Result(
                version(),
                ApiVersion.current(),
                ApiVersion.descriptorForm(),
                java + " (" + System.getProperty("java.vendor", "unknown") + ")");
    }

    @Override
    protected ExitCode execute(Session session) {
        Result result = result();
        session.print(
                result,
                Output.columns(List.of(
                        List.of("pipeforge", result.version()),
                        List.of(
                                session.messages().get("version.api"),
                                result.connectorApi() + " (" + result.pluginApi() + ")"),
                        List.of("java", result.java()))));
        return ExitCode.SUCCESS;
    }

    /** For --version. */
    public static final class Provider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"pipeforge " + version()};
        }
    }
}
