package com.inzent.apim.utilities.hazelcastmonitoring.schema;

import javax.sql.DataSource;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.locks.ReentrantLock;
import liquibase.Liquibase;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/** Schema changes are always explicit; report only calls check(). */
public final class SchemaManager {
    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";
    private static final String HISTORY = "hzmon_databasechangelog";
    private static final ReentrantLock LOCAL_INIT = new ReentrantLock();
    private final DataSource source;
    public SchemaManager(DataSource source) { this.source = source; }

    public void update() {
        try (Connection c = source.getConnection()) { migrate(c); }
        catch (Exception e) { throw new IllegalStateException("Liquibase update failed", e); }
    }
    private void migrate(Connection c) throws Exception {
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
        database.setDatabaseChangeLogTableName(HISTORY);
        database.setDatabaseChangeLogLockTableName("hzmon_databasechangeloglock");
        try (var resources = new ClassLoaderResourceAccessor(); var liquibase = new Liquibase(CHANGELOG, resources, database)) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }
    public void check() {
        try (Connection c = source.getConnection(); Statement s = c.createStatement();
             ResultSet rows = s.executeQuery("select id from " + HISTORY + " where id = 'hzmon-001' and exectype in ('EXECUTED','RERAN')")) {
            if (!rows.next()) throw new IllegalStateException("Incompatible monitoring schema; run collection or init first");
        } catch (SQLException e) { throw new IllegalStateException("Monitoring schema is missing or incompatible", e); }
    }
    public void reset() {
        if (!LOCAL_INIT.tryLock()) throw new IllegalStateException("Another init is running");
        try (Connection c = source.getConnection()) {
            String url = c.getMetaData().getURL();
            if (url.startsWith("jdbc:postgresql:")) {
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("select pg_try_advisory_lock(748321937661)")) {
                    if (!r.next() || !r.getBoolean(1)) throw new IllegalStateException("Another init is running");
                }
                try { resetLocked(c); } finally {
                    try (Statement s = c.createStatement()) { s.execute("select pg_advisory_unlock(748321937661)"); }
                }
            } else if (url.startsWith("jdbc:h2:mem:")) {
                resetLocked(c);
            } else if (url.startsWith("jdbc:h2:file:")) {
                if (url.toUpperCase(java.util.Locale.ROOT).contains("AUTO_SERVER=TRUE")) throw new IllegalArgumentException("H2 AUTO_SERVER is unsupported");
                Path file = Path.of(url.substring("jdbc:h2:file:".length()).split(";", 2)[0]).toAbsolutePath().normalize();
                Files.createDirectories(file.getParent());
                Path guard = file.getParent().toRealPath().resolve(file.getFileName() + ".init.lock");
                try (FileChannel channel = FileChannel.open(guard, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock lock = channel.tryLock()) {
                    if (lock == null) throw new IllegalStateException("Another init is running");
                    resetLocked(c);
                }
            } else throw new IllegalArgumentException("Only PostgreSQL and H2 file/memory are supported");
        } catch (Exception e) { throw new IllegalStateException("Schema init failed; existing monitoring history may have been removed", e); }
        finally { LOCAL_INIT.unlock(); }
    }
    private void resetLocked(Connection c) throws SQLException { dropOwned(c); update(); check(); }
    private void dropOwned(Connection c) throws SQLException {
        // No CASCADE: an unknown external FK must block reset rather than delete external data.
        try (Statement s = c.createStatement()) {
            for (String table : new String[]{"hzmon_object_info", "hzmon_node_info", "hzmon_monitor_summary",
                    HISTORY, "hzmon_databasechangeloglock"}) s.execute("drop table if exists " + table);
        }
    }
}
