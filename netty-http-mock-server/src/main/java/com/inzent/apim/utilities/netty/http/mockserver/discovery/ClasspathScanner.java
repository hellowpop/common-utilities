package com.inzent.apim.utilities.netty.http.mockserver.discovery;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.Stream;

/** Scans ordinary directories and flat JARs, including the shaded executable JAR. */
public final class ClasspathScanner {
    private ClasspathScanner() {}
    public static List<Class<?>> scan(List<String> packages) {
        if (packages == null || packages.isEmpty()) throw new IllegalArgumentException("No scan packages");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = ClasspathScanner.class.getClassLoader();
        SortedSet<String> names = new TreeSet<>();
        try {
            for (String pkg : packages) {
                if (!pkg.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
                    throw new IllegalArgumentException("Invalid scan package");
                String prefix = pkg.replace('.', '/') + "/";
                Enumeration<URL> resources = loader.getResources(prefix.substring(0,prefix.length()-1));
                while (resources.hasMoreElements()) {
                    URL resource = resources.nextElement();
                    if (resource.getProtocol().equals("file")) directory(Path.of(resource.toURI()),pkg,names);
                    else if (resource.getProtocol().equals("jar")) {
                        JarURLConnection connection = (JarURLConnection)resource.openConnection();
                        connection.setUseCaches(false);
                        try (JarFile jar = connection.getJarFile()) { jar(jar,prefix,names); }
                    } else throw new IllegalArgumentException("Unsupported classpath protocol");
                }
                for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                    if (entry.isEmpty()) continue;
                    Path path = Path.of(entry);
                    if (Files.isDirectory(path)) directory(path.resolve(prefix),pkg,names);
                    else if (Files.isRegularFile(path) && entry.endsWith(".jar")) {
                        try (JarFile jar = new JarFile(path.toFile())) { jar(jar,prefix,names); }
                    }
                }
            }
            List<Class<?>> result = new ArrayList<>();
            for (String name : names) result.add(Class.forName(name,false,loader));
            return List.copyOf(result);
        } catch (IOException | URISyntaxException | ReflectiveOperationException | LinkageError ex) {
            throw new IllegalArgumentException("Action classpath scan failed",ex);
        }
    }
    private static void directory(Path root, String pkg, Set<String> names) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString().replace(File.separatorChar,'.');
                if (valid(relative)) names.add(pkg + "." + relative.substring(0,relative.length()-6));
            }
        }
    }
    private static void jar(JarFile jar, String prefix, Set<String> names) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(prefix) && valid(name)) names.add(name.substring(0,name.length()-6).replace('/','.'));
        }
    }
    private static boolean valid(String name) {
        return name.endsWith(".class") && !name.endsWith("module-info.class") && !name.endsWith("package-info.class")
                && !name.startsWith("META-INF/");
    }
}
