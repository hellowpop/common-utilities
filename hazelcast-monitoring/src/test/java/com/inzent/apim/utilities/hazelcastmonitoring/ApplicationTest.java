package com.inzent.apim.utilities.hazelcastmonitoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class ApplicationTest {
    @TempDir Path temp;
    private int run(String... args) { return MonitorApplication.run(args,new PrintStream(new ByteArrayOutputStream())); }
    @Test void helpAndInvalidCliDoNotConnectToDatabase() {
        assertThat(run("--help","--spring.datasource.url=jdbc:invalid:bad")).isZero();
        assertThat(run("--mode=unknown","--spring.datasource.url=jdbc:invalid:bad")).isEqualTo(2);
    }
    @Test void installBypassesBrokenDatabaseAndClientSettingsBeforeBoot() {
        var bytes=new ByteArrayOutputStream();
        int code=MonitorApplication.run(new String[]{"--install","--spring.datasource.url=jdbc:invalid:bad","--monitor.client-config=missing.yml"},new PrintStream(bytes));
        // Unit-test classpath has no executable JAR; it must reach installer validation, not DB/client validation.
        assertThat(code).isEqualTo(5);
        assertThat(bytes.toString()).contains("Install requires a packaged JAR or installed runtime/classes").doesNotContain("Invalid configuration");
    }
    @Test void configuredInstallModeNeverFallsThroughToCollection() {
        assertThat(run("--spring.application.json={\"mode\":\"install\"}","--spring.datasource.url=jdbc:invalid:bad")).isEqualTo(5);
    }
    @Test void explicitReportFlagOverridesConfiguredInstallBeforeAnySideEffects() {
        var bytes=new ByteArrayOutputStream();
        int code=MonitorApplication.run(new String[]{"--report","--spring.application.json={\"mode\":\"install\"}","--spring.datasource.url=jdbc:invalid:bad"},new PrintStream(bytes));
        assertThat(code).isEqualTo(2);
        assertThat(bytes.toString()).contains("Use H2 file/memory or PostgreSQL").doesNotContain("Installation failed");
    }
    @Test void initReportAndFailedCollectionWorkWithoutALiveCluster() throws Exception {
        String url="jdbc:h2:file:"+temp.resolve("monitor").toString().replace('\\','/');
        String datasource="--spring.datasource.url="+url;
        assertThat(run("--init",datasource)).isZero();
        assertThat(run("--report",datasource,"--monitor.report-file="+temp.resolve("empty.html"))).isZero();
        assertThat(run(datasource,"--monitor.members=127.0.0.1:1","--monitor.connect-timeout=1s","--monitor.snapshot-dir="+temp.resolve("yaml"))).isEqualTo(3);
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement();var r=s.executeQuery("select active_node_count,collection_status,yaml_export_status from hzmon_monitor_summary")) {
            assertThat(r.next()).isTrue(); assertThat(r.getObject(1)).isNull();
            assertThat(r.getString(2)).isEqualTo("FAILED"); assertThat(r.getString(3)).isEqualTo("EXPORTED");
        }
        assertThat(run("--report",datasource,"--monitor.report-file="+temp.resolve("failed.html"))).isZero();
        assertThat(Files.readString(temp.resolve("failed.html"))).contains("FAILED");
    }
    @Test void modeAndClientConfigurationAreValidatedAfterExternalConfigurationLoads() throws Exception {
        String url="jdbc:h2:file:"+temp.resolve("configured").toString().replace('\\','/');
        assertThat(run("--init","--spring.datasource.url="+url)).isZero();
        Path config=temp.resolve("external.properties");
        Files.writeString(config,"mode=report\nspring.datasource.url="+url+"\nmonitor.report-file="+temp.resolve("configured.html").toString().replace('\\','/')+"\n");
        assertThat(run("--spring.config.additional-location="+config.toUri(),"--monitor.start-time=2026-10-27T00:00:00Z")).isZero();
        assertThat(temp.resolve("configured.html")).exists();
    }
}
