package com.inzent.apim.utilities.hazelcastmonitoring;

import com.inzent.apim.utilities.hazelcastmonitoring.schema.SchemaManager;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;

/** Run with -Ppostgres-tests and HZMON_TEST_PG_URL for a disposable local PostgreSQL database. */
class PostgresPersistenceIT extends PersistenceTest {
    private String schemaName;
    private DriverManagerDataSource admin;
    @Override @BeforeEach void setup() {
        String url=System.getenv("HZMON_TEST_PG_URL");
        if(url==null)throw new IllegalStateException("HZMON_TEST_PG_URL is required for postgres-tests");
        admin=new DriverManagerDataSource(url,"postgres",""); schemaName="hzmon_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=admin.getConnection();var s=c.createStatement()) { s.execute("create schema "+schemaName); }
        catch(Exception e) { throw new IllegalStateException(e); }
        db=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schemaName,"postgres","");
        schema=new SchemaManager(db);
    }
    @AfterEach void cleanup() throws Exception {
        if(schemaName!=null)try(var c=admin.getConnection();var s=c.createStatement()) { s.execute("drop schema "+schemaName+" cascade"); }
    }
}
