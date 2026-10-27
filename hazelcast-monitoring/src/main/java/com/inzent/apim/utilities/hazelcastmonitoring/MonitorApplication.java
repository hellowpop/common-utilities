package com.inzent.apim.utilities.hazelcastmonitoring;

import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.collection.ClusterCollector;
import com.inzent.apim.utilities.hazelcastmonitoring.export.YamlExporter;
import com.inzent.apim.utilities.hazelcastmonitoring.persistence.SnapshotStore;
import com.inzent.apim.utilities.hazelcastmonitoring.report.HtmlReport;
import com.inzent.apim.utilities.hazelcastmonitoring.schema.SchemaManager;
import com.inzent.apim.utilities.hazelcastmonitoring.snapshot.Snapshot;
import com.inzent.apim.utilities.hazelcastmonitoring.install.Installer;
import java.io.*;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Deliberately no EnableAutoConfiguration: mode selection precedes all database/client side effects. */
@SpringBootConfiguration(proxyBeanMethods=false)
public class MonitorApplication {
    public static void main(String[] args) { System.exit(run(args,System.out)); }
    public static int run(String[] args,PrintStream output) {
        try {
            if(Options.helpRequested(args)) { help(output); return 0; }
            if(Options.installRequested(args))return install(output);
        } catch(RuntimeException e) { output.println("Invalid arguments: "+e.getMessage()); return 2; }
        var app=new SpringApplication(MonitorApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE); app.setBannerMode(Banner.Mode.OFF); app.setLogStartupInfo(false);
        app.setRegisterShutdownHook(false);
        app.setDefaultProperties(Map.of("spring.datasource.url","jdbc:h2:file:./data/hazelcast-monitor","spring.datasource.username","sa",
            "spring.datasource.password","","logging.level.root","WARN"));
        ConfigurableApplicationContext context;
        try { context=app.run(args); }
        catch(RuntimeException e) { output.println("Application configuration failed ("+e.getClass().getSimpleName()+")"); return 2; }
        try(context) {
            Options o;
            try {
                if(Options.selectedMode(args,context.getEnvironment())==Options.Mode.INSTALL)return install(output);
                o=Options.parse(args,context.getEnvironment());
            }
            catch(RuntimeException e) { output.println("Invalid configuration: "+e.getMessage()); return 2; }
            if(o.help()) { help(output); return 0; }
            var env=context.getEnvironment();
            String url=env.getRequiredProperty("spring.datasource.url");
            if((!url.startsWith("jdbc:h2:file:")&&!url.startsWith("jdbc:h2:mem:")&&!url.startsWith("jdbc:postgresql:")) || url.toUpperCase(java.util.Locale.ROOT).contains("AUTO_SERVER=TRUE")) {
                output.println("Use H2 file/memory or PostgreSQL; H2 AUTO_SERVER is unsupported"); return 2;
            }
            var db=new DriverManagerDataSource(url,env.getProperty("spring.datasource.username","sa"),env.getProperty("spring.datasource.password",""));
            var schema=new SchemaManager(db);
            try {
                if(o.mode()==Options.Mode.INIT) {
                    schema.reset(); try(var ignored=new SnapshotStore(db,true)) { output.println("Monitoring schema recreated; monitoring history removed"); }
                    return 0;
                }
                if(o.mode()==Options.Mode.REPORT) {
                    schema.check();
                    try(var store=new SnapshotStore(db,false)) {
                        var snapshots=store.report(o);
                        try { output.println("Report: "+new HtmlReport().write(snapshots,o)); return 0; }
                        catch(IOException e) { output.println("HTML export failed ("+e.getClass().getSimpleName()+")"); return 5; }
                    }
                }
                schema.update();
                try(var store=new SnapshotStore(db,true)) {
                    var exporter=new YamlExporter(); int recoverFailures=exporter.recover(store,o);
                    Snapshot snapshot=new ClusterCollector().collect(o);
                    var path=exporter.path(snapshot,o.snapshotDir());
                    store.save(snapshot,path);
                    try { exporter.export(snapshot,path); }
                    catch(IOException e) { store.markExport(snapshot.id(),"ERROR"); output.println("YAML export failed; run="+snapshot.id()); return 5; }
                    store.markExport(snapshot.id(),"EXPORTED");
                    output.println("run="+snapshot.id()+" cluster="+snapshot.clusterId()+" result="+snapshot.collectionStatus()+" nodes="+snapshot.activeNodeCount()+" objects="+snapshot.objects().size()+" YAML="+path);
                    return recoverFailures>0||snapshot.collectionStatus()!=Snapshot.Status.SUCCESS?3:0;
                }
            } catch(IllegalArgumentException e) { output.println("Invalid operation: "+e.getMessage()); return 2; }
            catch(RuntimeException e) { output.println("Database/schema operation failed ("+e.getClass().getSimpleName()+")"); return 4; }
        }
    }
    private static int install(PrintStream output) {
        try {
            Path directory=Path.of("").toAbsolutePath().normalize();
            new Installer().install(directory,Installer.runningSource()).forEach((file,status)->output.println(status+" "+directory.resolve(file)));
            output.println("Edit application.yml, then run collect.bat or ./collect.sh. Separate report, init and install scripts are also available. Java 17+ is required.");
            return 0;
        } catch(IOException e) { output.println("Installation failed: "+e.getMessage()); return 5; }
    }
    private static void help(PrintStream out) {
        out.println("""
            Hazelcast monitoring — Java 17 single-run CLI
            Bootstrap: java -jar hazelcast-monitoring.jar --install
            Installed launchers: collect/report/init/install (.bat on Windows, .sh on Linux)
            Windows: java -cp "runtime/classes;runtime/lib/*" com.inzent.apim.utilities.hazelcastmonitoring.MonitorApplication [mode flag] [--key=value ...]
            Linux:   java -cp "runtime/classes:runtime/lib/*" com.inzent.apim.utilities.hazelcastmonitoring.MonitorApplication [mode flag] [--key=value ...]
              --collect (default)     collect once, persist using JPA, export YAML, exit
              --report                export a standalone offline HTML report; no Hazelcast connection
              --init                  DELETE monitoring history and recreate owned schema objects
              --install               create runtime/classes, runtime/lib, application.yml and mode-specific scripts
              --monitor.cluster-id=ID logical identity (defaults to cluster-name)
              --monitor.cluster-name=dev --monitor.members=127.0.0.1:5701
              --monitor.client-config=client.yml (exclusive with members/cluster-name)
              --monitor.snapshot-dir=./output/snapshots
              --monitor.report-file=report.html
              "--monitor.start-time=2026-10-27 00:00:00"
              "--monitor.end-time=2026-10-28 00:00:00" (exclusive)
              --monitor.report-zone=Asia/Seoul
              --monitor.connect-timeout=30s --monitor.operation-timeout=10s --monitor.collection-timeout=120s
              --monitor.node-name-attribute=monitor.node-name
              --monitor.max-report-runs=10000 --monitor.max-report-objects=1000000
              --spring.datasource.url=jdbc:h2:file:./data/hazelcast-monitor
              --spring.datasource.username=sa --spring.datasource.password=...
              --spring.config.additional-location=file:./config/
            Exit: 0 success, 2 input, 3 partial/failed collection or recovery, 4 database/schema, 5 output file
            """);
    }
}
