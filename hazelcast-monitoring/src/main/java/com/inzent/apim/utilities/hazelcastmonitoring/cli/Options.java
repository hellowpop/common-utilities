package com.inzent.apim.utilities.hazelcastmonitoring.cli;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.boot.convert.DurationStyle;

/** Validated once, before any database or client is opened. */
public record Options(Mode mode, boolean help, String clusterId, String clusterName,
        Path clientConfig, Path snapshotDir, Path reportFile, Instant start, Instant end, ZoneId reportZone,
        Duration connectTimeout, Duration operationTimeout, Duration collectionTimeout,
        String nodeNameAttribute, int maxRuns, int maxObjects, com.hazelcast.client.config.ClientConfig clientSettings) {
    public enum Mode { COLLECT, REPORT, INIT, INSTALL }
    private static final Set<String> MODE_FLAGS = Set.of("collect", "report", "init", "install");
    private static final Set<String> LEGACY_CONNECTION_KEYS=Set.of("monitor.cluster-name","monitor.members","monitor.lookup-mode","monitor.udp-group","monitor.udp-port");
    private static final Set<String> KEYS = Set.of("help", "monitor.cluster-id",
        "monitor.client-config", "monitor.snapshot-dir", "monitor.report-file", "monitor.start-time",
        "monitor.end-time", "monitor.report-zone", "monitor.connect-timeout", "monitor.operation-timeout",
        "monitor.collection-timeout", "monitor.node-name-attribute", "monitor.max-report-runs", "monitor.max-report-objects");

    private static Map<String,String> arguments(String[] args) {
        Map<String,String> cli = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) throw new IllegalArgumentException("Expected --key=value: " + arg);
            String[] pair = arg.substring(2).split("=", 2);
            if(LEGACY_CONNECTION_KEYS.contains(pair[0]))throw new IllegalArgumentException("Move "+pair[0]+" to the native monitor.client-config file");
            if(MODE_FLAGS.contains(pair[0])) {
                if(pair.length!=1)throw new IllegalArgumentException("Mode flags take no value: --"+pair[0]);
                if(cli.putIfAbsent("mode",pair[0])!=null)throw new IllegalArgumentException("Specify only one of --collect, --report, --init, --install");
                continue;
            }
            if (!KEYS.contains(pair[0]) && !pair[0].startsWith("spring.") && !pair[0].startsWith("logging."))
                throw new IllegalArgumentException("Unknown option: " + pair[0]);
            if (pair.length != 2 && !pair[0].equals("help")) throw new IllegalArgumentException("Expected --key=value: " + pair[0]);
            if (cli.putIfAbsent(pair[0], pair.length == 2 ? pair[1] : "true") != null)
                throw new IllegalArgumentException("Duplicate option: " + pair[0]);
        }
        return cli;
    }
    public static boolean helpRequested(String[] args) { return Boolean.parseBoolean(arguments(args).getOrDefault("help","false")); }
    public static boolean installRequested(String[] args) { return "install".equals(arguments(args).get("mode")); }
    public static Mode selectedMode(String[] args,Environment env) { return selectedMode(arguments(args),env); }
    private static Mode selectedMode(Map<String,String> cli,Environment env) {
        String mode=cli.getOrDefault("mode",env.getProperty("mode","collect"));
        return switch(mode) { case "collect" -> Mode.COLLECT; case "report" -> Mode.REPORT;
            case "init" -> Mode.INIT; case "install" -> Mode.INSTALL;
            default -> throw new IllegalArgumentException("Invalid configured mode; use collect, report, init, or install"); };
    }
    public static Options parse(String[] args, Environment env) {
        Map<String,String> cli=arguments(args);
        java.util.function.BiFunction<String,String,String> get = (key, fallback) -> cli.getOrDefault(key, env.getProperty(key, fallback));
        Mode selected = selectedMode(cli,env);
        Instant start = time(get.apply("monitor.start-time", null));
        Instant end = time(get.apply("monitor.end-time", null));
        if ((start != null || end != null) && selected != Mode.REPORT) throw new IllegalArgumentException("Time range is report-only");
        if (start != null && end != null && !start.isBefore(end)) throw new IllegalArgumentException("start-time must precede end-time");
        for(String key:LEGACY_CONNECTION_KEYS)
            if(env.getProperty(key)!=null)throw new IllegalArgumentException("Move "+key+" to the native monitor.client-config file");
        Path config=Path.of(get.apply("monitor.client-config","./hazelcast-client.yml")).toAbsolutePath().normalize();
        com.hazelcast.client.config.ClientConfig clientSettings=selected==Mode.COLLECT?
            com.inzent.apim.utilities.hazelcastmonitoring.client.ClientSettings.load(config):null;
        String name=clientSettings==null?"dev":clientSettings.getClusterName();
        name = required(name, 200, "cluster-name");
        String id = required(get.apply("monitor.cluster-id", name), 200, "cluster-id");
        String report = get.apply("monitor.report-file", null);
        return new Options(selected, Boolean.parseBoolean(get.apply("help", "false")), id, name,
            config, Path.of(get.apply("monitor.snapshot-dir", "./output/snapshots")).toAbsolutePath().normalize(),
            report == null ? null : Path.of(report).toAbsolutePath().normalize(), start, end,
            ZoneId.of(get.apply("monitor.report-zone", "Asia/Seoul")), duration(get.apply("monitor.connect-timeout", "30s")),
            duration(get.apply("monitor.operation-timeout", "10s")), duration(get.apply("monitor.collection-timeout", "120s")),
            required(get.apply("monitor.node-name-attribute", "monitor.node-name"),512,"node-name-attribute"),
            positive(get.apply("monitor.max-report-runs", "10000")), positive(get.apply("monitor.max-report-objects", "1000000")),clientSettings);
    }
    private static Instant time(String value) {
        return com.inzent.apim.utilities.hazelcastmonitoring.time.DateTimes.parse(value);
    }
    private static Duration duration(String value) {
        Duration d = DurationStyle.detectAndParse(value);
        if (d.isNegative() || d.isZero() || d.compareTo(Duration.ofHours(24)) > 0) throw new IllegalArgumentException("Timeout must be > 0 and <= 24h");
        return d;
    }
    private static int positive(String value) { int n = Integer.parseInt(value); if (n < 1) throw new IllegalArgumentException("Limit must be positive"); return n; }
    private static String required(String s, int max, String field) {
        if (s == null || s.isBlank() || s.length() > max) throw new IllegalArgumentException("Invalid " + field);
        return s;
    }
}
