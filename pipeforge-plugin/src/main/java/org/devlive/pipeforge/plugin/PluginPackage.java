/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;

/**
 * An installed plugin as one package that workers download: {@code plugin.yaml} and the plugin's
 * classpath as {@code lib/*.jar}; a classes directory, as in development mode, is packed into a
 * jar. The package is built deterministically — entries in order, fixed times — so the same plugin
 * always gives the same bytes and the same SHA-256, which names it.
 */
public final class PluginPackage {

    /** The time of every entry. */
    static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);

    /** A package on disk and its content hash. */
    public record Packed(Path file, String sha256, long size) {}

    private PluginPackage() {}

    /**
     * Writes the package of {@code plugin} into {@code directory}, named by its hash; a package
     * that is there already is reused.
     */
    public static Packed pack(InstalledPlugin plugin, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path partial = Files.createTempFile(directory, plugin.id() + "-", ".partial");
        MessageDigest digest = sha256();
        try (OutputStream file = Files.newOutputStream(partial);
                DigestOutputStream hashed = new DigestOutputStream(file, digest);
                ZipOutputStream zip = new ZipOutputStream(hashed)) {
            put(zip, PluginDescriptor.FILE_NAME, Files.readAllBytes(descriptor(plugin)));
            List<Path> classpath = plugin.classpath();
            for (int i = 0; i < classpath.size(); i++) {
                Path entry = classpath.get(i);
                String prefix = String.format("lib/%03d-", i);
                if (Files.isDirectory(entry)) {
                    put(zip, prefix + "classes.jar", jar(entry));
                } else {
                    put(zip, prefix + entry.getFileName(), Files.readAllBytes(entry));
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        Path target = directory.resolve(sha256 + ".zip");
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Packed(target, sha256, Files.size(target));
    }

    /**
     * The descriptor file: plugin.yaml in the root of an installed plugin, or in src/main/plugin of
     * a module of the source tree (development mode).
     */
    static Path descriptor(InstalledPlugin plugin) throws IOException {
        Path installed = plugin.root().resolve(PluginDescriptor.FILE_NAME);
        if (Files.isRegularFile(installed)) {
            return installed;
        }
        Path module = plugin.root().resolve(DevPlugins.DESCRIPTOR);
        if (Files.isRegularFile(module)) {
            return module;
        }
        throw new java.nio.file.NoSuchFileException(
                installed.toString(), null, "no plugin.yaml for " + plugin.coordinate());
    }

    /** The SHA-256 of a file, in lower-case hex. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** A jar of a classes directory, its entries in order. */
    static byte[] jar(Path directory) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream jar = new ZipOutputStream(bytes);
                Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                put(jar, directory.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
