/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.proto.Header;

/**
 * The epochs a worker knows: the largest leader epoch of the master, and the largest job epoch of
 * every run. Requests with a smaller one come from a master or job master that was replaced and are
 * refused, so that only the current one controls the tasks.
 */
public final class Fencing {

    private long leader;
    private final Map<String, Long> jobs = new ConcurrentHashMap<>();

    /**
     * Accepts the epochs of a request about {@code run} and remembers larger ones.
     *
     * @throws PipeForgeException {@link ErrorCode#RPC_STALE_EPOCH} if an epoch is smaller than known
     */
    public synchronized void check(Header header, String run) {
        if (header.getLeaderEpoch() < leader) {
            throw stale("leader", header.getLeaderEpoch(), leader);
        }
        long job = jobs.getOrDefault(run, 0L);
        if (header.getJobEpoch() < job) {
            throw stale("job", header.getJobEpoch(), job);
        }
        leader = header.getLeaderEpoch();
        jobs.put(run, header.getJobEpoch());
    }

    /** Forgets a run that ended. */
    public void forget(String run) {
        jobs.remove(run);
    }

    private static PipeForgeException stale(String epoch, long received, long known) {
        return PipeForgeException.of(ErrorCode.RPC_STALE_EPOCH)
                .param("epoch", epoch)
                .param("received", received)
                .param("known", known)
                .build();
    }
}
