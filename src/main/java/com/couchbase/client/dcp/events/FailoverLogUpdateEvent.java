package com.couchbase.client.dcp.events;

import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.couchbase.client.dcp.state.PartitionState;
import com.couchbase.client.deps.com.fasterxml.jackson.databind.ObjectMapper;

public class FailoverLogUpdateEvent implements DcpEvent {
    private static final Logger LOGGER = Logger.getLogger(FailoverLogUpdateEvent.class.getName());
    private final PartitionState partitionState;

    public FailoverLogUpdateEvent(PartitionState partitionState) {
        this.partitionState = partitionState;
    }

    @Override
    public Type getType() {
        return Type.FAILOVER_UPDATE;
    }

    public PartitionState getPartitionState() {
        return partitionState;
    }

    @Override
    public String toString() {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(toMap());
        } catch (Exception e) {
            LOGGER.log(Level.WARN, e);
            return "{\"" + this.getClass().getSimpleName() + "\":\"" + e.toString() + "\"}";
        }
    }

    private Map<String, Object> toMap() {
        Map<String, Object> tree = new HashMap<>();
        tree.put(DcpEvent.class.getSimpleName(), this.getClass().getSimpleName());
        return tree;
    }

}
