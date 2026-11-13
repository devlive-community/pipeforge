/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.devlive.pipeforge.api.factory.Factory;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.Component;
import org.devlive.pipeforge.core.plan.OptionOrigin;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.core.plan.PlannedOption;
import org.junit.jupiter.api.Test;

/**
 * Task descriptions: the chain a worker rebuilds from the bytes is the chain the master planned, the
 * sensitive values travel apart, damage is detected, and the golden file of format 1 stays
 * readable.
 */
public class ChainCodecTest {

    /** The golden description of format 1; written once when missing, then only ever read. */
    static final Path GOLDEN = Path.of("..", "protocol-compat", "chain", "v1", "sample.bin");

    public static final RunContext RUN = new RunContext(
            "job-1",
            "run-7",
            Map.of("date", "2026-11-13", "region", "east"),
            Instant.parse("2026-11-13T08:00:00Z"),
            Instant.parse("2026-11-13T00:00:00Z"),
            "batch",
            "at-least-once");

    public record Fake(NodeKind kind, String type, String plugin) implements Component {
        @Override
        public Factory factory() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClassLoader classLoader() {
            return ChainCodecTest.class.getClassLoader();
        }

        @Override
        public <T> T call(String operation, Callable<T> action) {
            throw new UnsupportedOperationException();
        }
    }

    static final CatalogTable USERS = new CatalogTable(
            new TableId(null, "crm", null, "users"),
            List.of(
                    new Column("id", DataTypes.parse("INT64 NOT NULL"), "the key", null, Map.of()),
                    new Column("name", DataTypes.parse("STRING(12)"), null, "anonymous", Map.of("collation", "utf8")),
                    new Column("amount", DataTypes.parse("DECIMAL(10, 2)"), null, 0, Map.of()),
                    new Column(
                            "tags",
                            DataTypes.parse("MAP<STRING, ARRAY<ROW<k STRING, v TIMESTAMP_LTZ(6)>>>"),
                            null,
                            null,
                            Map.of())),
            List.of("id"),
            List.of(),
            Map.of("engine", "InnoDB"),
            "customers");

    static final CatalogTable MAPPED = new CatalogTable(
            new TableId("cat", "crm", "public", "users_v2"),
            List.of(new Column("user_id", DataTypes.parse("INT64 NOT NULL"), null, null, Map.of())),
            List.of("user_id"),
            List.of("user_id"),
            Map.of(),
            null);

    static PlannedNode node(
            String id,
            NodeKind kind,
            String type,
            String plugin,
            Map<String, ?> config,
            List<PlannedOption> options,
            List<CatalogTable> in,
            List<CatalogTable> out) {
        return new PlannedNode(
                id,
                kind,
                type,
                plugin,
                List.of(),
                false,
                2,
                kind == NodeKind.SOURCE ? Boundedness.BOUNDED : null,
                ReadonlyConfig.of(config),
                options,
                in,
                out,
                new Fake(kind, type, plugin));
    }

    public static ChainDescriptor sample() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("url", "jdbc:mysql://db:3306/crm");
        source.put("password", "s3cr3t!");
        source.put("rows", 1000);
        source.put("ratio", 0.25);
        source.put("columns", List.of("id INT64 NOT NULL", "name STRING(12)"));
        source.put("jdbc.properties", Map.of("useSSL", false));
        source.put("nothing", null);
        Map<String, Object> mapper = new LinkedHashMap<>();
        mapper.put("mapping", List.of(Map.of("from", "id", "to", "user_id"), Map.of("expr", "LOWER(name)")));
        return new ChainDescriptor(
                "src",
                new JobContext("users-demo", 2, ZoneId.of("Asia/Shanghai")),
                List.of(
                        node(
                                "src",
                                NodeKind.SOURCE,
                                "mysql",
                                "connector-mysql@1.2.0",
                                source,
                                List.of(
                                        new PlannedOption("url", "jdbc:mysql://db:3306/crm", OptionOrigin.NODE, false),
                                        new PlannedOption("password", "******", OptionOrigin.NODE, true),
                                        new PlannedOption("fetch-size", 1024, OptionOrigin.DEFAULT, false)),
                                List.of(),
                                List.of(USERS)),
                        node(
                                "tidy",
                                NodeKind.TRANSFORM,
                                "field-mapper",
                                "builtin",
                                mapper,
                                List.of(),
                                List.of(USERS),
                                List.of(MAPPED)),
                        node(
                                "out",
                                NodeKind.SINK,
                                "console",
                                "connector-console@0.1.0",
                                Map.of(),
                                List.of(),
                                List.of(MAPPED),
                                List.of())));
    }

    static ChainCodec.Decoded decode(ChainCodec.Encoded encoded) {
        return ChainCodec.decode(encoded.descriptor(), encoded.secrets(), Fake::new);
    }

    @Test
    void theWorkerRebuildsThePlannedChain() {
        ChainDescriptor planned = sample();
        ChainCodec.Decoded decoded = decode(ChainCodec.encode(planned, RUN));
        ChainDescriptor chain = decoded.chain();

        assertThat(decoded.run()).isEqualTo(RUN);
        assertThat(chain.vertexId()).isEqualTo("src");
        assertThat(chain.job()).isEqualTo(planned.job());
        assertThat(chain.nodes()).hasSize(3);
        for (int i = 0; i < 3; i++) {
            PlannedNode before = planned.nodes().get(i);
            PlannedNode after = chain.nodes().get(i);
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.kind()).isEqualTo(before.kind());
            assertThat(after.type()).isEqualTo(before.type());
            assertThat(after.plugin()).isEqualTo(before.plugin());
            assertThat(after.parallelism()).isEqualTo(before.parallelism());
            assertThat(after.boundedness()).isEqualTo(before.boundedness());
            assertThat(after.config().toMap()).isEqualTo(before.config().toMap());
            assertThat(after.inputTables()).isEqualTo(before.inputTables());
            assertThat(after.outputTables()).isEqualTo(before.outputTables());
            assertThat(after.component()).isEqualTo(new Fake(before.kind(), before.type(), before.plugin()));
        }
        // inside a chain every node reads from the one before it
        assertThat(chain.nodes().get(0).inputs()).isEmpty();
        assertThat(chain.nodes().get(2).inputs()).containsExactly("tidy");
        // the worker reads options through the plugin's own option declarations
        assertThat(chain.source().config().nativeConfig("jdbc.properties")).containsEntry("useSSL", "false");
    }

    @Test
    void sensitiveValuesTravelApart() {
        ChainCodec.Encoded encoded = ChainCodec.encode(sample(), RUN);

        assertThat(encoded.secrets()).containsExactly(Map.entry("src.password", "s3cr3t!"));
        String bytes = new String(encoded.descriptor(), StandardCharsets.ISO_8859_1);
        assertThat(bytes).doesNotContain("s3cr3t!").contains("{\"$secret\":\"src.password\"}");
        assertThat(decode(encoded).chain().source().config().raw("password")).isEqualTo("s3cr3t!");

        assertThatThrownBy(() -> ChainCodec.decode(encoded.descriptor(), Map.of(), Fake::new))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The sensitive option 'password' of node 'src' was not delivered");
    }

    @Test
    void theSameChainGivesTheSameBytes() {
        assertThat(ChainCodec.encode(sample(), RUN).descriptor())
                .isEqualTo(ChainCodec.encode(sample(), RUN).descriptor());
    }

    @Test
    void eachTableIsListedOnce() {
        var description = ChainCodec.unwrap(ChainCodec.encode(sample(), RUN).descriptor());
        assertThat(description.getTablesList()).extracting(t -> t.getTable()).containsExactly("users", "users_v2");
        assertThat(description.getNodes(1).getInputTablesList()).containsExactly(0);
        assertThat(description.getNodes(1).getOutputTablesList()).containsExactly(1);
        assertThat(description.getNodes(0).getPlugin().getId()).isEqualTo("connector-mysql");
        assertThat(description.getNodes(0).getPlugin().getVersion()).isEqualTo("1.2.0");
        assertThat(description.getNodes(1).getPlugin().getId()).isEqualTo("builtin");
    }

    @Test
    void optionsThatAreNotJsonAreRefused() {
        ChainDescriptor planned = sample();
        List<PlannedNode> nodes = new ArrayList<>(planned.nodes());
        PlannedNode source = nodes.get(0);
        nodes.set(
                0,
                node(
                        "src",
                        NodeKind.SOURCE,
                        "mysql",
                        source.plugin(),
                        Map.of("since", new Object()),
                        List.of(),
                        List.of(),
                        List.of(USERS)));
        ChainDescriptor chain = new ChainDescriptor("src", planned.job(), nodes);
        assertThatThrownBy(() -> ChainCodec.encode(chain, RUN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("The options of node 'src' cannot be written as JSON");
    }

    @Test
    void damageIsDetected() {
        byte[] good = ChainCodec.encode(sample(), RUN).descriptor();

        byte[] magic = good.clone();
        magic[0] = 'X';
        assertThatThrownBy(() -> ChainCodec.unwrap(magic)).hasMessageStartingWith("Not a task description");

        byte[] newer = good.clone();
        ByteBuffer.wrap(newer).putInt(4, 2);
        assertThatThrownBy(() -> ChainCodec.unwrap(newer))
                .hasMessage("Task description format 2 is not supported; this engine reads 1 to 1");

        byte[] flipped = good.clone();
        flipped[20] ^= 0x01;
        assertThatThrownBy(() -> ChainCodec.unwrap(flipped))
                .hasMessage("Task description is damaged: checksum mismatch");

        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 1);
        assertThatThrownBy(() -> ChainCodec.unwrap(truncated)).hasMessageContaining("does not match");

        assertThatThrownBy(() -> ChainCodec.unwrap(new byte[8])).hasMessageContaining("at least 16 bytes");
    }

    @Test
    void readsTheGoldenDescriptionOfFormatOne() throws IOException {
        byte[] current = ChainCodec.encode(sample(), RUN).descriptor();
        if (!Files.exists(GOLDEN)) {
            Files.createDirectories(GOLDEN.getParent());
            Files.write(GOLDEN, current);
        }
        byte[] golden = Files.readAllBytes(GOLDEN);
        // the description of format 1 still reads to the same chain…
        ChainCodec.Decoded decoded = ChainCodec.decode(golden, Map.of("src.password", "s3cr3t!"), Fake::new);
        assertThat(decoded.run()).isEqualTo(RUN);
        assertThat(decoded.chain().nodes())
                .extracting(PlannedNode::id, n -> n.config().toMap())
                .containsExactlyElementsOf(sample().nodes().stream()
                        .map(n -> org.assertj.core.groups.Tuple.tuple(
                                n.id(), n.config().toMap()))
                        .toList());
        assertThat(decoded.chain().source().outputTables()).containsExactly(USERS);
        // …and this build still writes it byte for byte
        assertThat(current).isEqualTo(golden);
    }
}
