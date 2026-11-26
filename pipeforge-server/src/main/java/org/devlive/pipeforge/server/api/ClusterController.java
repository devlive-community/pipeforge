/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.devlive.pipeforge.server.run.ClusterView;
import org.devlive.pipeforge.server.run.MasterClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cluster")
@Tag(name = "cluster", description = "The master and the workers of the cluster")
public class ClusterController {

    private final MasterClient master;

    public ClusterController(MasterClient master) {
        this.master = master;
    }

    @GetMapping
    @Operation(
            operationId = "getCluster",
            summary = "Get the cluster",
            description = "The master and every registered worker with its slots, labels, resources and last"
                    + " heartbeat, and how many slots runs hold. HTTP 503 without a master or when it cannot be"
                    + " reached.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "查看集群"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "Master 以及每个已注册的 Worker：Slot、标签、资源和最近一次心跳，以及运行占用的 Slot 数。"
                                                + "没有 Master 或无法连接 Master 时返回 HTTP 503。")
                            }))
    public ClusterView cluster() {
        return master.cluster();
    }

    /** The answer to a failover: the master that gave its lease up. */
    public record Failover(String master) {}

    @PostMapping("/failover")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            operationId = "failoverMaster",
            summary = "Hand the lead over",
            description = "Asks the leading master to give its lease up: a standby master takes over within a third"
                    + " of master.leader.lease, the runs go on there from their last checkpoints, and the old leader"
                    + " stops so that its supervisor starts it again as a standby. HTTP 409 when the masters elect"
                    + " no leader (no metastore), 503 without a master or when it cannot be reached.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "切换主 Master"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "请主 Master 交出租约：备 Master 在 master.leader.lease 的三分之一内接管，运行从最近的"
                                                + " checkpoint 在那里继续，旧的主 Master 退出，由它的进程管理器重新启动为备 Master。"
                                                + "Master 不选主（没有元数据库）时返回 HTTP 409，没有 Master 或无法连接时返回 503。")
                            }))
    public Failover failover() {
        return new Failover(master.failover());
    }

    @PostMapping("/workers/{id}/drain")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            operationId = "drainWorker",
            summary = "Drain a worker",
            description = "Gives a worker no new tasks: its free slots are offered to no run, and the tasks running"
                    + " on it finish where they are. The workers of the cluster show it as draining until it is"
                    + " uncordoned or a new master leads. HTTP 404 for a worker the master does not know.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "下线 Worker"),
                                @ExtensionProperty(
                                        name = "description",
                                        value =
                                                "不再向 Worker 分配新的 Task：它的空闲 Slot 不再提供给任何运行，正在它上面运行的 Task 在原地运行到结束。"
                                                        + "在恢复调度或者换了主 Master 之前，集群的 Worker 列表把它显示为下线中。Master 不知道该 Worker 时返回 HTTP 404。")
                            }))
    public void drain(@PathVariable String id) {
        master.drain(id);
    }

    @PostMapping("/workers/{id}/uncordon")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            operationId = "uncordonWorker",
            summary = "Uncordon a worker",
            description = "Gives a drained worker new tasks again. HTTP 404 for a worker the master does not know.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "恢复调度 Worker"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "重新向已下线的 Worker 分配新的 Task。Master 不知道该 Worker 时返回 HTTP 404。")
                            }))
    public void uncordon(@PathVariable String id) {
        master.uncordon(id);
    }
}
