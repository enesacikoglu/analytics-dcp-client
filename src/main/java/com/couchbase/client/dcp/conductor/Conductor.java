/*
 * Copyright (c) 2016-2017 Couchbase, Inc.
 */
package com.couchbase.client.dcp.conductor;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.couchbase.client.core.config.CouchbaseBucketConfig;
import com.couchbase.client.core.config.NodeInfo;
import com.couchbase.client.core.logging.CouchbaseLogLevel;
import com.couchbase.client.core.logging.CouchbaseLogger;
import com.couchbase.client.core.logging.CouchbaseLoggerFactory;
import com.couchbase.client.core.service.ServiceType;
import com.couchbase.client.dcp.config.ClientEnvironment;
import com.couchbase.client.dcp.events.ChannelDroppedEvent;
import com.couchbase.client.dcp.state.PartitionState;
import com.couchbase.client.dcp.state.SessionState;
import com.couchbase.client.dcp.state.StreamRequest;

public class Conductor {

    private static final CouchbaseLogger LOGGER = CouchbaseLoggerFactory.getInstance(Conductor.class);
    private static final long WAIT_BETWEN_ATTEMPTS = 500L;

    private final ConfigProvider configProvider; // changes
    private final Map<InetSocketAddress, DcpChannel> channels; // changes
    private final ClientEnvironment env; // constant
    private SessionState sessionState; // final
    private final Fixer fixer; // final
    private Thread fixerThread; // once per connect
    private volatile boolean connected = false;
    private volatile boolean established;

    public Conductor(final ClientEnvironment env, ConfigProvider cp) {
        this.env = env;
        configProvider = cp == null ? new HttpStreamingConfigProvider(env) : cp;
        channels = new ConcurrentHashMap<>();
        fixer = new Fixer(this);
        env.setSystemEventHandler(fixer);
    }

    public ClientEnvironment getEnv() {
        return env;
    }

    public SessionState getSessionState() {
        return sessionState;
    }

    public void connect() throws Throwable {
        if (connected) {
            return;
        }
        connected = true;
        try {
            channels.clear();
            // passing 0 timeout and 0 retries will use the configured timeout
            configProvider.refresh(0, 0, WAIT_BETWEN_ATTEMPTS);
            createSession(configProvider.config());
        } catch (Exception e) {
            connected = false;
            throw e;
        }
    }

    /**
     * Returns true if all channels and the config provider are in a disconnected state.
     */
    public boolean disconnected() {
        return !connected;
    }

    public void disconnect(boolean wait) throws InterruptedException {
        LOGGER.info("Conductor.disconnect called.");
        fixer.poison();
        if (Thread.currentThread() != fixerThread) {
            sessionState.setDisconnected();
        }
        if (!connected) {
            return;
        }
        if (Thread.currentThread() != fixerThread && fixerThread != null) {
            LOGGER.info("Waiting for fixer thread to finish.");
            fixerThread.join();
            LOGGER.info("Fixer thread finished.");
        }
        fixerThread = null;
        synchronized (this) {
            if (!connected) {
                return;
            }
            connected = false;
            LOGGER.info("Instructed to shutdown dcp channels.");
            synchronized (channels) {
                for (DcpChannel channel : channels.values()) {
                    channel.disconnect(wait);
                }
            }
            established = false;
        }
    }

    /**
     * Returns the total number of partitions.
     */
    public int numberOfPartitions() {
        return configProvider.config().numberOfPartitions();
    }

    public void getSeqnos() throws Throwable {
        short[] vbuckets = env.vbuckets();
        // set request to all
        for (int i = 0; i < vbuckets.length; i++) {
            sessionState.get(vbuckets[i]).currentSeqRequest();
        }
        synchronized (channels) {
            for (DcpChannel channel : channels.values()) {
                getSeqnosForChannel(channel);
            }
        }
        for (int i = 0; i < vbuckets.length; i++) {
            sessionState.get(vbuckets[i]).waitTillCurrentSeqUpdated(60000);
        }
    }

    private void getSeqnosForChannel(final DcpChannel dcpChannel) throws InterruptedException {
        dcpChannel.getSeqnos();
    }

    public void getFailoverLog(final short partition) throws Throwable {
        PartitionState ps = getSessionState().get(partition);
        ps.failoverRequest();
        synchronized (channels) {
            masterChannelByPartition(partition).getFailoverLog(partition);
        }
        ps.waitTillFailoverUpdated(60000);
    }

    public void startStreamForPartition(StreamRequest request) {
        synchronized (channels) {
            DcpChannel channel = masterChannelByPartition(request.getPartition());
            channel.openStream(request.getPartition(), request.getVbucketUuid(), request.getStartSeqno(),
                    request.getEndSeqno(), request.getSnapshotStartSeqno(), request.getSnapshotEndSeqno());
        }
    }

    public void stopStreamForPartition(final short partition) throws InterruptedException {
        if (streamIsOpen(partition)) {
            PartitionState ps = sessionState.get(partition);
            synchronized (channels) {
                DcpChannel channel = masterChannelByPartition(partition);
                channel.closeStream(partition);
                ps.wait(PartitionState.DISCONNECTED);
            }
        }
    }

    public boolean streamIsOpen(final short partition) {
        synchronized (channels) {
            return masterChannelByPartition(partition).streamIsOpen(partition);
        }
    }

    /**
     * Returns the dcp channel responsible for a given vbucket id according to the current
     * configuration.
     *
     * Note that this doesn't mean that the partition is enabled there, it just checks the current
     * mapping.
     */
    private DcpChannel masterChannelByPartition(short partition) {
        synchronized (channels) {
            CouchbaseBucketConfig config = configProvider.config();
            int index = config.nodeIndexForMaster(partition, false);
            NodeInfo node = config.nodeAtIndex(index);
            InetSocketAddress address = new InetSocketAddress(node.hostname().nameOrAddress(),
                    (env.sslEnabled() ? node.sslServices() : node.services()).get(ServiceType.BINARY));
            DcpChannel theChannel = channels.get(address);
            if (theChannel == null) {
                throw new IllegalStateException("No DcpChannel found for partition " + partition + ". env vbuckets = "
                        + Arrays.toString(env.vbuckets()));
            }
            return theChannel;
        }
    }

    private synchronized void createSession(CouchbaseBucketConfig config) {
        if (sessionState == null) {
            sessionState = new SessionState(config.numberOfPartitions());
        } else {
            sessionState.setConnected();
        }
    }

    public void add(NodeInfo node, CouchbaseBucketConfig config, int timeout, int attempts) throws Throwable {
        synchronized (channels) {
            if (!(node.services().containsKey(ServiceType.BINARY)
                    || node.sslServices().containsKey(ServiceType.BINARY))) {
                return;
            }
            if (!config.hasPrimaryPartitionsOnNode(node.hostname())) {
                return;
            }
            InetSocketAddress address = new InetSocketAddress(node.hostname().nameOrAddress(),
                    (env.sslEnabled() ? node.sslServices() : node.services()).get(ServiceType.BINARY));
            if (channels.containsKey(address)) {
                return;
            }
            LOGGER.debug("Adding DCP Channel against {}", node);
            final DcpChannel channel = new DcpChannel(address, node.hostname(), env, sessionState,
                    configProvider.config().numberOfPartitions());
            channels.put(address, channel);
            try {
                channel.connect(timeout, attempts);
            } catch (Throwable th) {
                channels.remove(address);
                throw th;
            }
        }
    }

    public void setSessionState(SessionState sessionState) {
        this.sessionState = sessionState;
    }

    public CouchbaseBucketConfig config() {
        return configProvider.config();
    }

    public ConfigProvider configProvider() {
        return configProvider;
    }

    public void establishDcpConnections() throws Throwable {
        if (established) {
            return;
        }
        established = true;
        // create fixer thread
        CouchbaseBucketConfig config = configProvider.config();
        fixerThread = new Thread(fixer);
        fixerThread.start();
        fixer.waitTillStarted();
        for (NodeInfo node : config.nodes()) {
            // 0 timeout and attempts means use configured values
            add(node, config, 0, 0);
        }
    }

    public DcpChannel getChannel(short vbid) {
        return masterChannelByPartition(vbid);
    }

    public void removeChannel(DcpChannel channel) {
        synchronized (channels) {
            channels.remove(channel.getAddress());
        }
    }

    public Map<InetSocketAddress, DcpChannel> getChannels() {
        return channels;
    }

    public void reviveDeadConnections(int timeout, int attempts) {
        synchronized (channels) {
            for (DcpChannel channel : channels.values()) {
                synchronized (channel) {
                    if (channel.producerDroppedConnection()) {
                        try {
                            channel.disconnect(true);
                            try {
                                channel.connect(timeout, attempts);
                            } catch (Throwable e) {
                                // Disconnect succeeded but connect failed
                                LOGGER.log(CouchbaseLogLevel.WARN,
                                        "Dead connection detected, channel was disconnected successfully but connecting failed. Creating a channel dropped event",
                                        e);
                                channel.setState(State.CONNECTED);
                                env.eventBus().publish(new ChannelDroppedEvent(channel, e));
                            }
                        } catch (Exception e) {
                            LOGGER.log(CouchbaseLogLevel.WARN,
                                    "Failure disconnecting a dead dcp channel. ignoring till next round", e);
                        }
                    }
                }
            }
        }
    }
}
