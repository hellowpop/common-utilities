package com.inzent.apim.utilities.hazelcastmonitoring;

import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.install.Installer;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.jar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.*;

class InstallTest {
    @TempDir Path temp;
    @Test void installCreatesRunnableDirectoryWithEditableSettings() throws Exception {
        Path source=probeJar(); Path directory=temp.resolve("install space & bang!");
        new Installer().install(directory,source);
        assertThat(directory.resolve("hazelcast-monitoring.jar")).doesNotExist();
        assertThat(directory.resolve("runtime/classes/com/inzent/apim/utilities/hazelcastmonitoring/MonitorApplication.class")).isRegularFile();
        assertThat(directory.resolve("runtime/lib/probe-dependency.jar")).isRegularFile();
        assertThat(directory.resolve("runtime/manifest.properties")).isRegularFile();
        assertThat(Files.readString(directory.resolve("runtime/classes/variant.txt"))).isEqualTo("A");
        Map<?,?> settings=new Yaml().load(Files.readString(directory.resolve("application.yml")));
        assertThat(settings.containsKey("spring")).isTrue(); assertThat(settings.containsKey("monitor")).isTrue();
        for(String mode:List.of("collect","report","init","install")) {
            assertThat(Files.readString(directory.resolve(mode+".bat"))).contains("\r\n");
            assertThat(Files.readString(directory.resolve(mode+".sh"))).startsWith("#!/bin/sh\n").doesNotContain("\r");
            if(directory.getFileSystem().supportedFileAttributeViews().contains("posix"))assertThat(Files.isExecutable(directory.resolve(mode+".sh"))).isTrue();
        }
        assertThat(directory.resolve("run.bat")).doesNotExist(); assertThat(directory.resolve("run.sh")).doesNotExist();
        assertThat(Files.readString(directory.resolve("application.yml"))).doesNotContain("mode: install");
    }
    @Test void reinstallPreservesUserFilesAndRejectsAlteredRuntime() throws Exception {
        Path source=probeJar(); Path directory=temp.resolve("install"); var installer=new Installer();
        installer.install(directory,source);
        Files.writeString(directory.resolve("application.yml"),"custom: settings\n");
        Files.writeString(directory.resolve("report.sh"),"custom script\n");
        Files.writeString(directory.resolve("run.bat"),"legacy script\n");
        installer.install(directory,source);
        assertThat(Files.readString(directory.resolve("application.yml"))).isEqualTo("custom: settings\n");
        assertThat(Files.readString(directory.resolve("report.sh"))).isEqualTo("custom script\n");
        assertThat(Files.readString(directory.resolve("run.bat"))).isEqualTo("legacy script\n");
        Files.writeString(directory.resolve("runtime/classes/variant.txt"),"altered runtime");
        assertThatThrownBy(()->installer.install(directory,source)).isInstanceOf(IOException.class);
        assertThat(Files.readString(directory.resolve("runtime/classes/variant.txt"))).isEqualTo("altered runtime");
        assertThat(Files.readString(directory.resolve("application.yml"))).isEqualTo("custom: settings\n");
    }
    @Test void rejectsDirectoryInPlaceOfGeneratedFileWithoutOverwritingIt() throws Exception {
        Path directory=temp.resolve("install"); Files.createDirectories(directory.resolve("application.yml"));
        assertThatThrownBy(()->new Installer().install(directory,probeJar())).isInstanceOf(IOException.class);
        assertThat(directory.resolve("application.yml")).isDirectory();
    }
    @Test void installSelectionDoesNotReadInvalidClientConfiguration() {
        assertThat(Options.installRequested(new String[]{"--install","--monitor.client-config=missing.yml","--spring.datasource.url=invalid"})).isTrue();
        assertThat(Options.parse(new String[]{"--install"},new MockEnvironment()).mode()).isEqualTo(Options.Mode.INSTALL);
    }
    @Test void concurrentDifferentJarInstallationsNeverBothReportSuccess() throws Exception {
        Path a=probeJar(),b=probeJar("other.jar","B");
        var workers=Executors.newFixedThreadPool(2);
        try {
            for(int i=0;i<40;i++) {
                Path target=temp.resolve("race-"+i); CountDownLatch start=new CountDownLatch(1);
                var first=workers.submit(()->attempt(target,a,start)); var second=workers.submit(()->attempt(target,b,start)); start.countDown();
                int successes=(first.get(10,TimeUnit.SECONDS)?1:0)+(second.get(10,TimeUnit.SECONDS)?1:0);
                assertThat(successes).as("Different source JARs must not both be accepted for one directory").isEqualTo(1);
            }
        } finally { workers.shutdownNow(); }
    }
    private boolean attempt(Path directory,Path source,CountDownLatch start) throws InterruptedException {
        start.await(); try { new Installer().install(directory,source); return true; } catch(IOException expectedCollision) { return false; }
    }
    @Test void installedRuntimeCanBeReusedWithoutOriginalArchiveAndCopiedElsewhere() throws Exception {
        Path source=probeJar(),first=temp.resolve("first"),second=temp.resolve("second"); var installer=new Installer();
        installer.install(first,source); Files.delete(source);
        installer.install(first,first.resolve("runtime/classes"));
        installer.install(second,first.resolve("runtime/classes"));
        assertThat(Files.readString(second.resolve("runtime/classes/variant.txt"))).isEqualTo("A");
        assertThat(second.resolve("runtime/lib/probe-dependency.jar")).isRegularFile();
    }
    @Test void rejectsAdditionalRuntimeDependencies() throws Exception {
        Path source=probeJar(),directory=temp.resolve("install"); var installer=new Installer(); installer.install(directory,source);
        Files.copy(directory.resolve("runtime/lib/probe-dependency.jar"),directory.resolve("runtime/lib/unexpected.jar"));
        assertThatThrownBy(()->installer.install(directory,source)).isInstanceOf(IOException.class);
        assertThat(directory.resolve("runtime/lib/unexpected.jar")).isRegularFile();
    }
    @Test void localHazelcastReplacesOnlyBundledCoreAndIsIncludedInIntegrityChecks() throws Exception {
        Path source=probeJar(),directory=temp.resolve("install"); Files.createDirectories(directory);
        Path local=directory.resolve("hazelcast-custom.jar"); Files.write(local,hazelcastJar("local",true));
        var installer=new Installer(); var result=installer.install(directory,source);
        assertThat(result.get("runtime/lib/hazelcast-custom.jar")).isEqualTo("HAZELCAST_OVERRIDE");
        assertThat(directory.resolve("runtime/lib/hazelcast-bundled.jar")).doesNotExist();
        assertThat(directory.resolve("runtime/lib/hazelcast-sql.jar")).isRegularFile();
        assertThat(Files.mismatch(local,directory.resolve("runtime/lib/hazelcast-custom.jar"))).isEqualTo(-1);
        Properties manifest=new Properties(); try(var reader=Files.newBufferedReader(directory.resolve("runtime/manifest.properties"))) { manifest.load(reader); }
        assertThat(manifest.getProperty("file.lib/hazelcast-custom.jar")).matches("[0-9a-f]{64}");
        assertThat(manifest.getProperty("file.lib/hazelcast-bundled.jar")).isNull();
        installer.install(directory,source); installer.install(directory,directory.resolve("runtime/classes"));
        Files.delete(local); Files.delete(source);
        installer.install(directory,directory.resolve("runtime/classes"));
        assertThat(directory.resolve("runtime/lib/hazelcast-custom.jar")).isRegularFile();
    }
    @Test void localHazelcastIsAppliedWhenInstallingFromAnotherRuntime() throws Exception {
        Path first=temp.resolve("first"),second=temp.resolve("second"); var installer=new Installer(); installer.install(first,probeJar());
        Files.createDirectories(second); Path local=second.resolve("hazelcast-custom.jar"); Files.write(local,hazelcastJar("override",true));
        installer.install(second,first.resolve("runtime/classes"));
        assertThat(second.resolve("runtime/lib/hazelcast-bundled.jar")).doesNotExist();
        assertThat(Files.mismatch(local,second.resolve("runtime/lib/hazelcast-custom.jar"))).isEqualTo(-1);
    }
    @Test void changedLocalLibraryDoesNotSilentlyChangeExistingRuntime() throws Exception {
        Path directory=temp.resolve("install"),source=probeJar(); Files.createDirectories(directory);
        Path local=directory.resolve("hazelcast-custom.jar"); Files.write(local,hazelcastJar("first",true)); var installer=new Installer(); installer.install(directory,source);
        byte[] before=Files.readAllBytes(directory.resolve("runtime/lib/hazelcast-custom.jar"));
        Files.write(local,hazelcastJar("second",true));
        assertThatThrownBy(()->installer.install(directory,directory.resolve("runtime/classes"))).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(directory.resolve("runtime/lib/hazelcast-custom.jar"))).isEqualTo(before);
    }
    @Test void multipleCoreLibrariesAndInvalidHazelcastArchivesFailBeforeRuntimePublication() throws Exception {
        Path directory=temp.resolve("install"),source=probeJar(); Files.createDirectories(directory);
        Path first=directory.resolve("hazelcast-a.jar"),second=directory.resolve("hazelcast-b.jar");
        Files.write(first,hazelcastJar("first",true)); Files.write(second,hazelcastJar("second",true));
        assertThatThrownBy(()->new Installer().install(directory,source)).isInstanceOf(IOException.class);
        assertThat(directory.resolve("runtime")).doesNotExist(); Files.delete(second); Files.writeString(first,"not a jar");
        assertThatThrownBy(()->new Installer().install(directory,source)).isInstanceOf(IOException.class);
        assertThat(directory.resolve("runtime")).doesNotExist();
    }
    @Test void localAddonAndSourceArchivesDoNotReplaceCore() throws Exception {
        Path directory=temp.resolve("install"),source=probeJar(); Files.createDirectories(directory);
        Files.write(directory.resolve("hazelcast-sql.jar"),hazelcastJar("addon",false));
        Files.write(directory.resolve("hazelcast-custom-sources.jar"),hazelcastJar("source archive",true));
        new Installer().install(directory,source);
        assertThat(directory.resolve("runtime/lib/hazelcast-bundled.jar")).isRegularFile();
        assertThat(directory.resolve("runtime/lib/hazelcast-custom-sources.jar")).doesNotExist();
    }
    @Test void splitClientOrCoreArchiveIsRejectedInsteadOfSilentlyFallingBack() throws Exception {
        Path directory=temp.resolve("install"),source=probeJar(); Files.createDirectories(directory);
        try(var jar=new JarOutputStream(Files.newOutputStream(directory.resolve("hazelcast-incomplete.jar")))) {
            jar.putNextEntry(new JarEntry("com/hazelcast/core/HazelcastInstance.class")); jar.write(new byte[]{1}); jar.closeEntry();
        }
        assertThatThrownBy(()->new Installer().install(directory,source)).isInstanceOf(IOException.class);
        assertThat(directory.resolve("runtime")).doesNotExist();
    }
    @Test void malformedRuntimeManifestIsAnInstallationFailure() throws Exception {
        Path source=probeJar(),directory=temp.resolve("install"); var installer=new Installer(); installer.install(directory,source);
        Files.writeString(directory.resolve("runtime/manifest.properties"),"format.version=1\nfile.bad=\\uZZZZ\n");
        assertThatThrownBy(()->installer.install(directory,source)).isInstanceOf(IOException.class);
    }
    @Test void archivePathsCannotEscapeRuntimeStaging() throws Exception {
        Path source=temp.resolve("malicious.jar"),directory=temp.resolve("install");
        try(var jar=new JarOutputStream(Files.newOutputStream(source))) {
            jar.putNextEntry(new JarEntry("BOOT-INF/classes/../../escaped.txt")); jar.write(new byte[]{1}); jar.closeEntry();
        }
        assertThatThrownBy(()->new Installer().install(directory,source)).isInstanceOf(IOException.class);
        assertThat(directory.resolve("escaped.txt")).doesNotExist(); assertThat(temp.resolve("escaped.txt")).doesNotExist();
        assertThat(directory.resolve("runtime")).doesNotExist();
        try(var files=Files.list(directory)) { assertThat(files.map(p->p.getFileName().toString()).toList()).noneMatch(name->name.startsWith(".hzmon-runtime-")); }
    }
    @Test void generatedScriptsUseTheirOwnDirectoryForwardArgumentsAndPreserveExitCode() throws Exception {
        Path directory=temp.resolve("install space & bang!"); new Installer().install(directory,probeJar());
        Files.writeString(directory.resolve("application.yml"),"edited: local-settings\n");
        Path elsewhere=Files.createDirectory(temp.resolve("elsewhere"));
        String shell=System.getenv("HZMON_TEST_SH");
        if(shell==null&&!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))shell="/bin/sh";
        for(String mode:List.of("collect","report","init","install")) {
            if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                Path caller=temp.resolve("caller-"+mode+".bat");
                Files.writeString(caller,"@echo off\r\nsetlocal DisableDelayedExpansion\r\ncall \""+directory.resolve(mode+".bat")+"\" \"--monitor.cluster-id=with spaces\"\r\nexit /b %errorlevel%\r\n");
                verify(new ProcessBuilder("cmd.exe","/d","/c",caller.toString()),elsewhere,directory,mode,false,false);
            }
            if(shell!=null)verify(new ProcessBuilder(shell,directory.resolve(mode+".sh").toString().replace('\\','/'),"--monitor.cluster-id=with spaces"),elsewhere,directory,mode,true,false);
        }
    }
    @Test void cronLaunchersUseAbsolutePathsWithoutPathOrJavaHome() throws Exception {
        String shell=System.getenv("HZMON_TEST_SH");
        if(shell==null&&!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))shell="/bin/sh";
        Assumptions.assumeTrue(shell!=null,"Set HZMON_TEST_SH to execute POSIX scripts on Windows");
        Path directory=temp.resolve("cron space ' & $ % , path"); new Installer().install(directory,probeJar());
        Files.writeString(directory.resolve("application.yml"),"edited: local-settings\n");
        Path elsewhere=Files.createDirectory(temp.resolve("cron-cwd"));
        for(String mode:List.of("collect","report","init","install"))
            verify(new ProcessBuilder(shell,directory.resolve(mode+".sh").toString().replace('\\','/'),"--monitor.cluster-id=with spaces"),elsewhere,directory,mode,true,true);
        Path log=temp.resolve("relative-java-home.log");
        var invalid=new ProcessBuilder(shell,directory.resolve("collect.sh").toString().replace('\\','/')).directory(elsewhere.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        invalid.environment().put("JAVA_HOME","relative-home"); invalid.environment().put("PATH","");
        Process p=invalid.start(); if(!p.waitFor(20,TimeUnit.SECONDS)) { p.destroyForcibly(); throw new AssertionError("Script did not exit"); }
        assertThat(p.exitValue()).withFailMessage(Files.readString(log)).isEqualTo(2);
    }
    private void verify(ProcessBuilder builder,Path cwd,Path installed,String mode,boolean shell,boolean cron) throws Exception {
        Path log=temp.resolve("launch-"+UUID.randomUUID()+".log");
        builder.directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        if(cron) {
            builder.environment().remove("JAVA_HOME"); builder.environment().remove("OS"); builder.environment().remove("BASH_ENV"); builder.environment().put("PATH","");
        } else builder.environment().put("JAVA_HOME",System.getProperty("java.home").replace('\\','/'));
        Process p=builder.start(); if(!p.waitFor(20,TimeUnit.SECONDS)) { p.destroyForcibly(); throw new AssertionError("Script did not exit"); }
        String output=Files.readString(log,java.nio.charset.Charset.defaultCharset());
        assertThat(p.exitValue()).withFailMessage(output).isEqualTo(7);
        assertThat(output.replace('\\','/')).contains("cwd="+installed.toString().replace('\\','/'),"arg=--monitor.cluster-id=with spaces","config=edited: local-settings");
        assertThat(output.lines().filter(line->Set.of("arg=--collect","arg=--report","arg=--init","arg=--install").contains(line)).toList()).containsExactly("arg=--"+mode);
        if(shell) {
            String option=output.lines().filter(line->line.startsWith("arg=--spring.config.location=")).findFirst().orElseThrow();
            String prefix="arg=--spring.config.location=classpath:/application.yml,";
            assertThat(option).startsWith(prefix+"file:/");
            assertThat(Path.of(java.net.URI.create(option.substring(prefix.length())))).isEqualTo(installed.resolve("application.yml").toAbsolutePath());
        } else assertThat(output).contains("arg=--spring.config.location=classpath:/application.yml,file:./application.yml");
    }
    private Path probeJar() throws IOException {
        return probeJar("source.jar","A");
    }
    private Path probeJar(String filename,String variant) throws IOException {
        Path source=temp.resolve(filename); if(Files.exists(source))return source;
        String main="com/inzent/apim/utilities/hazelcastmonitoring/MonitorApplication.class";
        Path compiled=temp.resolve("probe-classes");
        if(!Files.exists(compiled.resolve(main))) {
            Path code=temp.resolve("MonitorApplication.java");
            Files.writeString(code,"package com.inzent.apim.utilities.hazelcastmonitoring; public class MonitorApplication { public static void main(String[] args) throws Exception { LaunchProbe.main(args); } }");
            int result=javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",compiled.toString(),code.toString());
            if(result!=0)throw new IOException("Cannot compile launch probe");
        }
        var library=new ByteArrayOutputStream();
        String probe=LaunchProbe.class.getName().replace('.','/')+".class";
        try(var jar=new JarOutputStream(library);var bytes=LaunchProbe.class.getResourceAsStream("/"+probe)) {
            jar.putNextEntry(new JarEntry(probe)); Objects.requireNonNull(bytes).transferTo(jar); jar.closeEntry();
        }
        try(var jar=new JarOutputStream(Files.newOutputStream(source))) {
            jar.putNextEntry(new JarEntry("BOOT-INF/classes/"+main)); Files.copy(compiled.resolve(main),jar); jar.closeEntry();
            jar.putNextEntry(new JarEntry("BOOT-INF/classes/variant.txt")); jar.write(variant.getBytes(java.nio.charset.StandardCharsets.UTF_8)); jar.closeEntry();
            jar.putNextEntry(new JarEntry("BOOT-INF/lib/probe-dependency.jar")); library.writeTo(jar); jar.closeEntry();
            jar.putNextEntry(new JarEntry("BOOT-INF/lib/hazelcast-bundled.jar")); jar.write(hazelcastJar("bundled",true)); jar.closeEntry();
            jar.putNextEntry(new JarEntry("BOOT-INF/lib/hazelcast-sql.jar")); jar.write(hazelcastJar("bundled-addon",false)); jar.closeEntry();
        }
        return source;
    }
    private byte[] hazelcastJar(String variant,boolean core) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var jar=new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("variant.txt")); jar.write(variant.getBytes(java.nio.charset.StandardCharsets.UTF_8)); jar.closeEntry();
            if(core)for(String name:List.of("com/hazelcast/client/HazelcastClient.class","com/hazelcast/core/HazelcastInstance.class")) {
                jar.putNextEntry(new JarEntry(name)); jar.write(new byte[]{1}); jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
