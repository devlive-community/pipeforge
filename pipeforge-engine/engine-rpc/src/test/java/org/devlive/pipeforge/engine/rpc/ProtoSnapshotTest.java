/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.devlive.pipeforge.engine.rpc.proto.ChainDescriptor;
import org.devlive.pipeforge.engine.rpc.proto.Header;
import org.devlive.pipeforge.engine.rpc.proto.RegisterWorkerRequest;
import org.devlive.pipeforge.engine.rpc.proto.SubmitJobRequest;
import org.devlive.pipeforge.engine.rpc.proto.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.proto.TaskStateUpdate;
import org.junit.jupiter.api.Test;

/**
 * Protocol v1 only grows. Every file, message, field, enum value, reserved range and RPC is one
 * line of pipeforge-engine/protocol-compat/rpc/descriptor-v1.txt; each line of that snapshot must
 * still be there. A removed field is accepted only when its number and name are reserved.
 * -Dpipeforge.protocol.update=true adds the new lines to the snapshot once the check passes.
 */
class ProtoSnapshotTest {

    static final Path SNAPSHOT = Path.of("..", "protocol-compat", "rpc", "descriptor-v1.txt");
    static final Path SOURCES = Path.of("src", "main", "proto", "pipeforge", "rpc", "v1");

    /** One message of every file of the protocol. */
    static List<FileDescriptor> files() {
        return List.of(
                Header.getDescriptor().getFile(),
                ChainDescriptor.getDescriptor().getFile(),
                RegisterWorkerRequest.getDescriptor().getFile(),
                TaskDeployment.getDescriptor().getFile(),
                TaskStateUpdate.getDescriptor().getFile(),
                SubmitJobRequest.getDescriptor().getFile());
    }

    /** The protocol as sorted lines. */
    static Set<String> describe(List<FileDescriptor> files) {
        Set<String> lines = new TreeSet<>();
        for (FileDescriptor file : files) {
            lines.add("file " + file.getName() + " package " + file.getPackage() + " java "
                    + file.getOptions().getJavaPackage());
            file.getMessageTypes().forEach(m -> message(m, lines));
            file.getEnumTypes().forEach(e -> enumeration(e, lines));
            for (ServiceDescriptor service : file.getServices()) {
                lines.add("service " + service.getFullName());
                for (MethodDescriptor method : service.getMethods()) {
                    lines.add("rpc " + method.getFullName() + " " + (method.isClientStreaming() ? "stream " : "")
                            + method.getInputType().getFullName() + " -> "
                            + (method.isServerStreaming() ? "stream " : "")
                            + method.getOutputType().getFullName());
                }
            }
        }
        return lines;
    }

    static void message(Descriptor message, Set<String> lines) {
        lines.add("message " + message.getFullName());
        for (FieldDescriptor field : message.getFields()) {
            String type = field.getType() == FieldDescriptor.Type.MESSAGE
                    ? field.getMessageType().getFullName()
                    : field.getType() == FieldDescriptor.Type.ENUM
                            ? field.getEnumType().getFullName()
                            : field.getType().name().toLowerCase(java.util.Locale.ROOT);
            String label = field.isRepeated() ? "repeated " : field.toProto().getProto3Optional() ? "optional " : "";
            String oneof = field.getRealContainingOneof() == null
                    ? ""
                    : " oneof " + field.getRealContainingOneof().getName();
            lines.add("field " + message.getFullName() + " " + field.getNumber() + " " + field.getName() + " " + label
                    + type + oneof);
        }
        for (var range : message.toProto().getReservedRangeList()) {
            lines.add("reserved " + message.getFullName() + " " + range.getStart() + "-" + (range.getEnd() - 1));
        }
        for (String name : message.toProto().getReservedNameList()) {
            lines.add("reserved-name " + message.getFullName() + " " + name);
        }
        message.getNestedTypes().forEach(n -> message(n, lines));
        message.getEnumTypes().forEach(e -> enumeration(e, lines));
    }

    static void enumeration(EnumDescriptor enumeration, Set<String> lines) {
        lines.add("enum " + enumeration.getFullName());
        for (EnumValueDescriptor value : enumeration.getValues()) {
            lines.add("value " + enumeration.getFullName() + " " + value.getNumber() + " " + value.getName());
        }
    }

    private static final Pattern FIELD = Pattern.compile("field (\\S+) (\\d+) (\\S+) .*");
    private static final Pattern RESERVED = Pattern.compile("reserved (\\S+) (\\d+)-(\\d+)");

    /** The lines of {@code snapshot} that {@code current} breaks: removed or changed elements. */
    static List<String> breaks(Set<String> snapshot, Set<String> current) {
        List<String> broken = new ArrayList<>();
        for (String line : snapshot) {
            if (current.contains(line) || reservedNow(line, current)) {
                continue;
            }
            broken.add(line);
        }
        return broken;
    }

    /** Whether a field that is gone has its number and its name reserved. */
    static boolean reservedNow(String line, Set<String> current) {
        Matcher field = FIELD.matcher(line);
        if (!field.matches()) {
            return false;
        }
        String message = field.group(1);
        int number = Integer.parseInt(field.group(2));
        boolean numberReserved = current.stream()
                .map(RESERVED::matcher)
                .filter(Matcher::matches)
                .anyMatch(m -> m.group(1).equals(message)
                        && Integer.parseInt(m.group(2)) <= number
                        && number <= Integer.parseInt(m.group(3)));
        return numberReserved && current.contains("reserved-name " + message + " " + field.group(3));
    }

    @Test
    void theProtocolOnlyGrows() throws IOException {
        Set<String> current = describe(files());
        Set<String> snapshot = new LinkedHashSet<>(Files.readAllLines(SNAPSHOT, StandardCharsets.UTF_8));
        snapshot.removeIf(line -> line.isBlank() || line.startsWith("#"));

        assertThat(breaks(snapshot, current))
                .as("protocol v1 elements removed or changed; add new fields, RPCs or enum values instead,"
                        + " or reserve the number and name of a removed field")
                .isEmpty();

        if (Boolean.getBoolean("pipeforge.protocol.update")) {
            List<String> text = new ArrayList<>(List.of(
                    "# Protocol v1 (pipeforge.rpc.v1): every element, one per line. Lines are only ever added;",
                    "# ProtoSnapshotTest fails when one disappears or changes. Regenerate with",
                    "# -Dpipeforge.protocol.update=true after a compatible addition."));
            Set<String> merged = new TreeSet<>(snapshot);
            merged.addAll(current);
            text.addAll(merged);
            Files.write(SNAPSHOT, text, StandardCharsets.UTF_8);
            return;
        }
        assertThat(snapshot)
                .as("new elements not yet in the snapshot; run with -Dpipeforge.protocol.update=true")
                .containsAll(current);
    }

    @Test
    void everyProtoFileIsDescribed() throws IOException {
        Set<String> described = new TreeSet<>();
        files().forEach(f -> described.add(Path.of(f.getName()).getFileName().toString()));
        Set<String> sources = new TreeSet<>();
        try (Stream<Path> files = Files.list(SOURCES)) {
            files.forEach(f -> sources.add(f.getFileName().toString()));
        }
        assertThat(described).isEqualTo(sources);
    }

    @Test
    void detectsRemovedRenumberedAndRetypedElements() {
        Set<String> snapshot = Set.of(
                "field pipeforge.rpc.v1.X 1 a string",
                "field pipeforge.rpc.v1.X 2 b int64",
                "field pipeforge.rpc.v1.X 3 c int32",
                "value pipeforge.rpc.v1.E 1 E_ONE",
                "rpc pipeforge.rpc.v1.S.Call pipeforge.rpc.v1.X -> pipeforge.rpc.v1.Ack");
        Set<String> current = Set.of(
                "field pipeforge.rpc.v1.X 1 a string", // unchanged
                "field pipeforge.rpc.v1.X 4 b int64", // renumbered
                "field pipeforge.rpc.v1.X 3 c int64", // retyped
                "field pipeforge.rpc.v1.X 5 d string", // added
                "rpc pipeforge.rpc.v1.S.Call stream pipeforge.rpc.v1.X -> pipeforge.rpc.v1.Ack");
        assertThat(breaks(snapshot, current))
                .containsExactlyInAnyOrder(
                        "field pipeforge.rpc.v1.X 2 b int64",
                        "field pipeforge.rpc.v1.X 3 c int32",
                        "value pipeforge.rpc.v1.E 1 E_ONE",
                        "rpc pipeforge.rpc.v1.S.Call pipeforge.rpc.v1.X -> pipeforge.rpc.v1.Ack");
    }

    @Test
    void acceptsARemovedFieldWhoseNumberAndNameAreReserved() {
        Set<String> snapshot = Set.of("field pipeforge.rpc.v1.X 2 b int64");
        assertThat(breaks(snapshot, Set.of("reserved pipeforge.rpc.v1.X 2-3"))).hasSize(1);
        assertThat(breaks(snapshot, Set.of("reserved pipeforge.rpc.v1.X 2-3", "reserved-name pipeforge.rpc.v1.X b")))
                .isEmpty();
    }
}
