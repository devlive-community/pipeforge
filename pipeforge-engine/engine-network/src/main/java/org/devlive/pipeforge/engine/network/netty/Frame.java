/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.netty;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import org.devlive.pipeforge.engine.network.ResultPartitionId;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.jspecify.annotations.Nullable;

/** The frames of the data exchange (protocol specification, section 3.2 and appendix B). */
public sealed interface Frame {

    int MAGIC = 0x5046;
    int VERSION = 1;
    String PROTOCOL_VERSION = "1.0";
    int HEADER = 8;

    int HELLO = 0x00;
    int PARTITION_REQUEST = 0x01;
    int BUFFER_RESPONSE = 0x02;
    int ADD_CREDIT = 0x03;
    int CANCEL_PARTITION = 0x04;
    int CLOSE_REQUEST = 0x05;
    int ERROR_RESPONSE = 0x06;
    int PING = 0x07;
    int PONG = 0x08;

    int type();

    record Hello(String protocolVersion, String workerId, String clusterToken) implements Frame {
        @Override
        public int type() {
            return HELLO;
        }

        @Override
        public String toString() {
            return "Hello[" + protocolVersion + ", " + workerId + "]";
        }
    }

    record PartitionRequest(UUID channel, ResultPartitionId partition, int subpartition, int credit) implements Frame {
        @Override
        public int type() {
            return PARTITION_REQUEST;
        }
    }

    /**
     * A buffer of a channel.
     *
     * @param data the content; the frame owns one reference to it
     */
    record BufferResponse(UUID channel, long sequence, int backlog, Buffer.Kind kind, int flags, ByteBuf data)
            implements Frame {
        @Override
        public int type() {
            return BUFFER_RESPONSE;
        }
    }

    record AddCredit(UUID channel, int credit) implements Frame {
        @Override
        public int type() {
            return ADD_CREDIT;
        }
    }

    record CancelPartition(UUID channel) implements Frame {
        @Override
        public int type() {
            return CANCEL_PARTITION;
        }
    }

    record CloseRequest() implements Frame {
        @Override
        public int type() {
            return CLOSE_REQUEST;
        }
    }

    /**
     * An error of one channel or of the whole connection.
     *
     * @param channel the channel that failed, or {@code null} for the whole connection
     */
    record ErrorResponse(@Nullable UUID channel, String code, String message) implements Frame {
        @Override
        public int type() {
            return ERROR_RESPONSE;
        }
    }

    record Ping() implements Frame {
        @Override
        public int type() {
            return PING;
        }
    }

    record Pong() implements Frame {
        @Override
        public int type() {
            return PONG;
        }
    }
}
