/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.devlive.pipeforge.e2e.chaos.FaultProxy;
import org.devlive.pipeforge.e2e.chaos.Faults;
import org.devlive.pipeforge.e2e.chaos.Injection;
import org.devlive.pipeforge.e2e.chaos.StorageControl;
import org.devlive.pipeforge.e2e.chaos.Target;

/**
 * The faults of the chaos framework on a cluster of the distribution: processes are killed or
 * stopped and started again when the fault heals, links are cut, slowed down or lose packets in
 * their proxies, and the checkpoint storage of every process fails, stalls or keeps half a file as
 * its control file says.
 */
final class ClusterFaults implements Faults, AutoCloseable {

    private final Cluster cluster;
    private final Map<String, FaultProxy> links = new LinkedHashMap<>();
    private final Path control;
    private final long seed;

    /**
     * @param control the control file of the faulty checkpoint storage
     */
    ClusterFaults(Cluster cluster, Path control, long seed) {
        this.cluster = cluster;
        this.control = control;
        this.seed = seed;
    }

    /**
     * A link the cluster goes through from now on: a proxy to {@code target}; returns the address
     * the client end uses instead of the target.
     */
    String link(String name, InetSocketAddress target) throws IOException {
        FaultProxy proxy = new FaultProxy(name, Cluster.freePorts(1)[0], target, seed ^ name.hashCode());
        links.put(name, proxy);
        return "localhost:" + proxy.port();
    }

    FaultProxy proxy(String name) {
        return links.get(name);
    }

    /** The link as a target of the plan. */
    Target linkTarget(String name) {
        if (!links.containsKey(name)) {
            throw new IllegalArgumentException("no link " + name);
        }
        return Target.link(name);
    }

    @Override
    public void inject(Injection injection) throws Exception {
        switch (injection.fault()) {
            case KILL -> cluster.kill(injection.target());
            case TERM -> cluster.terminate(injection.target());
            case PARTITION -> links.get(injection.target()).partition();
            case DELAY -> links.get(injection.target()).delay(injection.param());
            case LOSS -> links.get(injection.target()).loss((int) injection.param());
            case STORAGE_FAIL -> new StorageControl(StorageControl.Mode.FAIL, 0).write(control);
            case STORAGE_SLOW -> new StorageControl(StorageControl.Mode.SLOW, injection.param()).write(control);
            case STORAGE_TRUNCATE -> new StorageControl(StorageControl.Mode.TRUNCATE, 0).write(control);
        }
    }

    @Override
    public void heal(Injection injection) throws Exception {
        switch (injection.fault().kind()) {
            case PROCESS -> cluster.restart(injection.target());
            case LINK -> links.get(injection.target()).heal();
            case STORAGE -> StorageControl.OK.write(control);
        }
    }

    @Override
    public void close() {
        links.values().forEach(FaultProxy::close);
    }
}
