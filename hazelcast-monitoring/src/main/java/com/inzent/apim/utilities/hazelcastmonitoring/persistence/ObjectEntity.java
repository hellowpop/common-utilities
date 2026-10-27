package com.inzent.apim.utilities.hazelcastmonitoring.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hzmon_object_info")
public class ObjectEntity {
    @Id
    @Column(name = "id", nullable = false)
    public UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "summary_id", nullable = false)
    public SummaryEntity summary;
    @Column(name = "object_type", nullable = false, length = 64)
    public String objectType;
    @Column(name = "service_name", nullable = false, length = 512)
    public String serviceName;
    @Column(name = "object_name", nullable = false, length = 512)
    public String objectName;
    @Column(name = "object_count", nullable = true)
    public Long objectCount;
    @Column(name = "count_status", nullable = false, length = 32)
    public String countStatus;
    @Column(name = "observed_at", nullable = true)
    public Instant observedAt;
    @Column(name = "error_code", nullable = true, length = 64)
    public String errorCode;
    @Column(name = "error_message", nullable = true, length = 2000)
    public String errorMessage;
}
