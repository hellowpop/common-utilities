package com.inzent.apim.utilities.hazelcastmonitoring.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hzmon_node_info")
public class NodeEntity {
    @Id
    @Column(name = "id", nullable = false)
    public UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "summary_id", nullable = false)
    public SummaryEntity summary;
    @Column(name = "member_uuid", nullable = false)
    public UUID memberUuid;
    @Column(name = "node_name", nullable = false, length = 512)
    public String nodeName;
    @Column(name = "node_address", nullable = false, length = 512)
    public String nodeAddress;
    @Column(name = "node_status", nullable = false, length = 32)
    public String nodeStatus;
    @Column(name = "lite_member", nullable = false)
    public boolean liteMember;
}
