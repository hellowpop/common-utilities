package com.inzent.apim.utilities.hazelcastmonitoring;

import com.hazelcast.core.*;
import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.persistence.SnapshotStore;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Optional end-to-end probe: launches the packaged JAR in separate Java processes. */
public final class ExecutableSmoke {
    public static void main(String[] ignored) throws Exception {
        String cluster="smoke-"+UUID.randomUUID(); HazelcastInstance a=null,b=null;
        Path output=Path.of("target/executable-smoke").toAbsolutePath(); Files.createDirectories(output);
        Path jar=Path.of("target/hazelcast-monitoring.jar").toAbsolutePath();
        String url="jdbc:h2:file:"+output.resolve("monitor").toString().replace('\\','/');
        try {
            a=Hazelcast.newHazelcastInstance(ClusterCollectorTest.config(cluster,"node-a",null));
            int port=a.getCluster().getLocalMember().getAddress().getPort();
            b=Hazelcast.newHazelcastInstance(ClusterCollectorTest.config(cluster,"node-b","127.0.0.1:"+port));
            a.getMap("orders").putAll(Map.of(1,"a",2,"b")); a.getTopic("events");
            execute(jar,output,0,"help","--help");
            execute(jar,output,0,"init","--init","--spring.datasource.url="+url);
            String[] collect={"--spring.datasource.url="+url,"--monitor.cluster-id=smoke","--monitor.cluster-name="+cluster,
                "--monitor.members=127.0.0.1:"+port,"--monitor.snapshot-dir="+output.resolve("snapshots")};
            execute(jar,output,0,"collect-1",collect);
            a.getMap("orders").put(3,"c"); execute(jar,output,0,"collect-2",collect);
            b.shutdown(); b=null; execute(jar,output,0,"collect-3",collect);
            execute(jar,output,0,"report","--report","--spring.datasource.url="+url,"--monitor.cluster-id=smoke","--monitor.report-file="+output.resolve("report.html"));
            try(var store=new SnapshotStore(new DriverManagerDataSource(url,"sa",""),false)) {
                var rows=store.report(Options.parse(new String[]{"--report","--monitor.cluster-id=smoke"},new MockEnvironment()));
                if(rows.stream().flatMap(s->s.objects().stream()).anyMatch(o->o.objectType().equals("TOPIC")))throw new AssertionError("Topic must not be stored");
                if(!rows.stream().map(s -> s.activeNodeCount()).toList().equals(List.of(2,2,1)))throw new AssertionError("Wrong node trend");
                var counts=rows.stream().map(s -> s.objects().stream().filter(o -> o.objectName().equals("orders")).findFirst().orElseThrow().objectCount()).toList();
                if(!counts.equals(List.of(2L,3L,3L)))throw new AssertionError("Wrong object trend "+counts);
            }
            try(var files=Files.walk(output.resolve("snapshots"))) { if(files.filter(p->p.toString().endsWith(".yml")).count()!=3)throw new AssertionError("Wrong YAML count"); }
            System.out.println("EXECUTABLE SMOKE PASSED: nodes=[2,2,1], orders=[2,3,3], 3 YAML, HTML="+output.resolve("report.html"));
        } finally { if(b!=null)b.shutdown(); if(a!=null)a.shutdown(); }
    }
    private static void execute(Path jar,Path dir,int expected,String name,String... options) throws Exception {
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-XX:ActiveProcessorCount=4","-jar",jar.toString()));
        command.addAll(Arrays.asList(options)); Path log=dir.resolve(name+".log");
        Process p=new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!p.waitFor(60,TimeUnit.SECONDS)) { p.destroyForcibly(); throw new AssertionError("JAR did not terminate: "+name); }
        if(p.exitValue()!=expected)throw new AssertionError(name+" exit="+p.exitValue()+"\n"+Files.readString(log));
    }
}
