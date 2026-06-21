package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * Seata distributed transaction diagnostic tools.
 * Inspects global transactions, branch transactions, locks, undo logs, and TC server connectivity.
 * Conditional on seata-spring-boot-starter being on classpath.
 */
public class SeataInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Inspect Seata global transaction configuration: application ID, transaction service group, "
            + "data source proxy mode (AT/TCC/SAGA/XA), RM and TM role, registry type, and service addresses (TC cluster). "
            + "Useful for verifying distributed transaction setup and diagnosing TC connection failures.")
    public Map<String, Object> getSeataGlobalConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        // RootContext (active transaction info)
        try {
            Class<?> rootContextClass = Class.forName("io.seata.core.context.RootContext");
            java.lang.reflect.Method getXID = rootContextClass.getMethod("getXID");
            Object xid = getXID.invoke(null);
            result.put("currentXid", xid != null ? xid : "none (no active global transaction)");

            java.lang.reflect.Method getBranchType = rootContextClass.getMethod("getBranchType");
            Object branchType = getBranchType.invoke(null);
            result.put("currentBranchType", branchType != null ? branchType.toString() : "AT");
        } catch (Exception ignored) {}

        // Spring properties
        Map<String, String> props = new LinkedHashMap<>();
        String[] propNames = {
                "seata.enabled", "seata.application-id", "seata.tx-service-group",
                "seata.data-source-proxy-mode", "seata.enable-auto-data-source-proxy",
                "seata.use-jdk-proxy",
                "seata.service.vgroup-mapping",
                "seata.service.grouplist",
                "seata.registry.type", "seata.registry.nacos.server-addr",
                "seata.config.type", "seata.config.nacos.server-addr",
                "seata.client.rm.report-success-enable",
                "seata.client.rm.table-meta-checker-enable",
                "seata.client.tm.commit-retry-count",
                "seata.client.tm.rollback-retry-count"
        };
        for (String p : propNames) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null) props.put(p, val);
        }
        if (!props.isEmpty()) result.put("springProperties", props);

        // DataSource proxy
        try {
            List<?> proxies = ReflectionHelper.getBeansOfType(ctx,
                    "io.seata.rm.datasource.DataSourceProxy");
            if (!proxies.isEmpty()) {
                List<String> proxyClasses = new ArrayList<>();
                for (Object p : proxies) {
                    Object targetDataSource = ReflectionHelper.invokeMethod(p, "getTargetDataSource");
                    proxyClasses.add(targetDataSource != null
                            ? targetDataSource.getClass().getSimpleName()
                            : p.getClass().getSimpleName());
                }
                result.put("dataSourceProxies", proxyClasses);
            }

            // Seata AutoConfiguration
            Object failureHandler = ReflectionHelper.getFirstBeanOfType(ctx,
                    "io.seata.spring.annotation.GlobalTransactionScanner");
            if (failureHandler != null) {
                result.put("scannerPresent", true);
                Object applicationId = ReflectionHelper.getFieldValue(failureHandler, "applicationId");
                if (applicationId != null) result.put("applicationId", applicationId);
                Object txServiceGroup = ReflectionHelper.getFieldValue(failureHandler, "txServiceGroup");
                if (txServiceGroup != null) result.put("txServiceGroup", txServiceGroup);
            }
        } catch (Exception ignored) {}

        if (result.isEmpty()) {
            result.put("status", "not_configured");
            result.put("hint", "No Seata configuration found. Add seata-spring-boot-starter.");
        }

        return result;
    }

    @DebugTool(description = "Inspect Seata client RM (Resource Manager) configuration: branch transaction "
            + "report interval, async commit buffer limit, table meta checker interval, "
            + "sql parser type (druid/antlr), and lock retry strategy. "
            + "Useful for debugging branch transaction commit/rollback issues.")
    public Map<String, Object> getSeataRmConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Class<?> rmConfigClass = Class.forName("io.seata.rm.RMClient");
            // Get RM client config from system properties or Spring
            String[] rmProps = {
                    "seata.client.rm.async-commit-buffer-limit",
                    "seata.client.rm.report-retry-count",
                    "seata.client.rm.table-meta-checker-enable",
                    "seata.client.rm.table-meta-checker-interval",
                    "seata.client.rm.report-success-enable",
                    "seata.client.rm.saga-branch-register-enable",
                    "seata.client.rm.saga-json-parser",
                    "seata.client.rm.sql-parser-type",
                    "seata.client.rm.lock.retry-interval",
                    "seata.client.rm.lock.retry-times",
                    "seata.client.rm.lock.retry-policy-branch-rollback-on-conflict"
            };
            Map<String, String> props = new LinkedHashMap<>();
            for (String p : rmProps) {
                String val = ctx.getEnvironment().getProperty(p);
                if (val != null) props.put(p.replace("seata.client.rm.", ""), val);
            }
            if (!props.isEmpty()) result.put("rmConfig", props);

            // DataSourceProxy entries (one per data source)
            List<?> proxies = ReflectionHelper.getBeansOfType(ctx,
                    "io.seata.rm.datasource.DataSourceProxy");
            result.put("dataSourceProxyCount", proxies.size());

            // Undo log table config
            String undoTable = ctx.getEnvironment().getProperty(
                    "seata.client.rm.undo.log-table", "undo_log");
            result.put("undoLogTable", undoTable);

            String undoDataValidation = ctx.getEnvironment().getProperty(
                    "seata.client.rm.undo.data-validation", "true");
            result.put("undoDataValidation", undoDataValidation);

            String undoLogSerialization = ctx.getEnvironment().getProperty(
                    "seata.client.rm.undo.log-serialization", "jackson");
            result.put("undoLogSerialization", undoLogSerialization);

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Inspect Seata TM (Transaction Manager) configuration: commit retry count, "
            + "rollback retry count, default global transaction timeout, and interceptor settings. "
            + "Useful for debugging global transaction timeout or retry behavior.")
    public Map<String, Object> getSeataTmConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            String[] tmProps = {
                    "seata.client.tm.commit-retry-count",
                    "seata.client.tm.rollback-retry-count",
                    "seata.client.tm.default-global-transaction-timeout",
                    "seata.client.tm.degrade-check",
                    "seata.client.tm.degrade-check-period",
                    "seata.client.tm.degrade-check-allow-times",
                    "seata.client.tm.interceptor-order"
            };
            Map<String, String> props = new LinkedHashMap<>();
            for (String p : tmProps) {
                String val = ctx.getEnvironment().getProperty(p);
                if (val != null) props.put(p.replace("seata.client.tm.", ""), val);
            }
            result.put("tmConfig", props);

            // Check for GlobalTransactional interceptor
            Object interceptor = ReflectionHelper.getFirstBeanOfType(ctx,
                    "io.seata.spring.annotation.GlobalTransactionalInterceptor");
            if (interceptor != null) {
                result.put("interceptorPresent", true);
                Object flavor = ReflectionHelper.getFieldValue(interceptor, "failureHandler");
                if (flavor != null) {
                    result.put("failureHandler", flavor.getClass().getSimpleName());
                }
            }

            // Default timeout
            String timeout = ctx.getEnvironment().getProperty(
                    "seata.client.tm.default-global-transaction-timeout", "60000");
            result.put("defaultGlobalTransactionTimeoutMs", timeout);

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Check Seata TC (Transaction Coordinator) server connectivity: "
            + "registry type, TC server address, active connections, and whether the TM/RM clients "
            + "are successfully registered with the TC. "
            + "Essential for diagnosing 'can not connect to TC server' or transaction timeout issues.")
    public Map<String, Object> getSeataTcStatus() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Check TM client
            Object tmClient = ReflectionHelper.getFirstBeanOfType(ctx, "io.seata.tm.TMClient");
            if (tmClient != null) {
                result.put("tmClientPresent", true);
                Object address = ReflectionHelper.getFieldValue(tmClient, "transactionServiceGroup");
                if (address != null) result.put("tmServiceGroup", address);
            }

            // Check RM client
            Object rmClient = ReflectionHelper.getFirstBeanOfType(ctx, "io.seata.rm.RMClient");
            if (rmClient != null) {
                result.put("rmClientPresent", true);
            }

            // Registry config
            String registryType = ctx.getEnvironment().getProperty("seata.registry.type", "file");
            result.put("registryType", registryType);

            if ("nacos".equalsIgnoreCase(registryType)) {
                String nacosAddr = ctx.getEnvironment().getProperty("seata.registry.nacos.server-addr");
                result.put("nacosServerAddr", nacosAddr);
                String nacosGroup = ctx.getEnvironment().getProperty("seata.registry.nacos.group", "SEATA_GROUP");
                result.put("nacosGroup", nacosGroup);
            } else if ("file".equalsIgnoreCase(registryType)) {
                String grouplist = ctx.getEnvironment().getProperty("seata.service.grouplist");
                result.put("grouplist", grouplist);
            }

            // VGroup mapping
            String vgroupMapping = ctx.getEnvironment().getProperty("seata.service.vgroup-mapping");
            if (vgroupMapping != null) result.put("vgroupMapping", vgroupMapping);

            // Transport config
            Map<String, String> transport = new LinkedHashMap<>();
            String[] tProps = {
                    "seata.transport.type", "seata.transport.server",
                    "seata.transport.heartbeat", "seata.transport.enable-client-batch-send-request",
                    "seata.transport.thread-factory.boss-thread-prefix",
                    "seata.transport.thread-factory.worker-thread-prefix",
                    "seata.transport.shutdown.wait"
            };
            for (String p : tProps) {
                String val = ctx.getEnvironment().getProperty(p);
                if (val != null) transport.put(p.replace("seata.transport.", ""), val);
            }
            if (!transport.isEmpty()) result.put("transportConfig", transport);

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }
}
