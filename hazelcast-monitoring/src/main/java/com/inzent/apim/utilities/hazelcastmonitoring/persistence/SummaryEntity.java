package com.inzent.apim.utilities.hazelcastmonitoring.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hzmon_monitor_summary")
public class SummaryEntity {
    @Id
    @Column(name = "id", nullable = false)
    public UUID id;
    @Column(name = "cluster_id", nullable = false, length = 200)
    public String clusterId;
    @Column(name = "cluster_name", nullable = false, length = 200)
    public String clusterName;
    @Column(name = "executed_at", nullable = false)
    public Instant executedAt;
    @Column(name = "completed_at", nullable = false)
    public Instant completedAt;
    @Column(name = "active_node_count", nullable = true)
    public Integer activeNodeCount;
    @Column(name = "collection_status", nullable = false, length = 32)
    public String collectionStatus;
    @Column(name = "cluster_state", nullable = false, length = 32)
    public String clusterState;
    @Column(name = "topology_changed", nullable = false)
    public boolean topologyChanged;
    @Column(name = "object_inventory_observed_at", nullable = true)
    public Instant objectInventoryObservedAt;
    @Column(name = "object_inventory_complete", nullable = false)
    public boolean objectInventoryComplete;
    @Column(name = "error_code", nullable = true, length = 64)
    public String errorCode;
    @Column(name = "error_message", nullable = true, length = 2000)
    public String errorMessage;
    @Column(name = "yaml_export_status", nullable = false, length = 16)
    public String yamlExportStatus;
    @Column(name = "yaml_path", nullable = true, length = 2000)
    public String yamlPath;
}
