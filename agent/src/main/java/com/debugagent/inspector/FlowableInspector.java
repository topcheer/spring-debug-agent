package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * Flowable BPM diagnostic tools.
 * Inspects process definitions, active process instances, tasks, and execution history.
 * Conditional on flowable-spring-boot-starter being on classpath.
 */
public class FlowableInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    private Object getProcessEngine() {
        Object engine = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.flowable.engine.ProcessEngine");
        if (engine == null) {
            engine = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.flowable.engine.impl.ProcessEngineImpl");
        }
        return engine;
    }

    private Object getRepositoryService() {
        return ReflectionHelper.getFirstBeanOfType(ctx,
                "org.flowable.engine.RepositoryService");
    }

    private Object getRuntimeService() {
        return ReflectionHelper.getFirstBeanOfType(ctx,
                "org.flowable.engine.RuntimeService");
    }

    private Object getTaskService() {
        return ReflectionHelper.getFirstBeanOfType(ctx,
                "org.flowable.engine.TaskService");
    }

    private Object getHistoryService() {
        return ReflectionHelper.getFirstBeanOfType(ctx,
                "org.flowable.engine.HistoryService");
    }

    @DebugTool(description = "List all deployed Flowable process definitions: key, name, version, "
            + "deployment ID, category, suspension status, and BPMN resource name. "
            + "Useful for verifying process deployment and diagnosing version conflicts.")
    public List<Map<String, Object>> getFlowableProcessDefinitions() {
        List<Map<String, Object>> result = new ArrayList<>();

        Object repoService = getRepositoryService();
        if (repoService == null) {
            result.add(Map.of("error", "No Flowable RepositoryService found. Add flowable-spring-boot-starter."));
            return result;
        }

        try {
            Object query = ReflectionHelper.invokeMethod(repoService, "createProcessDefinitionQuery");
            if (query != null) {
                query = ReflectionHelper.invokeMethod(query, "latestVersion");
                query = ReflectionHelper.invokeMethod(query, "orderByProcessDefinitionName");
                query = ReflectionHelper.invokeMethod(query, "asc");
                Object definitions = ReflectionHelper.invokeMethod(query, "list");

                if (definitions instanceof List) {
                    for (Object def : (List<?>) definitions) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("id", ReflectionHelper.invokeMethod(def, "getId"));
                        d.put("key", ReflectionHelper.invokeMethod(def, "getKey"));
                        d.put("name", ReflectionHelper.invokeMethod(def, "getName"));
                        d.put("version", ReflectionHelper.invokeMethod(def, "getVersion"));
                        d.put("deploymentId", ReflectionHelper.invokeMethod(def, "getDeploymentId"));
                        d.put("category", ReflectionHelper.invokeMethod(def, "getCategory"));
                        d.put("suspended", ReflectionHelper.invokeMethod(def, "isSuspended"));
                        d.put("resourceName", ReflectionHelper.invokeMethod(def, "getResourceName"));
                        d.put("tenantId", ReflectionHelper.invokeMethod(def, "getTenantId"));
                        result.add(d);
                    }
                }
            }
        } catch (Exception e) {
            result.add(Map.of("error", "Failed to query process definitions: " + e.getClass().getSimpleName()));
        }

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_definitions",
                    "hint", "No process definitions deployed. Deploy a .bpmn20.xml or .bpmn file."));
        }

        return result;
    }

    @DebugTool(description = "List active Flowable process instances: instance ID, process definition key, "
            + "business key, start time, duration, current activity, and variables. "
            + "Useful for diagnosing stuck processes or identifying long-running workflows.")
    public List<Map<String, Object>> getFlowableActiveInstances() {
        List<Map<String, Object>> result = new ArrayList<>();

        Object runtimeService = getRuntimeService();
        if (runtimeService == null) {
            result.add(Map.of("error", "No Flowable RuntimeService found"));
            return result;
        }

        try {
            Object query = ReflectionHelper.invokeMethod(runtimeService, "createProcessInstanceQuery");
            query = ReflectionHelper.invokeMethod(query, "active");
            query = ReflectionHelper.invokeMethod(query, "orderByProcessInstanceId");
            query = ReflectionHelper.invokeMethod(query, "desc");

            // Limit results
            query = ReflectionHelper.invokeMethod(query, "listPage", 0, 50);

            if (query instanceof List) {
                for (Object inst : (List<?>) query) {
                    Map<String, Object> i = new LinkedHashMap<>();
                    i.put("id", ReflectionHelper.invokeMethod(inst, "getId"));
                    i.put("processDefinitionKey", ReflectionHelper.invokeMethod(inst, "getProcessDefinitionKey"));
                    i.put("processDefinitionId", ReflectionHelper.invokeMethod(inst, "getProcessDefinitionId"));
                    i.put("businessKey", ReflectionHelper.invokeMethod(inst, "getBusinessKey"));
                    i.put("startTime", ReflectionHelper.invokeMethod(inst, "getStartTime"));
                    Object suspended = ReflectionHelper.invokeMethod(inst, "isSuspended");
                    i.put("suspended", suspended);
                    i.put("tenantId", ReflectionHelper.invokeMethod(inst, "getTenantId"));
                    result.add(i);
                }
            }
        } catch (Exception e) {
            result.add(Map.of("error", "Failed to query process instances: " + e.getClass().getSimpleName()));
        }

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_active_instances"));
        }

        return result;
    }

    @DebugTool(description = "List active Flowable user tasks: task ID, name, assignee, creation time, "
            + "process instance ID, and task variables. "
            + "Useful for diagnosing task assignment issues or identifying stale tasks.")
    public List<Map<String, Object>> getFlowableTasks() {
        List<Map<String, Object>> result = new ArrayList<>();

        Object taskService = getTaskService();
        if (taskService == null) {
            result.add(Map.of("error", "No Flowable TaskService found"));
            return result;
        }

        try {
            Object query = ReflectionHelper.invokeMethod(taskService, "createTaskQuery");
            query = ReflectionHelper.invokeMethod(query, "active");
            query = ReflectionHelper.invokeMethod(query, "orderByTaskCreateTime");
            query = ReflectionHelper.invokeMethod(query, "desc");
            Object tasks = ReflectionHelper.invokeMethod(query, "listPage", 0, 50);

            if (tasks instanceof List) {
                for (Object task : (List<?>) tasks) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("id", ReflectionHelper.invokeMethod(task, "getId"));
                    t.put("name", ReflectionHelper.invokeMethod(task, "getName"));
                    t.put("assignee", ReflectionHelper.invokeMethod(task, "getAssignee"));
                    Object owner = ReflectionHelper.invokeMethod(task, "getOwner");
                    if (owner != null) t.put("owner", owner);
                    t.put("createTime", ReflectionHelper.invokeMethod(task, "getCreateTime"));
                    t.put("processInstanceId", ReflectionHelper.invokeMethod(task, "getProcessInstanceId"));
                    t.put("executionId", ReflectionHelper.invokeMethod(task, "getExecutionId"));
                    Object dueDate = ReflectionHelper.invokeMethod(task, "getDueDate");
                    if (dueDate != null) t.put("dueDate", dueDate);
                    t.put("priority", ReflectionHelper.invokeMethod(task, "getPriority"));
                    t.put("tenantId", ReflectionHelper.invokeMethod(task, "getTenantId"));
                    result.add(t);
                }
            }
        } catch (Exception e) {
            result.add(Map.of("error", "Failed to query tasks: " + e.getClass().getSimpleName()));
        }

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_tasks"));
        }

        return result;
    }

    @DebugTool(description = "Get Flowable process engine configuration: engine name, database type, "
            + "database schema update strategy, job executor settings, async executor, history level, "
            + "and deployment resources. Useful for diagnleshooting BPM engine setup issues.")
    public Map<String, Object> getFlowableEngineConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        Object engine = getProcessEngine();
        if (engine == null) {
            result.put("status", "not_configured");
            result.put("hint", "No Flowable ProcessEngine found. Add flowable-spring-boot-starter.");
            return result;
        }

        result.put("engineClass", engine.getClass().getSimpleName());

        Object config = ReflectionHelper.invokeMethod(engine, "getProcessEngineConfiguration");
        if (config != null) {
            Object engineName = ReflectionHelper.invokeMethod(config, "getEngineName");
            if (engineName != null) result.put("engineName", engineName);

            Object dbType = ReflectionHelper.invokeMethod(config, "getDatabaseType");
            if (dbType != null) result.put("databaseType", dbType);

            Object dbSchemaUpdate = ReflectionHelper.invokeMethod(config, "getDatabaseSchemaUpdate");
            if (dbSchemaUpdate != null) result.put("databaseSchemaUpdate", dbSchemaUpdate);

            Object historyLevel = ReflectionHelper.invokeMethod(config, "getHistoryLevel");
            if (historyLevel != null) result.put("historyLevel", historyLevel.toString());

            // Async executor
            Object asyncExecutor = ReflectionHelper.invokeMethod(config, "getAsyncExecutor");
            if (asyncExecutor != null) {
                Map<String, Object> ae = new LinkedHashMap<>();
                ae.put("class", asyncExecutor.getClass().getSimpleName());
                Object isAutoActivate = ReflectionHelper.invokeMethod(asyncExecutor, "isAutoActivate");
                ae.put("autoActivate", isAutoActivate);
                ae.put("corePoolSize", ReflectionHelper.invokeMethod(asyncExecutor, "getCorePoolSize"));
                ae.put("maxPoolSize", ReflectionHelper.invokeMethod(asyncExecutor, "getMaxPoolSize"));
                result.put("asyncExecutor", ae);
            }

            // Job executor
            Object isJobExecutorActivate = ReflectionHelper.invokeMethod(config, "isJobExecutorActivate");
            if (isJobExecutorActivate != null) result.put("jobExecutorActive", isJobExecutorActivate);
        }

        // Count entities
        Object runtimeService = getRuntimeService();
        if (runtimeService != null) {
            try {
                Object execQuery = ReflectionHelper.invokeMethod(runtimeService, "createExecutionQuery");
                Object execCount = ReflectionHelper.invokeMethod(execQuery, "count");
                result.put("activeExecutionCount", execCount);
            } catch (Exception ignored) {}
        }

        Object taskService = getTaskService();
        if (taskService != null) {
            try {
                Object taskQuery = ReflectionHelper.invokeMethod(taskService, "createTaskQuery");
                Object taskCount = ReflectionHelper.invokeMethod(taskQuery, "count");
                result.put("activeTaskCount", taskCount);
            } catch (Exception ignored) {}
        }

        // Spring properties
        Map<String, String> props = new LinkedHashMap<>();
        String[] propNames = {
                "flowable.database-schema-update",
                "flowable.history-level",
                "flowable.async-executor-activate",
                "flowable.process-definition-cache-limit",
                "flowable.id-generator",
                "flowable.process-definition-location-prefix"
        };
        for (String p : propNames) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null) props.put(p, val);
        }
        if (!props.isEmpty()) result.put("springProperties", props);

        return result;
    }
}
