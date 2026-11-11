/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IsolationTest {

    @TempDir
    Path dir;

    @Test
    void loadsPluginClassesFirstExceptForTheApi() throws Exception {
        TestPlugins plugins = new TestPlugins(dir);
        Path lib = plugins.library("child");
        // The parent can see another version of the library; the plugin must still get its own.
        try (java.net.URLClassLoader parent = new java.net.URLClassLoader(
                        new URL[] {plugins.library("parent").toUri().toURL()},
                        getClass().getClassLoader());
                PluginClassLoader loader =
                        new PluginClassLoader("p@1.0.0", new URL[] {lib.toUri().toURL()}, parent)) {
            Class<?> greeting = loader.loadClass("com.example.lib.Greeting");
            assertThat(greeting.getMethod("version").invoke(null)).isEqualTo("child");
            assertThat(greeting.getClassLoader()).isSameAs(loader);
            assertThat(loader.loadClass("com.example.lib.Greeting")).isSameAs(greeting);
            assertThat(loader.loadClass(Row.class.getName())).isSameAs(Row.class);
            assertThat(loader.loadClass("java.lang.String")).isSameAs(String.class);
            assertThat(loader.loadClass(IsolationTest.class.getName())).isSameAs(IsolationTest.class);
            assertThatThrownBy(() -> loader.loadClass("com.example.Missing"))
                    .isInstanceOf(ClassNotFoundException.class);

            assertThat(loader.getResource("com/example/lib/Greeting.class").toString())
                    .contains("lib-child.jar");
            assertThat(loader.getResource("META-INF/pipeforge/api.properties")).isNotNull();
            assertThat(loader.getResource("org/junit/jupiter/api/Test.class")).isNotNull();
            assertThat(Collections.list(loader.getResources("com/example/lib/Greeting.class")))
                    .hasSize(2);
            assertThat(loader.plugin()).isEqualTo("p@1.0.0");
            assertThat(loader).hasToString("PluginClassLoader[p@1.0.0]");
        }
        assertThat(PluginClassLoader.parentFirst("org.devlive.pipeforge.api.row.Row"))
                .isTrue();
        assertThat(PluginClassLoader.parentFirst("org.devlive.pipeforge.engine.Task"))
                .isFalse();
    }

    @Test
    void readsMethodReferencesFromClassFiles() throws IOException {
        byte[] bytes;
        try (InputStream in = Probe.class.getResourceAsStream("IsolationTest$Probe.class")) {
            bytes = in.readAllBytes();
        }
        assertThat(ForbiddenCalls.referencedMethods(bytes))
                .contains("java/util/TimeZone.setDefault", "java/lang/Math.max");
        assertThatThrownBy(() -> ForbiddenCalls.referencedMethods(new byte[] {0, 1, 2, 3, 0, 0, 0, 0, 0, 0}))
                .hasMessage("Not a class file");
    }

    @Test
    void scansJarsForForbiddenCallsExceptAllowedPackages() throws IOException {
        Path jar = dir.resolve("probe.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out);
                InputStream in = Probe.class.getResourceAsStream("IsolationTest$Probe.class")) {
            zip.putNextEntry(new ZipEntry("org/devlive/pipeforge/plugin/IsolationTest$Probe.class"));
            zip.write(in.readAllBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/versions/9/Skipped.class"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.closeEntry();
        }
        assertThat(ForbiddenCalls.scanJar(jar, List.of()))
                .containsExactly(new ForbiddenCalls.Violation(
                        "java.util.TimeZone.setDefault", "org.devlive.pipeforge.plugin.IsolationTest$Probe"));
        assertThat(ForbiddenCalls.scanJar(jar, List.of("org.devlive.pipeforge.plugin.")))
                .isEmpty();
    }

    /** References a forbidden method without ever running it. */
    static final class Probe {
        static void change(boolean really) {
            if (really) {
                java.util.TimeZone.setDefault(null);
            }
            Math.max(1, 2);
        }
    }

    @Test
    void invokerWrapsFailuresWithThePluginName() {
        try (PluginInvoker invoker = new PluginInvoker("p@1.0.0", getClass().getClassLoader())) {
            assertThat(invoker.plugin()).isEqualTo("p@1.0.0");
            assertThat(invoker.classLoader()).isSameAs(getClass().getClassLoader());
            assertThat(invoker.call("read", () -> 42)).isEqualTo(42);
            assertThatThrownBy(() -> invoker.call("read", () -> {
                        throw new IOException("disk gone");
                    }))
                    .isInstanceOf(PipeForgeException.class)
                    .hasMessage("PF-PLUG-0007 Plugin 'p@1.0.0' failed in read: IOException: disk gone")
                    .hasCauseInstanceOf(IOException.class);
            PipeForgeException own = new PipeForgeException(ErrorCode.INTERNAL, Map.of("detail", "x"));
            assertThatThrownBy(() -> invoker.run("write", () -> {
                        throw own;
                    }))
                    .isSameAs(own);
            assertThatThrownBy(() -> invoker.call("link", () -> {
                        throw new NoClassDefFoundError("com/Missing");
                    }))
                    .hasMessageContaining("NoClassDefFoundError: com/Missing");
        }
    }

    @Test
    void invokerInterruptsCallsThatTakeTooLong() throws InterruptedException {
        CountDownLatch interrupted = new CountDownLatch(1);
        try (PluginInvoker invoker = new PluginInvoker("slow@1.0.0", getClass().getClassLoader())) {
            assertThat(invoker.callWithTimeout("ping", Duration.ofSeconds(5), () -> "pong"))
                    .isEqualTo("pong");
            assertThatThrownBy(() -> invoker.callWithTimeout("connect", Duration.ofMillis(100), () -> {
                        try {
                            Thread.sleep(10_000);
                        } catch (InterruptedException e) {
                            interrupted.countDown();
                            throw e;
                        }
                        return null;
                    }))
                    .isInstanceOf(PipeForgeException.class)
                    .hasMessage("PF-PLUG-0006 Plugin 'slow@1.0.0' did not finish connect within 100 ms");
            assertThat(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            assertThatThrownBy(() -> invoker.callWithTimeout("fail", Duration.ofSeconds(5), () -> {
                        throw new IllegalStateException("broken");
                    }))
                    .hasMessage("PF-PLUG-0007 Plugin 'slow@1.0.0' failed in fail: IllegalStateException: broken");
            PipeForgeException own = new PipeForgeException(ErrorCode.INTERNAL, Map.of("detail", "y"));
            assertThatThrownBy(() -> invoker.callWithTimeout("own", Duration.ofSeconds(5), () -> {
                        throw own;
                    }))
                    .isSameAs(own);
        }
    }

    @Test
    void invokerRestoresTheInterruptFlag() {
        try (PluginInvoker invoker = new PluginInvoker("p@1.0.0", getClass().getClassLoader())) {
            assertThatThrownBy(() -> invoker.call("wait", () -> {
                        throw new InterruptedException("stop");
                    }))
                    .isInstanceOf(PipeForgeException.class);
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Test
    void rejectsZipsThatEscapeTheirDirectory() throws IOException {
        Path zip = dir.resolve("plugins/evil.zip");
        Files.createDirectories(zip.getParent());
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream stream = new ZipOutputStream(out)) {
            stream.putNextEntry(new ZipEntry("../../outside.txt"));
            stream.write(1);
            stream.closeEntry();
        }
        PluginRepository.Scan scan = new PluginRepository(dir.resolve("cache")).scan(List.of(dir.resolve("plugins")));
        assertThat(scan.plugins()).isEmpty();
        assertThat(scan.rejected()).singleElement().satisfies(r -> assertThat(r.reason())
                .contains("escapes"));
        assertThat(dir.resolve("outside.txt")).doesNotExist();
        assertThat(PluginRepository.hash(zip)).hasSize(32);
    }
}
