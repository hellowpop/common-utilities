package com.inzent.apim.utilities.hazelcastmonitoring.snapshot;

import java.time.Instant;
import java.util.*;

/** One collection interval; null counts always mean unknown, never zero. */
public record Snapshot(UUID id, String clusterId, String clusterName, Instant executedAt, Instant completedAt,
        Integer activeNodeCount, Status collectionStatus, String clusterState, boolean topologyChanged,
        Instant objectInventoryObservedAt, boolean objectInventoryComplete, String errorCode, String errorMessage,
        List<Node> nodes, List<ObjectInfo> objects) {
    public enum Status { SUCCESS, PARTIAL, FAILED }
    public enum CountStatus { SUCCESS, UNSUPPORTED, ERROR, NOT_COLLECTED }
    public record Node(UUID memberUuid, String nodeName, String nodeAddress, String nodeStatus, boolean liteMember) {
        public Node {
            if(memberUuid==null)throw new IllegalArgumentException("memberUuid is required");
            check(nodeName,512); check(nodeAddress,512); check(nodeStatus,32);
        }
    }
    public record ObjectInfo(String objectType, String serviceName, String objectName, Long objectCount,
            CountStatus countStatus, Instant observedAt, String errorCode, String errorMessage) {
        public ObjectInfo {
            check(objectName,512); check(serviceName,512); check(objectType,64);
            if(countStatus==null)throw new IllegalArgumentException("countStatus is required");
            if((countStatus==CountStatus.SUCCESS)!=(objectCount!=null)||(objectCount!=null&&objectCount<0))
                throw new IllegalArgumentException("Invalid count/status");
        }
    }
    public Snapshot {
        Objects.requireNonNull(id); Objects.requireNonNull(executedAt); Objects.requireNonNull(completedAt);
        Objects.requireNonNull(collectionStatus); Objects.requireNonNull(clusterState);
        nodes = List.copyOf(nodes); objects = List.copyOf(objects);
        check(clusterId,200); check(clusterName,200);
        if (activeNodeCount != null && activeNodeCount < 0) throw new IllegalArgumentException("Negative node count");
        Set<String> names = new HashSet<>();
        for (ObjectInfo o : objects) {
            if (!names.add(o.serviceName() + "\0" + o.objectName())) throw new IllegalArgumentException("Duplicate object identity");
        }
    }
    private static void check(String value, int length) {
        if (value == null || value.isBlank() || value.length() > length) throw new IllegalArgumentException("Invalid snapshot string length");
    }
}
