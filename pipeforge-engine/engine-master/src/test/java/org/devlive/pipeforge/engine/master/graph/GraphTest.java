/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.devlive.pipeforge.api.factory.EngineKind;
import org.devlive.pipeforge.api.factory.Factory;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.core.job.Delivery;
import org.devlive.pipeforge.core.job.JobMode;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.Component;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.master.graph.ExecutionGraph.Task;
import org.devlive.pipeforge.engine.master.graph.JobGraph.Edge;
import org.devlive.pipeforge.engine.rpc.Partitioning;
import org.junit.jupiter.api.Test;

/** Chaining, partitioning, expansion, regions and slots of job graphs. */
class GraphTest {

    static final Component NONE = new Component() {
        @Override
        public Factory factory() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String plugin() {
            return "builtin";
        }

        @Override
        public ClassLoader classLoader() {
            return GraphTest.class.getClassLoader();
        }

        @Override
        public <T> T call(String operation, Callable<T> action) {
            throw new UnsupportedOperationException();
        }
    };

    static CatalogTable table(String name, String... key) {
        return new CatalogTable(
                new TableId(null, "db", null, name),
                List.of(new Column("id", DataTypes.parse("INT64 NOT NULL"), null, null, Map.of())),
                List.of(key),
                List.of(),
                Map.of(),
                null);
    }

    static PlannedNode source(String id, int parallelism, Boundedness boundedness, CatalogTable... tables) {
        return new PlannedNode(
                id,
                NodeKind.SOURCE,
                "fake",
                "builtin",
                List.of(),
                false,
                parallelism,
                boundedness,
                ReadonlyConfig.empty(),
                List.of(),
                List.of(),
                List.of(tables),
                NONE);
    }

    static PlannedNode node(String id, NodeKind kind, int parallelism, List<CatalogTable> tables, String... inputs) {
        return new PlannedNode(
                id,
                kind,
                kind == NodeKind.SINK ? "console" : "map",
                "builtin",
                Arrays.asList(inputs),
                false,
                parallelism,
                null,
                ReadonlyConfig.empty(),
                List.of(),
                tables,
                kind == NodeKind.SINK ? List.of() : tables,
                NONE);
    }

    static JobPlan plan(PlannedNode... nodes) {
        return new JobPlan(
                "job",
                null,
                EngineKind.NATIVE,
                JobMode.BATCH,
                true,
                Delivery.AT_LEAST_ONCE,
                1,
                ZoneId.of("UTC"),
                Map.of(),
                List.of(nodes),
                List.of());
    }

    static List<String> ids(JobGraph.Vertex vertex) {
        return vertex.nodes().stream().map(PlannedNode::id).toList();
    }

    @Test
    void aStraightJobOfOneParallelismIsOneChainWithIndependentRegions() {
        // native engine design, section 4, first example
        CatalogTable orders = table("orders", "id");
        JobGraph graph = JobGraph.of(plan(
                source("src", 4, Boundedness.BOUNDED, orders),
                node("map", NodeKind.TRANSFORM, 4, List.of(orders), "src"),
                node("out", NodeKind.SINK, 4, List.of(orders), "map")));

        assertThat(graph.vertices()).singleElement().satisfies(v -> {
            assertThat(v.id()).isEqualTo("src");
            assertThat(ids(v)).containsExactly("src", "map", "out");
            assertThat(v.parallelism()).isEqualTo(4);
        });
        assertThat(graph.edges()).isEmpty();

        ExecutionGraph execution = ExecutionGraph.of(graph);
        assertThat(execution.tasks()).hasSize(4);
        assertThat(execution.regions()).hasSize(4).allSatisfy(region -> assertThat(region)
                .hasSize(1));
        assertThat(execution.slots()).isEqualTo(4);
    }

    @Test
    void changesIntoAParallelSinkAreHashedByKey() {
        // native engine design, section 4, second example: a change data capture source
        CatalogTable orders = table("orders", "id");
        JobGraph graph = JobGraph.of(plan(
                source("cdc", 1, Boundedness.UNBOUNDED, orders),
                node("route", NodeKind.TRANSFORM, 1, List.of(orders), "cdc"),
                node("doris", NodeKind.SINK, 4, List.of(orders), "route")));

        assertThat(graph.vertices())
                .extracting(GraphTest::ids)
                .containsExactly(List.of("cdc", "route"), List.of("doris"));
        assertThat(graph.edges()).containsExactly(new Edge("cdc", "doris", Partitioning.hash(List.of("id")), 0));

        ExecutionGraph execution = ExecutionGraph.of(graph);
        assertThat(execution.tasks()).hasSize(5);
        assertThat(execution.connections()).hasSize(4);
        assertThat(execution.regions()).singleElement().satisfies(r -> assertThat(r)
                .hasSize(5));
        assertThat(execution.slots()).isEqualTo(4);
        assertThat(execution.slotSharing().get(0)).containsExactly(new Task("cdc", 0, 0), new Task("doris", 0, 0));
        assertThat(execution.slotSharing().get(3)).containsExactly(new Task("doris", 3, 3));
    }

    @Test
    void severalTablesAreHashedByTableAndPrimaryKey() {
        CatalogTable a = table("a", "id");
        CatalogTable b = table("b", "id");
        JobGraph graph = JobGraph.of(plan(
                source("cdc", 1, Boundedness.UNBOUNDED, a, b), node("out", NodeKind.SINK, 2, List.of(a, b), "cdc")));
        assertThat(graph.edges()).singleElement().satisfies(e -> {
            assertThat(e.partitioning()).isEqualTo(Partitioning.hash(List.of()));
            assertThat(e.partitioning()).hasToString("hash(table, primary key)");
        });
    }

    @Test
    void anotherParallelismRebalancesRowsOfABoundedSource() {
        CatalogTable t = table("t");
        JobGraph graph = JobGraph.of(
                plan(source("src", 2, Boundedness.BOUNDED, t), node("out", NodeKind.SINK, 3, List.of(t), "src")));
        assertThat(graph.edges()).containsExactly(new Edge("src", "out", Partitioning.REBALANCE, 0));
        ExecutionGraph execution = ExecutionGraph.of(graph);
        assertThat(execution.connections()).hasSize(6);
        assertThat(execution.regions()).hasSize(1);
        assertThat(execution.slots()).isEqualTo(3);
        assertThat(Partitioning.REBALANCE).hasToString("rebalance");
    }

    @Test
    void aSinkOfParallelismOneNeedsNoHash() {
        CatalogTable t = table("t", "id");
        JobGraph graph = JobGraph.of(
                plan(source("cdc", 1, Boundedness.UNBOUNDED, t), node("out", NodeKind.SINK, 1, List.of(t), "cdc")));
        assertThat(graph.vertices()).singleElement().satisfies(v -> assertThat(ids(v))
                .containsExactly("cdc", "out"));
    }

    @Test
    void aNodeWithTwoConsumersEndsItsChain() {
        CatalogTable t = table("t");
        JobGraph graph = JobGraph.of(plan(
                source("src", 2, Boundedness.BOUNDED, t),
                node("a", NodeKind.SINK, 2, List.of(t), "src"),
                node("b", NodeKind.SINK, 2, List.of(t), "src")));
        assertThat(graph.vertices()).extracting(JobGraph.Vertex::id).containsExactly("src", "a", "b");
        assertThat(graph.outputs("src")).extracting(Edge::target).containsExactly("a", "b");
        assertThat(graph.outputs("src")).extracting(Edge::partitioning).containsOnly(Partitioning.FORWARD);

        ExecutionGraph execution = ExecutionGraph.of(graph);
        // forward edges connect subtask i only to subtask i
        assertThat(execution.regions()).hasSize(2);
        assertThat(execution.regionOf(new Task("a", 1, 1)))
                .containsExactly(new Task("src", 1, 1), new Task("a", 1, 1), new Task("b", 1, 1));
        assertThat(execution.tasksOf("a")).containsExactly(new Task("a", 0, 0), new Task("a", 1, 1));
    }

    @Test
    void aNodeWithTwoInputsStartsAVertex() {
        CatalogTable t = table("t");
        JobGraph graph = JobGraph.of(plan(
                source("left", 1, Boundedness.BOUNDED, t),
                source("right", 1, Boundedness.BOUNDED, t),
                node("union", NodeKind.TRANSFORM, 1, List.of(t), "left", "right"),
                node("out", NodeKind.SINK, 1, List.of(t), "union")));
        assertThat(graph.vertices())
                .extracting(GraphTest::ids)
                .containsExactly(List.of("left"), List.of("right"), List.of("union", "out"));
        assertThat(graph.inputs("union"))
                .containsExactly(
                        new Edge("left", "union", Partitioning.FORWARD, 0),
                        new Edge("right", "union", Partitioning.FORWARD, 1));
        assertThat(graph.vertex("union"))
                .hasValueSatisfying(v -> assertThat(v.tail().id()).isEqualTo("out"));
        assertThat(ExecutionGraph.of(graph).regions()).singleElement().satisfies(r -> assertThat(r)
                .hasSize(3));
    }

    @Test
    void changesFollowTheirSourcesThroughTransforms() {
        CatalogTable t = table("t");
        JobPlan plan = plan(
                source("bounded", 1, Boundedness.BOUNDED, t),
                source("stream", 1, Boundedness.UNBOUNDED, t),
                node("a", NodeKind.TRANSFORM, 1, List.of(t), "bounded"),
                node("b", NodeKind.TRANSFORM, 1, List.of(t), "a", "stream"),
                node("out", NodeKind.SINK, 1, List.of(t), "b"));
        var changes = JobGraph.mayChange(plan);
        assertThat(plan.nodes()).filteredOn(changes).extracting(PlannedNode::id).containsExactly("stream", "b", "out");
    }

    @Test
    void partitioningsKeepTheirRules() {
        assertThat(Partitioning.hash(List.of("id", "day"))).hasToString("hash(id, day)");
        assertThat(Partitioning.FORWARD.allToAll()).isFalse();
        assertThat(new Partitioning(Partitioning.Kind.BROADCAST, List.of()).allToAll())
                .isTrue();
        assertThatThrownBy(() -> new Partitioning(Partitioning.Kind.TABLE, List.of("id")))
                .isInstanceOf(IllegalArgumentException.class);
        JobGraph mismatch = new JobGraph(
                "job",
                List.of(
                        new JobGraph.Vertex("a", List.of(source("a", 1, Boundedness.BOUNDED)), 1),
                        new JobGraph.Vertex("b", List.of(node("b", NodeKind.SINK, 2, List.of(), "a")), 2)),
                List.of(new Edge("a", "b", Partitioning.FORWARD, 0)));
        assertThatThrownBy(() -> ExecutionGraph.of(mismatch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same parallelism");
    }
}
