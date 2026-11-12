/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

/** Turns groups into dynamic tests and applies the skipped groups. */
final class Contracts {

    /** Every group of the testkit; skipping an unknown group is an error. */
    static final Set<String> KNOWN = Set.of(
            "options",
            "types",
            "batch",
            "resume",
            "stream",
            "cdc",
            "exactly-once",
            "multi-table",
            "schema",
            "dirty",
            "native-config",
            "metrics",
            "cluster",
            "compat",
            "transform",
            "stateful",
            "function",
            "rollback");

    static final String SKIPPED_KEY = "skipped-testkit-groups";

    private Contracts() {}

    /** The system property that selects the groups to run: names separated by commas. */
    static final String SELECTED_PROPERTY = "pipeforge.testkit.groups";

    static Stream<DynamicNode> run(List<ContractGroup> groups, Map<String, String> skipped, TestkitEngine engine) {
        return run(groups, skipped, selected(System.getProperty(SELECTED_PROPERTY)), engine);
    }

    /** The groups named in the property, or empty for all groups. */
    static Set<String> selected(@Nullable String property) {
        Set<String> names = new LinkedHashSet<>();
        if (property != null) {
            for (String name : property.split(",", -1)) {
                if (!name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }
        return names;
    }

    /**
     * The groups as dynamic tests. Skipped groups and, with a selection, the groups not selected are
     * reported as skipped with the reason; an unknown skipped group and a selected name that is no
     * group of this component fail, so typos are caught.
     */
    static Stream<DynamicNode> run(
            List<ContractGroup> groups, Map<String, String> skipped, Set<String> selected, TestkitEngine engine) {
        Stream<DynamicNode> unknown = skipped.keySet().stream()
                .filter(name -> !KNOWN.contains(name))
                .map(name -> DynamicTest.dynamicTest("skipped group " + name, () -> {
                    throw new AssertionFailedError(
                            "'" + name + "' in " + SKIPPED_KEY + " is not a testkit group; known: "
                                    + KNOWN.stream().sorted().toList());
                }));
        List<String> available = groups.stream().map(ContractGroup::name).toList();
        Stream<DynamicNode> missing = selected.stream()
                .filter(name -> !available.contains(name))
                .map(name -> DynamicTest.dynamicTest("selected group " + name, () -> {
                    throw new AssertionFailedError("'" + name + "' in " + SELECTED_PROPERTY
                            + " is not a group of this component; its groups: " + available);
                }));
        Stream<DynamicNode> run = groups.stream().map(group -> {
            String reason = skipped.get(group.name());
            if (reason == null && !selected.isEmpty() && !selected.contains(group.name())) {
                reason = "not selected by " + SELECTED_PROPERTY + "=" + String.join(",", selected);
            }
            if (reason != null) {
                String message = reason.startsWith("not selected") ? reason : "skipped: " + reason;
                return DynamicTest.dynamicTest(group.name(), () -> {
                    throw new TestAbortedException(message);
                });
            }
            return DynamicContainer.dynamicContainer(
                    group.name(), Stream.of(group).flatMap(g -> g.cases(engine).stream()));
        });
        return Stream.concat(Stream.concat(unknown, missing), run);
    }

    /** Where plugin projects keep their descriptor, relative to the module directory tests run in. */
    static final Path PROJECT_DESCRIPTOR = Path.of("src", "main", "plugin", PluginDescriptor.FILE_NAME);

    /**
     * The groups a plugin declares as not applicable in {@code skipped-testkit-groups} of its {@code
     * plugin.yaml}, by name with the reason: the descriptor next to the classes of {@code component}
     * on the class path, or else {@code src/main/plugin/plugin.yaml} of the project under test. Written
     * either as a list of {@code {group, reason}} or as a map from group to reason.
     */
    static Map<String, String> skippedGroups(Class<?> component) {
        URL descriptor = descriptor(component);
        if (descriptor == null && Files.isRegularFile(PROJECT_DESCRIPTOR)) {
            try {
                descriptor = PROJECT_DESCRIPTOR.toUri().toURL();
            } catch (IOException e) {
                throw new AssertionFailedError("Cannot read " + PROJECT_DESCRIPTOR, e);
            }
        }
        if (descriptor == null) {
            return Map.of();
        }
        String text;
        try (InputStream in = descriptor.openStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionFailedError("Cannot read " + descriptor, e);
        }
        return skippedGroups(PluginDescriptor.parse(text).orElseThrow().extra().get(SKIPPED_KEY));
    }

    static Map<String, String> skippedGroups(@Nullable Object declared) {
        Map<String, String> skipped = new LinkedHashMap<>();
        if (declared instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map && map.get("group") != null) {
                    skipped.put(String.valueOf(map.get("group")), String.valueOf(map.get("reason")));
                } else {
                    throw new AssertionFailedError(SKIPPED_KEY + " entries need a group and a reason: " + entry);
                }
            }
        } else if (declared instanceof Map<?, ?> map) {
            map.forEach((group, reason) -> skipped.put(String.valueOf(group), String.valueOf(reason)));
        } else if (declared != null) {
            throw new AssertionFailedError(SKIPPED_KEY + " must be a list or a map: " + declared);
        }
        return Collections.unmodifiableMap(skipped);
    }

    private static @Nullable URL descriptor(Class<?> component) {
        CodeSource source = component.getProtectionDomain().getCodeSource();
        ClassLoader loader = component.getClassLoader();
        if (source == null || loader == null) {
            return null;
        }
        String location = source.getLocation().toString();
        try {
            for (URL url : Collections.list(loader.getResources(PluginDescriptor.FILE_NAME))) {
                if (url.toString().startsWith(location) || url.toString().startsWith("jar:" + location)) {
                    return url;
                }
            }
        } catch (IOException e) {
            throw new AssertionFailedError("Cannot look for " + PluginDescriptor.FILE_NAME, e);
        }
        return null;
    }
}
