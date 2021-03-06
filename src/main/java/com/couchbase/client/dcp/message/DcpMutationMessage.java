/*
 * Copyright (c) 2016-2017 Couchbase, Inc.
 */
package com.couchbase.client.dcp.message;

import static com.couchbase.client.dcp.message.MessageUtil.DCP_MUTATION_OPCODE;

import java.nio.charset.Charset;

import com.couchbase.client.deps.io.netty.buffer.ByteBuf;
import com.couchbase.client.deps.io.netty.util.CharsetUtil;

public enum DcpMutationMessage {
    ;

    public static boolean is(final ByteBuf buffer) {
        return buffer.getByte(0) == MessageUtil.MAGIC_REQ && buffer.getByte(1) == DCP_MUTATION_OPCODE;
    }

    public static ByteBuf key(final ByteBuf buffer) {
        return MessageUtil.getKey(buffer);
    }

    public static String keyString(final ByteBuf buffer, Charset charset) {
        return key(buffer).toString(charset);
    }

    public static String keyString(final ByteBuf buffer) {
        return keyString(buffer, CharsetUtil.UTF_8);
    }

    public static ByteBuf content(final ByteBuf buffer) {
        return MessageUtil.getContent(buffer);
    }

    public static byte[] contentBytes(final ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        content(buffer).getBytes(0, bytes);
        return bytes;
    }

    public static long cas(final ByteBuf buffer) {
        return MessageUtil.getCas(buffer);
    }

    public static short partition(final ByteBuf buffer) {
        return MessageUtil.getVbucket(buffer);
    }

    public static long bySeqno(final ByteBuf buffer) {
        return buffer.getLong(MessageUtil.HEADER_SIZE);
    }

    public static long revisionSeqno(final ByteBuf buffer) {
        return buffer.getLong(MessageUtil.HEADER_SIZE + 8);
    }

    public static int flags(final ByteBuf buffer) {
        return buffer.getInt(MessageUtil.HEADER_SIZE + 16);
    }

    public static int expiry(final ByteBuf buffer) {
        return buffer.getInt(MessageUtil.HEADER_SIZE + 20);
    }

    public static int lockTime(final ByteBuf buffer) {
        return buffer.getInt(MessageUtil.HEADER_SIZE + 24);
    }

    public static String toString(final ByteBuf buffer) {
        return "MutationMessage [key: \"" + keyString(buffer) + "\", vbid: " + partition(buffer) + ", cas: "
                + cas(buffer) + ", bySeqno: " + bySeqno(buffer) + ", revSeqno: " + revisionSeqno(buffer) + ", flags: "
                + flags(buffer) + ", expiry: " + expiry(buffer) + ", lockTime: " + lockTime(buffer) + ", clength: "
                + content(buffer).readableBytes() + "]";
    }
}
