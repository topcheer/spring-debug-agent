package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Database migration diagnostic tools.
 * Inspects Flyway/Liquibase migration status, pending migrations, and history.
 * Conditional on Flyway or Liquibase being on classpath.
 */
public class MigrationInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get database migration status: applied migrations, schema version, migration history. Shows Flyway or Liquibase info depending on what's on classpath.")
    public Map<String, Object> getDbMigrations() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Try Flyway
        try {
            Class<?> flywayClass = Class.forName("org.flywaydb.core.Flyway");
            String[] flywayNames = ctx.getBeanNamesForType(flywayClass);
            if (flywayNames.length > 0) {
                Object flyway = ctx.getBean(flywayNames[0]);
                result.put("migrationTool", "Flyway");

                // Get info via reflection
                try {
                    // Flyway 9+ uses Configuration
                    Object config = ReflectionHelper.invokeMethod(flyway, "getConfiguration");
                    if (config != null) {
                        result.put("locations", ReflectionHelper.invokeMethod(config, "getLocations"));
                        result.put("baselineOnMigrate", ReflectionHelper.invokeMethod(config, "isBaselineOnMigrate"));
                        result.put("baselineVersion", String.valueOf(ReflectionHelper.invokeMethod(config, "getBaselineVersion")));
                    }
                } catch (Exception ignored) {}

                // Get migration info from DataSource
                try {
                    javax.sql.DataSource ds = ctx.getBean(javax.sql.DataSource.class);
                    try (java.sql.Connection conn = ds.getConnection()) {
                        // Check flyway_schema_history table
                        try (java.sql.Statement stmt = conn.createStatement();
                             java.sql.ResultSet rs = stmt.executeQuery(
                                     "SELECT version, description, type, success, installed_on " +
                                     "FROM flyway_schema_history ORDER BY installed_rank")) {
                            List<Map<String, Object>> migrations = new ArrayList<>();
                            while (rs.next()) {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("version", rs.getString("version"));
                                m.put("description", rs.getString("description"));
                                m.put("type", rs.getString("type"));
                                m.put("success", rs.getBoolean("success"));
                                m.put("installedOn", rs.getTimestamp("installed_on") != null
                                        ? rs.getTimestamp("installed_on").toString() : null);
                                migrations.add(m);
                            }
                            result.put("appliedMigrations", migrations);
                            result.put("migrationCount", migrations.size());

                            if (!migrations.isEmpty()) {
                                result.put("currentVersion",
                                        migrations.get(migrations.size() - 1).get("version"));
                            }
                        }
                    }
                } catch (Exception ignored) {}

                return result;
            }
        } catch (ClassNotFoundException ignored) {}

        // Try Liquibase
        try {
            Class<?> springLiquibaseClass = Class.forName(
                    "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration");
            String[] lbNames = ctx.getBeanNamesForType(
                    Class.forName("liquibase.integration.spring.SpringLiquibase"));
            if (lbNames.length > 0) {
                result.put("migrationTool", "Liquibase");
                Object liquibase = ctx.getBean(lbNames[0]);
                result.put("changeLog", ReflectionHelper.invokeMethod(liquibase, "getChangeLog"));

                // Check DATABASECHANGELOG table
                try {
                    javax.sql.DataSource ds = ctx.getBean(javax.sql.DataSource.class);
                    try (java.sql.Connection conn = ds.getConnection();
                         java.sql.Statement stmt = conn.createStatement();
                         java.sql.ResultSet rs = stmt.executeQuery(
                                 "SELECT id, author, filename, dateexecuted, orderexecuted, exectype " +
                                 "FROM DATABASECHANGELOG ORDER BY orderexecuted")) {
                        List<Map<String, Object>> changesets = new ArrayList<>();
                        while (rs.next()) {
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("id", rs.getString("id"));
                            c.put("author", rs.getString("author"));
                            c.put("file", rs.getString("filename"));
                            c.put("executed", rs.getTimestamp("dateexecuted") != null
                                    ? rs.getTimestamp("dateexecuted").toString() : null);
                            c.put("execType", rs.getString("exectype"));
                            changesets.add(c);
                        }
                        result.put("appliedChangesets", changesets);
                        result.put("changesetCount", changesets.size());
                    }
                } catch (Exception ignored) {}
            }
        } catch (ClassNotFoundException ignored) {}

        if (result.isEmpty()) {
            result.put("note", "Neither Flyway nor Liquibase detected on classpath.");
        }

        return result;
    }

    @DebugTool(description = "Get pending database migrations: scripts that exist but haven't been applied yet. Shows version, description, and SQL file path. Useful for diagnosing migration failures or version mismatches.")
    public List<Map<String, Object>> getPendingMigrations() {
        List<Map<String, Object>> pending = new ArrayList<>();

        try {
            // Get applied versions from DB
            javax.sql.DataSource ds = ctx.getBean(javax.sql.DataSource.class);
            Set<String> appliedVersions = new HashSet<>();

            try (java.sql.Connection conn = ds.getConnection()) {
                try (java.sql.Statement stmt = conn.createStatement();
                     java.sql.ResultSet rs = stmt.executeQuery(
                             "SELECT DISTINCT version FROM flyway_schema_history WHERE success = true")) {
                    while (rs.next()) {
                        appliedVersions.add(rs.getString("version"));
                    }
                } catch (Exception ignored) {}

                if (appliedVersions.isEmpty()) {
                    // Try Liquibase
                    try (java.sql.Statement stmt = conn.createStatement();
                         java.sql.ResultSet rs = stmt.executeQuery(
                                 "SELECT DISTINCT id FROM DATABASECHANGELOG")) {
                        while (rs.next()) {
                            appliedVersions.add(rs.getString("id"));
                        }
                    } catch (Exception ignored) {}
                }
            }

            // Scan classpath migration scripts
            try {
                String[] locations = {"db/migration", "db/changelog"};
                ClassLoader cl = Thread.currentThread().getContextClassLoader();

                for (String location : locations) {
                    java.net.URL resource = cl.getResource(location);
                    if (resource == null) continue;

                    java.io.File dir;
                    if (resource.getProtocol().equals("file")) {
                        dir = new java.io.File(resource.toURI());
                    } else {
                        continue;
                    }

                    File[] scripts = dir.listFiles((d, name) ->
                            name.endsWith(".sql") || name.endsWith(".xml") || name.endsWith(".yaml"));

                    if (scripts != null) {
                        for (File script : scripts) {
                            String name = script.getName();
                            // Extract version from Flyway-style names (V1__desc.sql)
                            String version = null;
                            String desc = name;
                            if (name.matches("V\\d+.*\\.sql")) {
                                version = name.replaceAll("^(V\\d+[_\\-].*?)\\.sql$", "$1");
                                desc = name.replaceAll("^V\\d+[_\\-](.*?)\\.sql$", "$1").replace("_", " ");
                            }

                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("file", name);
                            m.put("version", version);
                            m.put("description", desc);
                            m.put("path", location + "/" + name);
                            m.put("applied", version != null && appliedVersions.contains(version));
                            m.put("size", script.length());

                            if (version == null || !appliedVersions.contains(version)) {
                                pending.add(m);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            pending.add(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        if (pending.isEmpty()) {
            pending.add(Map.of("note", "All migrations have been applied. No pending migrations detected."));
        }

        return pending;
    }
}
