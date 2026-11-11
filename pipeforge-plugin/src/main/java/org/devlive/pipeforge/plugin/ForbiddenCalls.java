/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * Finds calls that change the whole JVM by reading the constant pools of class files: exiting
 * the JVM, system properties, default time zone and locale, security providers and properties,
 * the standard streams and other JVM-wide handlers. A plugin that references one of them is not
 * loaded; packages of trusted official clients can be allowed.
 */
public final class ForbiddenCalls {

    /** {@code owner.method} pairs, owners in internal form. */
    static final Set<String> FORBIDDEN = Set.of(
            "java/lang/System.exit",
            "java/lang/Runtime.exit",
            "java/lang/Runtime.halt",
            "java/lang/System.setProperty",
            "java/lang/System.clearProperty",
            "java/lang/System.setProperties",
            "java/lang/System.setSecurityManager",
            "java/lang/System.setOut",
            "java/lang/System.setErr",
            "java/lang/System.setIn",
            "java/util/TimeZone.setDefault",
            "java/util/Locale.setDefault",
            "java/security/Security.setProperty",
            "java/security/Security.addProvider",
            "java/security/Security.insertProviderAt",
            "java/security/Security.removeProvider",
            "java/lang/Thread.setDefaultUncaughtExceptionHandler",
            "java/net/URL.setURLStreamHandlerFactory");

    /** A forbidden call found in a class. */
    public record Violation(String call, String location) {}

    private ForbiddenCalls() {}

    /**
     * Scans every class of a jar or of a classes directory.
     *
     * @param allowedPackages class name prefixes (dotted) whose classes are not checked
     */
    public static List<Violation> scan(Path entry, List<String> allowedPackages) throws IOException {
        return Files.isDirectory(entry) ? scanDirectory(entry, allowedPackages) : scanJar(entry, allowedPackages);
    }

    /**
     * Scans every class below a classes directory, such as target/classes.
     *
     * @param allowedPackages class name prefixes (dotted) whose classes are not checked
     */
    public static List<Violation> scanDirectory(Path directory, List<String> allowedPackages) throws IOException {
        List<Violation> violations = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(directory)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            String name = directory.relativize(file).toString().replace('\\', '/');
            String className = className(name, allowedPackages);
            if (className != null) {
                check(Files.readAllBytes(file), className, violations);
            }
        }
        return violations;
    }

    /**
     * Scans every class of a jar.
     *
     * @param allowedPackages class name prefixes (dotted) whose classes are not checked
     */
    public static List<Violation> scanJar(Path jar, List<String> allowedPackages) throws IOException {
        List<Violation> violations = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String className = className(entry.getName(), allowedPackages);
                if (className == null) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    check(in.readAllBytes(), className, violations);
                }
            }
        }
        return violations;
    }

    /** The class name of a class file entry to check, or null for other entries and allowed classes. */
    private static @Nullable String className(String name, List<String> allowedPackages) {
        if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) {
            return null;
        }
        String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
        return allowedPackages.stream().anyMatch(className::startsWith) ? null : className;
    }

    private static void check(byte[] classFile, String className, List<Violation> violations) throws IOException {
        for (String call : referencedMethods(classFile)) {
            if (FORBIDDEN.contains(call)) {
                violations.add(new Violation(call.replace('/', '.'), className));
            }
        }
    }

    /** Every {@code owner.method} a class file references in its constant pool. */
    static Set<String> referencedMethods(byte[] classFile) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("Not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        int[][] memberRefs = new int[count][];
        int[] nameAndTypeName = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 7 -> classNameIndex[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 9, 10, 11 -> memberRefs[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                case 12 -> {
                    nameAndTypeName[i] = in.readUnsignedShort();
                    in.readUnsignedShort();
                }
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                case 17, 18 -> {
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("Unknown constant pool tag " + tag);
            }
        }
        Set<String> methods = new LinkedHashSet<>();
        for (int[] ref : memberRefs) {
            if (ref == null) {
                continue;
            }
            String owner = utf8[classNameIndex[ref[0]]];
            String name = utf8[nameAndTypeName[ref[1]]];
            methods.add(owner + "." + name);
        }
        return methods;
    }
}
