/*
 * Copyright (c) 2017 Couchbase, Inc.
 */
package com.couchbase.client.dcp;

import com.couchbase.client.deps.io.netty.buffer.ByteBuf;

@FunctionalInterface
public interface DcpAckHandle {
    void ack(ByteBuf message);
}
