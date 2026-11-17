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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
