/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Every setting of the master and worker processes; the configuration reference is generated from it. */
public final class Settings {

    private static final Map<String, Setting> ALL = new LinkedHashMap<>();

    // conf/pipeforge.yaml: the whole cluster
    public static final Setting CLUSTER_NAME = define(
            "cluster.name",
            "pipeforge",
            Setting.Type.STRING,
            "default",
            false,
            "The name of the cluster, shown in the logs.",
            "集群名，显示在日志中。");
    public static final Setting CLUSTER_TOKEN = define(
            "cluster.token",
            "pipeforge",
            Setting.Type.STRING,
            null,
            true,
            "The shared token every process presents to the others; processes with another token are refused. Use a long random value and keep it secret.",
            "每个进程向其他进程出示的共享令牌；令牌不同的进程会被拒绝。请使用较长的随机值并妥善保密。");
    public static final Setting TLS_ENABLED = define(
            "security.tls.enabled",
            "pipeforge",
            Setting.Type.BOOLEAN,
            "false",
            false,
            "Encrypt the RPC between the processes with TLS; the key store and the trust store are then required.",
            "用 TLS 加密进程之间的 RPC；开启后必须提供密钥库和信任库。");
    public static final Setting TLS_KEY_STORE = define(
            "security.tls.key-store",
            "pipeforge",
            Setting.Type.STRING,
            null,
            false,
            "The PKCS#12 key store with the certificate this process presents.",
            "本进程出示的证书所在的 PKCS#12 密钥库。");
    public static final Setting TLS_KEY_STORE_PASSWORD = define(
            "security.tls.key-store-password",
            "pipeforge",
            Setting.Type.STRING,
            "",
            false,
            "The password of the key store.",
            "密钥库的密码。");
    public static final Setting TLS_TRUST_STORE = define(
            "security.tls.trust-store",
            "pipeforge",
            Setting.Type.STRING,
            null,
            false,
            "The PKCS#12 trust store with the certificates of the other processes.",
            "其他进程的证书所在的 PKCS#12 信任库。");
    public static final Setting TLS_TRUST_STORE_PASSWORD = define(
            "security.tls.trust-store-password",
            "pipeforge",
            Setting.Type.STRING,
            "",
            false,
            "The password of the trust store.",
            "信任库的密码。");
    public static final Setting PLUGIN_DIRS = define(
            "plugins.dirs",
            "pipeforge",
            Setting.Type.LIST,
            "plugins",
            false,
            "The directories with plugin packages, relative to the installation directory. The master plans jobs with them and serves them to the workers.",
            "存放插件包的目录，相对于安装目录。Master 用它们规划作业，并把它们提供给 Worker。");

    // conf/master.yaml
    public static final Setting MASTER_RPC_PORT = define(
            "master.rpc.port",
            "master",
            Setting.Type.INT,
            "6123",
            false,
            "The port of the master's RPC: workers register here, tasks report here, jobs are submitted here.",
            "Master 的 RPC 端口：Worker 在此注册，Task 在此报告，作业在此提交。");
    public static final Setting MASTER_DIR = define(
            "master.dir",
            "master",
            Setting.Type.STRING,
            "data/master",
            false,
            "The working directory of the master: extracted plugins and the packages it serves to workers.",
            "Master 的工作目录：解压后的插件以及提供给 Worker 的插件包。");
    public static final Setting HEARTBEAT_INTERVAL = define(
            "master.heartbeat.interval",
            "master",
            Setting.Type.DURATION,
            "5s",
            false,
            "How often workers send a heartbeat; the master checks for lost workers as often.",
            "Worker 发送心跳的频率；Master 也按这个频率检查失联的 Worker。");
    public static final Setting HEARTBEAT_TIMEOUT = define(
            "master.heartbeat.timeout",
            "master",
            Setting.Type.DURATION,
            "30s",
            false,
            "After how long without a heartbeat a worker counts as lost and its tasks fail.",
            "多久没有心跳就认为 Worker 失联，它上面的 Task 失败。");
    public static final Setting SCHEDULER_STRATEGY = define(
            "scheduler.strategy",
            "master",
            Setting.Type.STRING,
            "spread",
            false,
            "spread puts the slots of a job on as many workers as possible, pack on as few as possible.",
            "spread 把作业的 Slot 分散到尽量多的 Worker 上，pack 集中到尽量少的 Worker 上。");
    public static final Setting SLOT_REQUEST_TIMEOUT = define(
            "scheduler.slot-request-timeout",
            "master",
            Setting.Type.DURATION,
            "5min",
            false,
            "How long a job waits for free slots before it fails with PF-RES-0001.",
            "作业等待空闲 Slot 的最长时间，超过后以 PF-RES-0001 失败。");

    // conf/worker.yaml
    public static final Setting WORKER_MASTER = define(
            "worker.master.address",
            "worker",
            Setting.Type.ADDRESS,
            "localhost:6123",
            false,
            "Where the worker finds the master.",
            "Worker 连接 Master 的地址。");
    public static final Setting WORKER_HOST = derive(
            "worker.host",
            "worker",
            Setting.Type.STRING,
            "the host name of this machine",
            "本机的主机名",
            "The host name or address other processes reach this worker at.",
            "其他进程访问本 Worker 时使用的主机名或地址。");
    public static final Setting WORKER_RPC_PORT = define(
            "worker.rpc.port",
            "worker",
            Setting.Type.INT,
            "6125",
            false,
            "The port the master deploys and cancels tasks on.",
            "Master 部署和取消 Task 使用的端口。");
    public static final Setting WORKER_DATA_PORT = define(
            "worker.data.port",
            "worker",
            Setting.Type.INT,
            "6124",
            false,
            "The port tasks on other workers read the rows of this worker's tasks from.",
            "其他 Worker 上的 Task 读取本 Worker 上 Task 数据的端口。");
    public static final Setting WORKER_SLOTS = derive(
            "worker.slots",
            "worker",
            Setting.Type.INT,
            "the number of processors",
            "CPU 核数",
            "How many tasks the worker runs at the same time.",
            "Worker 同时运行的 Task 数量。");
    public static final Setting WORKER_LABELS = define(
            "worker.labels",
            "worker",
            Setting.Type.MAP,
            "",
            false,
            "Key and value pairs jobs can require, such as zone=east or net=db-zone.",
            "作业可以要求的键值对，例如 zone=east 或 net=db-zone。");
    public static final Setting WORKER_RESOURCE_GROUP = define(
            "worker.resource-group",
            "worker",
            Setting.Type.STRING,
            "default",
            false,
            "The resource group the worker belongs to.",
            "Worker 所属的资源组。");
    public static final Setting WORKER_DIR = define(
            "worker.dir",
            "worker",
            Setting.Type.STRING,
            "data/worker",
            false,
            "The working directory of the worker: the plugin packages it downloaded from the master.",
            "Worker 的工作目录：从 Master 下载的插件包。");
    public static final Setting WORKER_LOG_DIR = define(
            "worker.log.dir",
            "worker",
            Setting.Type.STRING,
            "logs/tasks",
            false,
            "Where tasks write their own logs, relative to the installation directory: <job>/<run>/<vertex>-<subtask>-<attempt>.log.",
            "Task 写自己日志的目录，相对于安装目录：<job>/<run>/<vertex>-<subtask>-<attempt>.log。");
    public static final Setting WORKER_LOG_MAX_FILE_SIZE = define(
            "worker.log.max-file-size",
            "worker",
            Setting.Type.SIZE,
            "64MB",
            false,
            "The size at which a task log file is rolled; offsets in the log stay the same.",
            "Task 日志文件滚动的大小；日志中的偏移量保持不变。");
    public static final Setting WORKER_LOG_MAX_FILES = define(
            "worker.log.max-files",
            "worker",
            Setting.Type.INT,
            "5",
            false,
            "How many files of one task log are kept, the current one included; older ones are deleted.",
            "每个 Task 日志保留的文件数，包括当前文件；更早的文件会被删除。");
    public static final Setting WORKER_LOG_RETENTION = define(
            "worker.log.retention",
            "worker",
            Setting.Type.DURATION,
            "7d",
            false,
            "How long the task logs of a run are kept after their last write; the worker checks every hour.",
            "一次运行的 Task 日志在最后一次写入后保留多久；Worker 每小时检查一次。");
    public static final Setting WORKER_RETRY = define(
            "worker.registration-retry",
            "worker",
            Setting.Type.DURATION,
            "1s",
            false,
            "How often a worker that is not connected tries to register.",
            "未连接的 Worker 尝试注册的频率。");
    public static final Setting NETWORK_MEMORY = derive(
            "worker.memory.network",
            "worker",
            Setting.Type.SIZE,
            "10% of the maximum heap, at least 64MB and at most 1GB",
            "最大堆的 10%，不少于 64MB、不超过 1GB",
            "The memory for network buffers of the data exchange.",
            "数据交换使用的网络缓冲内存。");
    public static final Setting BUFFER_SIZE = define(
            "network.buffer-size",
            "worker",
            Setting.Type.SIZE,
            "32KB",
            false,
            "The size of a network buffer.",
            "网络缓冲的大小。");
    public static final Setting BUFFERS_PER_CHANNEL = define(
            "network.buffers-per-channel",
            "worker",
            Setting.Type.INT,
            "2",
            false,
            "The buffers each input channel owns.",
            "每个输入通道的专属缓冲数。");
    public static final Setting FLOATING_BUFFERS = define(
            "network.floating-buffers-per-gate",
            "worker",
            Setting.Type.INT,
            "8",
            false,
            "The buffers an input shares among its channels when producers report a backlog.",
            "生产者报告积压时，一个输入在各通道之间共享的缓冲数。");
    public static final Setting BUFFER_TIMEOUT = define(
            "network.buffer-timeout",
            "worker",
            Setting.Type.DURATION,
            "100ms",
            false,
            "After how long a buffer that is not full is sent anyway.",
            "未满的缓冲在多久之后也会被发送。");
    public static final Setting MAX_ROW_BYTES = define(
            "task.max-row-bytes",
            "worker",
            Setting.Type.SIZE,
            "64MB",
            false,
            "The largest row a task may exchange with another task.",
            "Task 之间交换的单行大小上限。");

    private Settings() {}

    private static Setting define(
            String key,
            String file,
            Setting.Type type,
            @Nullable String value,
            boolean required,
            String en,
            String zh) {
        return add(new Setting(key, file, type, value, null, required, Map.of("en-US", en, "zh-CN", zh)));
    }

    private static Setting derive(
            String key, String file, Setting.Type type, String derivedEn, String derivedZh, String en, String zh) {
        return add(new Setting(
                key,
                file,
                type,
                null,
                Map.of("en-US", derivedEn, "zh-CN", derivedZh),
                false,
                Map.of("en-US", en, "zh-CN", zh)));
    }

    private static Setting add(Setting setting) {
        if (ALL.putIfAbsent(setting.key(), setting) != null) {
            throw new IllegalStateException("setting " + setting.key() + " is defined twice");
        }
        return setting;
    }

    /** Every setting, in the order of the reference. */
    public static List<Setting> all() {
        return Collections.unmodifiableList(new ArrayList<>(ALL.values()));
    }

    public static Optional<Setting> find(String key) {
        return Optional.ofNullable(ALL.get(key));
    }
}
