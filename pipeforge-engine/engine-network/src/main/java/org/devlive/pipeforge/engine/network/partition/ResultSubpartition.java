/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.partition;

import java.util.ArrayDeque;
import org.devlive.pipeforge.engine.network.NetworkEvent;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.jspecify.annotations.Nullable;

/** The buffers of an output for one consumer subtask: finished ones in order and the one being filled. */
final class ResultSubpartition {

    private final ResultPartition parent;
    private final int index;
    private final ArrayDeque<Buffer> finished = new ArrayDeque<>();
    private @Nullable Buffer current;
    private int backlog;
    private boolean ended;
    private boolean consumed;
    private boolean released;
    private boolean counted;
    private @Nullable Runnable listener;

    ResultSubpartition(ResultPartition parent, int index) {
        this.parent = parent;
        this.index = index;
    }

    /** Appends a row's bytes to the current buffer if they fit; false when a new buffer is needed. */
    synchronized boolean append(org.devlive.pipeforge.engine.network.row.RowBuffer row) {
        Buffer buffer = current;
        if (buffer == null || buffer.size() + row.size() > buffer.capacity()) {
            return false;
        }
        row.copyTo(buffer.memory(), buffer.size());
        buffer.setSize(buffer.size() + row.size());
        return true;
    }

    /**
     * Starts a new current buffer, finishing the one before.
     *
     * @return false when the subpartition is released: its consumer is gone and the rows are dropped
     */
    boolean start(Buffer buffer) {
        Runnable notify;
        synchronized (this) {
            if (released) {
                buffer.recycle();
                return false;
            }
            notify = finishCurrent();
            current = buffer;
        }
        run(notify);
        return true;
    }

    /** Adds a finished buffer (an event, or an oversized row) after the current one. */
    void add(Buffer buffer) {
        Runnable notify;
        synchronized (this) {
            if (released) {
                buffer.recycle();
                return;
            }
            finishCurrent();
            finished.add(buffer);
            if (!buffer.isEvent()) {
                backlog++;
            } else if (NetworkEvent.fromBuffer(buffer) instanceof NetworkEvent.EndOfPartition) {
                ended = true;
            }
            notify = listener;
        }
        run(notify);
    }

    /** Makes a current buffer with content available to the consumer. */
    void flush() {
        Runnable notify;
        synchronized (this) {
            notify = finishCurrent();
        }
        run(notify);
    }

    private @Nullable Runnable finishCurrent() {
        Buffer buffer = current;
        if (buffer == null || buffer.size() == 0) {
            return null;
        }
        current = null;
        finished.add(buffer);
        backlog++;
        return listener;
    }

    synchronized boolean hasCurrent() {
        return current != null;
    }

    synchronized boolean isEnded() {
        return ended;
    }

    synchronized boolean isDone() {
        return consumed || released;
    }

    SubpartitionView view(Runnable available) {
        synchronized (this) {
            if (listener != null || released || consumed) {
                throw new IllegalStateException("subpartition " + index + " of " + parent.id() + " is read already");
            }
            listener = available;
        }
        View view = new View();
        if (backlogOrEvents()) {
            available.run();
        }
        return view;
    }

    private synchronized boolean backlogOrEvents() {
        return !finished.isEmpty();
    }

    /** Drops all buffers; nothing is kept from now on. */
    void release() {
        synchronized (this) {
            if (released) {
                return;
            }
            released = true;
            finished.forEach(Buffer::recycle);
            finished.clear();
            if (current != null) {
                current.recycle();
                current = null;
            }
            backlog = 0;
        }
        countDone();
    }

    /** Tells the partition once that this subpartition needs nothing more. */
    private void countDone() {
        synchronized (this) {
            if (counted) {
                return;
            }
            counted = true;
        }
        parent.subpartitionDone();
    }

    private static void run(@Nullable Runnable listener) {
        if (listener != null) {
            listener.run();
        }
    }

    private final class View implements SubpartitionView {
        @Override
        public @Nullable Delivery poll(boolean data) {
            Delivery delivery;
            synchronized (ResultSubpartition.this) {
                Buffer head = finished.peek();
                if (head == null || (!head.isEvent() && !data)) {
                    return null;
                }
                finished.poll();
                if (!head.isEvent()) {
                    backlog--;
                }
                boolean last = ended && finished.isEmpty();
                if (last) {
                    consumed = true;
                }
                delivery = new Delivery(head, backlog, last);
            }
            if (delivery.last()) {
                countDone();
            }
            return delivery;
        }

        @Override
        public int backlog() {
            synchronized (ResultSubpartition.this) {
                return backlog;
            }
        }

        @Override
        public void release() {
            ResultSubpartition.this.release();
        }

        @Override
        public ResultPartition partition() {
            return parent;
        }
    }
}
