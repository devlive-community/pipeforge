/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.SlotRequest;
import org.jspecify.annotations.Nullable;

/**
 * The slots of a worker and who reserved them. A job master reserves a slot before it deploys a
 * task into it; a reservation that no task has used within {@link #UNUSED_TIMEOUT} is released, so
 * that a job master that failed between the two does not hold slots forever.
 */
public final class SlotTable {

    /** How long a reservation may stay without a task. */
    public static final Duration UNUSED_TIMEOUT = Duration.ofSeconds(60);

    /** A slot as the worker reports it to the master. */
    public record Slot(int index, @Nullable String jobId, @Nullable String allocationId) {
        public boolean allocated() {
            return allocationId != null;
        }
    }

    private static final class Reservation {
        final SlotRequest request;
        final long since;
        boolean used;

        Reservation(SlotRequest request, long since) {
            this.request = request;
            this.since = since;
        }
    }

    private final String worker;
    private final Reservation[] slots;
    private final LongSupplier nanoTime;

    /**
     * @param worker how errors name the worker, e.g. its address
     */
    public SlotTable(String worker, int slots) {
        this(worker, slots, System::nanoTime);
    }

    SlotTable(String worker, int slots, LongSupplier nanoTime) {
        if (slots < 1) {
            throw new IllegalArgumentException("worker.slots must be at least 1, not " + slots);
        }
        this.worker = worker;
        this.slots = new Reservation[slots];
        this.nanoTime = nanoTime;
    }

    public int size() {
        return slots.length;
    }

    /**
     * Reserves a slot; reserving again with the same allocation succeeds.
     *
     * @throws PipeForgeException {@link ErrorCode#SLOT_TAKEN} when another allocation holds it
     * @throws IllegalArgumentException when the worker has no such slot
     */
    public synchronized void reserve(SlotRequest request) {
        if (request.slot() >= slots.length) {
            throw new IllegalArgumentException(
                    "Worker " + worker + " has " + slots.length + " slot(s), not slot " + request.slot());
        }
        Reservation current = slots[request.slot()];
        if (current != null) {
            if (current.request.allocationId().equals(request.allocationId())) {
                return;
            }
            throw PipeForgeException.of(ErrorCode.SLOT_TAKEN)
                    .param("worker", worker)
                    .param("slot", request.slot())
                    .param("holder", current.request.allocationId())
                    .build();
        }
        slots[request.slot()] = new Reservation(request, nanoTime.getAsLong());
    }

    /**
     * Marks the slot of an allocation as used by a task, so that it is no longer released for
     * being unused.
     *
     * @throws IllegalStateException when no slot is reserved for the allocation
     */
    public synchronized void use(String allocationId) {
        Reservation reservation = find(allocationId);
        if (reservation == null) {
            throw new IllegalStateException(
                    "No slot of worker " + worker + " is reserved for allocation " + allocationId);
        }
        reservation.used = true;
    }

    /** Releases the slot of an allocation; returns whether it held one. */
    public synchronized boolean free(String allocationId) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && slots[i].request.allocationId().equals(allocationId)) {
                slots[i] = null;
                return true;
            }
        }
        return false;
    }

    /** Releases the reservations that no task used within the timeout; returns them. */
    public synchronized List<SlotRequest> expire() {
        long now = nanoTime.getAsLong();
        List<SlotRequest> released = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            Reservation reservation = slots[i];
            if (reservation != null && !reservation.used && now - reservation.since >= UNUSED_TIMEOUT.toNanos()) {
                slots[i] = null;
                released.add(reservation.request);
            }
        }
        return released;
    }

    /** Every slot with its reservation, by index. */
    public synchronized List<Slot> snapshot() {
        List<Slot> list = new ArrayList<>(slots.length);
        for (int i = 0; i < slots.length; i++) {
            Reservation reservation = slots[i];
            list.add(
                    reservation == null
                            ? new Slot(i, null, null)
                            : new Slot(i, reservation.request.jobId(), reservation.request.allocationId()));
        }
        return list;
    }

    private @Nullable Reservation find(String allocationId) {
        for (Reservation reservation : slots) {
            if (reservation != null && reservation.request.allocationId().equals(allocationId)) {
                return reservation;
            }
        }
        return null;
    }
}
