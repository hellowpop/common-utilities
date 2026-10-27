package com.inzent.apim.utilities.hazelcastmonitoring.install;

import com.inzent.apim.utilities.hazelcastmonitoring.MonitorApplication;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.regex.*;
import org.springframework.boot.system.ApplicationHome;

/** Creates a classpath runtime and launchers without opening application settings or a database. */
public final class Installer {
    private static final List<String> TEMPLATES=List.of("application.yml","collect.bat","collect.sh","report.bat","report.sh","init.bat","init.sh","install.bat","install.sh");
    public static Path runningSource() throws IOException {
        File source=new ApplicationHome(MonitorApplication.class).getSource();
        if(source==null||(!source.isFile()&&!source.isDirectory()))
            throw new IOException("Install requires a packaged JAR or installed runtime/classes");
        return source.toPath().toRealPath();
    }
    public Map<String,String> install(Path directory,Path sourceLocation) throws IOException {
        Path source=sourceLocation.toRealPath();
        Path target=directory.toAbsolutePath().normalize(); Files.createDirectories(target); target=target.toRealPath();
        // Check all known collisions before publishing anything.
        for(String name:TEMPLATES) {
            Path file=target.resolve(name);
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(file))throw new IOException("Install target is not a regular file: "+file);
        }
        Map<String,String> result=new LinkedHashMap<>();
        var runtime=new RuntimeLayout().install(target,source);
        result.put("runtime",runtime.status());
        if(runtime.localLibrary()!=null)result.put("runtime/lib/"+runtime.localLibrary(),"HAZELCAST_OVERRIDE");
        for(String name:TEMPLATES) {
            Path file=target.resolve(name);
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
                if(!Files.isRegularFile(file))throw new IOException("Install target is not a regular file: "+file);
                result.put(name,"PRESERVED"); continue;
            }
            String template=name.equals("application.yml")?name:(name.endsWith(".bat")?"launcher.bat.template":"launcher.sh.template");
            try(var resource=Installer.class.getResourceAsStream("/install/"+template)) {
                if(resource==null)throw new IOException("Missing install template: "+name);
                String text=new String(resource.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n");
                if(name.endsWith(".sh"))text=renderShell(text,target,name.substring(0,name.indexOf('.')));
                else if(name.endsWith(".bat"))text=text.replace("@MODE@",name.substring(0,name.indexOf('.'))).replace("\n","\r\n");
                try(var bytes=new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) { create(file,bytes,name.endsWith(".sh")); }
                result.put(name,"CREATED");
            } catch(FileAlreadyExistsException race) {
                if(!Files.isRegularFile(file))throw new IOException("Install target changed to a non-file: "+file,race);
                result.put(name,"PRESERVED");
            }
        }
        return Collections.unmodifiableMap(result);
    }
    private String renderShell(String template,Path target,String mode) {
        Path java=Path.of(System.getProperty("java.home"),"bin",File.separatorChar=='\\'?"java.exe":"java").toAbsolutePath().normalize();
        String config=target.resolve("application.yml").toUri().toASCIIString().replace(",","%2C").replace(";","%3B");
        Map<String,String> values=Map.of("MODE",mode,"INSTALL_DIR",shellLiteral(target.toString().replace('\\','/')),
            "JAVA_EXEC",shellLiteral(java.toString().replace('\\','/')),"CONFIG_LOCATION",shellLiteral("classpath:/application.yml,"+config));
        return Pattern.compile("@(MODE|INSTALL_DIR|JAVA_EXEC|CONFIG_LOCATION)@").matcher(template)
            .replaceAll(match->Matcher.quoteReplacement(values.get(match.group(1))));
    }
    private String shellLiteral(String value) { return "'"+value.replace("'","'\"'\"'")+"'"; }
    private void create(Path target,InputStream bytes,boolean executable) throws IOException {
        Path temp=Files.createTempFile(target.getParent(),".hzmon-install-",".tmp");
        try {
            Files.copy(bytes,temp,StandardCopyOption.REPLACE_EXISTING);
            if(executable&&temp.getFileSystem().supportedFileAttributeViews().contains("posix"))
                Files.setPosixFilePermissions(temp,EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,PosixFilePermission.GROUP_EXECUTE,PosixFilePermission.OTHERS_READ,PosixFilePermission.OTHERS_EXECUTE));
            try { Files.createLink(target,temp); }
            catch(FileAlreadyExistsException exists) { throw exists; }
            catch(UnsupportedOperationException|IOException unsupported) { Files.move(temp,target); }
        } finally { Files.deleteIfExists(temp); }
    }
}
