package com.inzent.apim.utilities.hazelcastmonitoring.persistence;

import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.snapshot.Snapshot;
import jakarta.persistence.*;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.function.Function;
import org.hibernate.Session;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Owns its factory. Network collection and filesystem writes never run in these transactions. */
public final class SnapshotStore implements AutoCloseable {
    public record Pending(UUID id, Path path) {}
    private final LocalContainerEntityManagerFactoryBean factory;
    private final EntityManagerFactory emf;
    public SnapshotStore(DataSource source, boolean validate) {
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan(SnapshotStore.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", validate ? "validate" : "none",
            "hibernate.jdbc.time_zone", "UTC", "hibernate.jdbc.batch_size", "50",
            "hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD"));
        factory.afterPropertiesSet();
        emf = Objects.requireNonNull(factory.getObject());
    }
    private <T> T transaction(boolean readOnly, Function<EntityManager,T> body) {
        try (EntityManager em = emf.createEntityManager()) {
            em.unwrap(Session.class).setDefaultReadOnly(readOnly);
            em.unwrap(Session.class).doWork(c -> { c.setReadOnly(readOnly); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); });
            var tx = em.getTransaction();
            try { tx.begin(); T result = body.apply(em); tx.commit(); return result; }
            catch (RuntimeException e) { if (tx.isActive()) tx.rollback(); throw e; }
        }
    }
    public void save(Snapshot value, Path yamlPath) {
        transaction(false, em -> {
            SummaryEntity s = new SummaryEntity(); s.id=value.id(); s.clusterId=value.clusterId(); s.clusterName=value.clusterName();
            s.executedAt=value.executedAt(); s.completedAt=value.completedAt(); s.activeNodeCount=value.activeNodeCount();
            s.collectionStatus=value.collectionStatus().name(); s.clusterState=value.clusterState(); s.topologyChanged=value.topologyChanged();
            s.objectInventoryObservedAt=value.objectInventoryObservedAt(); s.objectInventoryComplete=value.objectInventoryComplete();
            s.errorCode=value.errorCode(); s.errorMessage=value.errorMessage(); s.yamlExportStatus="PENDING";
            s.yamlPath=yamlPath.toAbsolutePath().normalize().toString(); em.persist(s);
            for (var n : value.nodes()) {
                NodeEntity row = new NodeEntity(); row.id=UUID.randomUUID(); row.summary=s; row.memberUuid=n.memberUuid();
                row.nodeName=n.nodeName(); row.nodeAddress=n.nodeAddress(); row.nodeStatus=n.nodeStatus(); row.liteMember=n.liteMember(); em.persist(row);
            }
            for (var o : value.objects()) {
                ObjectEntity row = new ObjectEntity(); row.id=UUID.randomUUID(); row.summary=s; row.objectType=o.objectType();
                row.serviceName=o.serviceName(); row.objectName=o.objectName(); row.objectCount=o.objectCount(); row.countStatus=o.countStatus().name();
                row.observedAt=o.observedAt(); row.errorCode=o.errorCode(); row.errorMessage=o.errorMessage(); em.persist(row);
            }
            return null;
        });
    }
    public Snapshot load(UUID id) {
        return transaction(true, em -> toSnapshot(em, Objects.requireNonNull(em.find(SummaryEntity.class, id), "Unknown run")));
    }
    private Snapshot toSnapshot(EntityManager em, SummaryEntity s) {
        var nodes = em.createQuery("select n from NodeEntity n where n.summary.id=:id order by n.memberUuid", NodeEntity.class)
            .setParameter("id", s.id).getResultList();
        var objects = em.createQuery("select o from ObjectEntity o where o.summary.id=:id order by o.serviceName,o.objectName", ObjectEntity.class)
            .setParameter("id", s.id).getResultList();
        return assemble(s,nodes,objects);
    }
    private Snapshot assemble(SummaryEntity s,List<NodeEntity> nodeRows,List<ObjectEntity> objectRows) {
        var nodes=nodeRows.stream().map(n -> new Snapshot.Node(n.memberUuid,n.nodeName,n.nodeAddress,n.nodeStatus,n.liteMember)).toList();
        var objects=objectRows.stream().map(o -> new Snapshot.ObjectInfo(o.objectType,o.serviceName,o.objectName,o.objectCount,
                Snapshot.CountStatus.valueOf(o.countStatus),o.observedAt,o.errorCode,o.errorMessage)).toList();
        return new Snapshot(s.id,s.clusterId,s.clusterName,s.executedAt,s.completedAt,s.activeNodeCount,Snapshot.Status.valueOf(s.collectionStatus),
            s.clusterState,s.topologyChanged,s.objectInventoryObservedAt,s.objectInventoryComplete,s.errorCode,s.errorMessage,nodes,objects);
    }
    public List<Pending> pending(String clusterId) {
        return pending(clusterId,null);
    }
    public List<Pending> pending(String clusterId,UUID after) {
        return transaction(true, em -> {
            var q=em.createQuery("select s from SummaryEntity s where s.clusterId=:cluster and s.yamlExportStatus <> 'EXPORTED'" +
                (after==null?"":" and s.id>:after") + " order by s.id",SummaryEntity.class).setParameter("cluster",clusterId);
            if(after!=null)q.setParameter("after",after);
            return q.setMaxResults(100).getResultList().stream().map(s -> new Pending(s.id,Path.of(s.yamlPath))).toList();
        });
    }
    public void markExport(UUID id, String status) {
        if (!Set.of("EXPORTED","ERROR").contains(status)) throw new IllegalArgumentException("Invalid export status");
        transaction(false, em -> { SummaryEntity s = Objects.requireNonNull(em.find(SummaryEntity.class,id)); s.yamlExportStatus=status; return null; });
    }
    public List<Snapshot> report(Options o) {
        return transaction(true, em -> {
            String where = "s.clusterId=:cluster" + (o.start()!=null ? " and s.executedAt>=:start" : "") + (o.end()!=null ? " and s.executedAt<:end" : "");
            long count=parameters(em.createQuery("select count(v) from ObjectEntity v join v.summary s where "+where,Long.class),o).getSingleResult();
            if(count>o.maxObjects())throw new IllegalArgumentException("Report object limit exceeded; narrow time range");
            List<Snapshot> result=new ArrayList<>(); SummaryEntity cursor=null;
            while(true) {
                String seek=cursor==null?"":" and (s.executedAt>:afterTime or (s.executedAt=:afterTime and s.id>:afterId))";
                var q=parameters(em.createQuery("select s from SummaryEntity s where "+where+seek+" order by s.executedAt,s.id",SummaryEntity.class),o);
                if(cursor!=null)q.setParameter("afterTime",cursor.executedAt).setParameter("afterId",cursor.id);
                var batch=q.setMaxResults(100).getResultList(); if(batch.isEmpty())break;
                if((long)result.size()+batch.size()>o.maxRuns())throw new IllegalArgumentException("Report run limit exceeded; narrow time range");
                var ids=batch.stream().map(s->s.id).toList();
                Map<UUID,List<NodeEntity>> nodes=new HashMap<>();
                for(var row:em.createQuery("select n.summary.id,n from NodeEntity n where n.summary.id in :ids order by n.memberUuid",Object[].class).setParameter("ids",ids).getResultList())
                    nodes.computeIfAbsent((UUID)row[0],k->new ArrayList<>()).add((NodeEntity)row[1]);
                Map<UUID,List<ObjectEntity>> objects=new HashMap<>();
                for(var row:em.createQuery("select v.summary.id,v from ObjectEntity v where v.summary.id in :ids order by v.serviceName,v.objectName",Object[].class).setParameter("ids",ids).getResultList())
                    objects.computeIfAbsent((UUID)row[0],k->new ArrayList<>()).add((ObjectEntity)row[1]);
                for(var s:batch)result.add(assemble(s,nodes.getOrDefault(s.id,List.of()),objects.getOrDefault(s.id,List.of())));
                cursor=batch.get(batch.size()-1); em.clear();
            }
            return List.copyOf(result);
        });
    }
    private <T> TypedQuery<T> parameters(TypedQuery<T> query,Options o) {
        query.setParameter("cluster",o.clusterId());
        if(o.start()!=null)query.setParameter("start",o.start()); if(o.end()!=null)query.setParameter("end",o.end()); return query;
    }
    @Override public void close() { factory.destroy(); }
}
