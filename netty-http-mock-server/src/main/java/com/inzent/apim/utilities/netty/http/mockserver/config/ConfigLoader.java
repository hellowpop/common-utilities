package com.inzent.apim.utilities.netty.http.mockserver.config;

import org.snakeyaml.engine.v2.api.*;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Loads a single YAML document without Java object construction or recursive substitution. */
public final class ConfigLoader {
    private static final int MAX_BYTES = 1_048_576;
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^{}]*))?}");
    private ConfigLoader() {}

    public static ServerConfig load(String[] args) {
        return load(args, System.getProperties(), System.getenv());
    }

    public static ServerConfig load(String[] args, Properties properties, Map<String,String> environment) {
        Map<String,String> cli = new LinkedHashMap<>();
        for (String arg : args) {
            int equals = arg.indexOf('=');
            if (!arg.startsWith("--") || equals <= 2) throw bad("Invalid argument format; use --key=value");
            if (cli.putIfAbsent(arg.substring(2, equals), arg.substring(equals + 1)) != null)
                throw bad("Duplicate argument key");
        }
        String external = cli.remove("config");
        String yaml;
        try (InputStream in = external == null ? ConfigLoader.class.getResourceAsStream("/com/inzent/apim/utilities/netty/http/mockserver/application.yml")
                : Files.newInputStream(Path.of(external))) {
            if (in == null) throw bad("Configuration resource not found");
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw bad("Configuration exceeds 1 MiB");
            yaml = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | InvalidPathException ex) { throw bad("Cannot read configuration file"); }

        Map<String,Object> values = defaults();
        Set<String> consumed = new HashSet<>();
        try {
            LoadSettings settings = LoadSettings.builder().setAllowDuplicateKeys(false)
                    .setAllowRecursiveKeys(false).setMaxAliasesForCollections(0)
                    .setCodePointLimit(MAX_BYTES).build();
            int depth = 0;
            for (Event event : new Parse(settings).parseString(yaml)) {
                if (depth == 0 && event instanceof ScalarEvent scalar
                        && (!scalar.getValue().isEmpty() || scalar.getTag().isPresent()))
                    throw bad("Configuration root must be a map");
                if (event.getEventId() == Event.ID.MappingStart || event.getEventId() == Event.ID.SequenceStart) {
                    if (++depth > 40) throw bad("Configuration nesting exceeds 40");
                } else if (event.getEventId() == Event.ID.MappingEnd || event.getEventId() == Event.ID.SequenceEnd) depth--;
                if (event.getEventId() == Event.ID.Alias) throw bad("YAML aliases are not supported");
            }
            Object root = new Load(settings).loadFromString(yaml);
            if (root != null) {
                if (!(root instanceof Map<?,?> map)) throw bad("Configuration root must be a map");
                flatten("", map, values, cli, properties, environment, consumed);
            }
        } catch (RuntimeException ex) {
            // Parser diagnostics may contain raw configuration values. Keep them out of startup logs.
            if (ex instanceof IllegalArgumentException) throw ex;
            throw bad("Invalid YAML configuration");
        }
        for (var entry : cli.entrySet()) {
            String key = entry.getKey();
            if (key.contains(".")) {
                if (!values.containsKey(key) || key.equals("mock.scan-packages")) throw bad("Unknown override: " + key);
                values.put(key, entry.getValue());
            } else if (!consumed.contains(key)) throw bad("Unused argument variable: " + key);
        }
        return new ServerConfig(text(values,"server.host"), number(values,"server.port"),
                number(values,"server.boss-threads"), number(values,"server.worker-threads"),
                number(values,"server.backlog"), bool(values,"server.reuse-address"),
                bool(values,"server.tcp-no-delay"), bool(values,"server.keep-alive"),
                number(values,"server.max-connections"), number(values,"server.max-initial-line-length"),
                number(values,"server.max-header-size"), number(values,"server.max-content-length"),
                number(values,"server.request-timeout-ms"), number(values,"server.write-buffer-low-water-mark"),
                number(values,"server.write-buffer-high-water-mark"), number(values,"server.max-pending-responses"),
                number(values,"server.shutdown-timeout-ms"), text(values,"mock.header-name"),
                text(values,"mock.default-action"), packages(values.get("mock.scan-packages")));
    }

    private static Map<String,Object> defaults() {
        Map<String,Object> v = new LinkedHashMap<>();
        v.put("server.host","127.0.0.1"); v.put("server.port",8080);
        v.put("server.boss-threads",1); v.put("server.worker-threads",0); v.put("server.backlog",128);
        v.put("server.reuse-address",true); v.put("server.tcp-no-delay",true); v.put("server.keep-alive",true);
        v.put("server.max-connections",100); v.put("server.max-initial-line-length",4096);
        v.put("server.max-header-size",8192); v.put("server.max-content-length",1048576);
        v.put("server.request-timeout-ms",30000); v.put("server.write-buffer-low-water-mark",32768);
        v.put("server.write-buffer-high-water-mark",65536); v.put("server.max-pending-responses",8);
        v.put("server.shutdown-timeout-ms",10000); v.put("mock.header-name","x-response-action");
        v.put("mock.default-action","default");
        v.put("mock.scan-packages",List.of("com.inzent.apim.utilities.netty.http.mockserver.processor"));
        return v;
    }

    private static void flatten(String prefix, Map<?,?> map, Map<String,Object> values, Map<String,String> cli,
            Properties properties, Map<String,String> environment, Set<String> consumed) {
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.contains(".")) throw bad("Invalid configuration key");
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = entry.getValue();
            if (value instanceof Map<?,?> nested) {
                if (!path.equals("server") && !path.equals("mock")) throw bad("Unknown section: " + path);
                flatten(path,nested,values,cli,properties,environment,consumed);
            } else {
                if (!values.containsKey(path)) throw bad("Unknown configuration: " + path);
                if (value == null) throw bad("Null configuration: " + path);
                if (value instanceof String string) value = substitute(string,cli,properties,environment,consumed);
                else if (value instanceof List<?> list) {
                    List<Object> resolved = new ArrayList<>();
                    for (Object item : list) resolved.add(item instanceof String s
                            ? substitute(s,cli,properties,environment,consumed) : item);
                    value = resolved;
                }
                values.put(path,value);
            }
        }
    }
    private static String substitute(String input, Map<String,String> cli, Properties props,
            Map<String,String> env, Set<String> consumed) {
        Matcher matcher = VARIABLE.matcher(input); StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = cli.get(name);
            if (replacement != null) consumed.add(name);
            else replacement = props.getProperty(name);
            if (replacement == null) replacement = env.get(name);
            if (replacement == null) replacement = matcher.group(2);
            if (replacement == null || replacement.contains("${")) throw bad("Unresolved or recursive configuration variable");
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        if (result.indexOf("${") >= 0) throw bad("Malformed configuration variable");
        return result.toString();
    }
    private static String text(Map<String,Object> v, String key) {
        if (!(v.get(key) instanceof String s)) throw bad("Expected string: " + key);
        return s;
    }
    private static int number(Map<String,Object> v, String key) {
        Object raw = v.get(key);
        if (!(raw instanceof Number || raw instanceof String) || !raw.toString().matches("[0-9]+"))
            throw bad("Expected nonnegative integer: " + key);
        try { return Integer.parseInt(raw.toString()); } catch (NumberFormatException ex) { throw bad("Integer overflow: " + key); }
    }
    private static boolean bool(Map<String,Object> v, String key) {
        Object raw = v.get(key);
        if (Boolean.TRUE.equals(raw) || "true".equals(raw)) return true;
        if (Boolean.FALSE.equals(raw) || "false".equals(raw)) return false;
        throw bad("Expected boolean: " + key);
    }
    private static List<String> packages(Object value) {
        if (!(value instanceof List<?> list)) throw bad("Expected list: mock.scan-packages");
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String name)) throw bad("Expected package name");
            result.add(name);
        }
        return result;
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
