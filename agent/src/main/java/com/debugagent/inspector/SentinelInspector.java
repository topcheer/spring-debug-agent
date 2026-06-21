package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * Alibaba Sentinel diagnostic tools.
 * Inspects flow control rules, circuit breakers, degrade rules, hotspot params, and system rules.
 * Conditional on sentinel-spring-cloud-gateway-starter or sentinel-core being on classpath.
 */
public class SentinelInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List all Sentinel flow control rules: resource name, grade (QPS/thread), "
            + "threshold, control behavior (direct/warm up/rate limiter), and relation strategy. "
            + "Useful for verifying rate limiting configuration and diagnosing blocked requests.")
    public List<Map<String, Object>> getSentinelFlowRules() {
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            Class<?> flowRuleClass = Class.forName("com.alibaba.csp.sentinel.slots.block.flow.FlowRule", false, ctx.getClassLoader());
            Class<?> managerClass = Class.forName("com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager", false, ctx.getClassLoader());
            Object rules = ReflectionHelper.invokeMethod(null == managerClass ? null :
                    managerClass, "getRules");
            // Static method
            java.lang.reflect.Method m = managerClass.getMethod("getRules");
            Object ruleList = m.invoke(null);

            if (ruleList instanceof List) {
                for (Object rule : (List<?>) ruleList) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("resource", ReflectionHelper.invokeMethod(rule, "getResource"));
                    r.put("limitApp", ReflectionHelper.invokeMethod(rule, "getLimitApp"));
                    Object grade = ReflectionHelper.invokeMethod(rule, "getGrade");
                    r.put("grade", grade != null && (int) grade == 0 ? "THREAD" : "QPS");
                    r.put("count", ReflectionHelper.invokeMethod(rule, "getCount"));

                    Object controlBehavior = ReflectionHelper.invokeMethod(rule, "getControlBehavior");
                    if (controlBehavior != null) {
                        String cb = switch ((int) controlBehavior) {
                            case 0 -> "direct_reject";
                            case 1 -> "warm_up";
                            case 2 -> "rate_limiter";
                            case 3 -> "warm_up_rate_limiter";
                            default -> "unknown(" + controlBehavior + ")";
                        };
                        r.put("controlBehavior", cb);
                    }

                    Object strategy = ReflectionHelper.invokeMethod(rule, "getStrategy");
                    if (strategy != null) {
                        String st = switch ((int) strategy) {
                            case 0 -> "direct";
                            case 1 -> "relate";
                            case 2 -> "chain";
                            default -> "unknown(" + strategy + ")";
                        };
                        r.put("strategy", st);
                    }

                    result.add(r);
                }
            }
        } catch (Exception e) {
            result.add(Map.of("error", "Sentinel not available: " + e.getMessage()));
        }

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_flow_rules",
                    "hint", "No flow control rules. Configure via Sentinel dashboard or @SentinelResource."));
        }

        return result;
    }

    @DebugTool(description = "List all Sentinel circuit breaker (degrade) rules: resource name, "
            + "strategy (RT/exception ratio/exception count), threshold, time window, min request amount, "
            + "and stat interval. Useful for diagnosing circuit breaker tripping or not tripping issues.")
    public List<Map<String, Object>> getSentinelDegradeRules() {
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            Class<?> managerClass = Class.forName("com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager", false, ctx.getClassLoader());
            java.lang.reflect.Method m = managerClass.getMethod("getRules");
            Object ruleList = m.invoke(null);

            if (ruleList instanceof List) {
                for (Object rule : (List<?>) ruleList) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("resource", ReflectionHelper.invokeMethod(rule, "getResource"));
                    r.put("limitApp", ReflectionHelper.invokeMethod(rule, "getLimitApp"));

                    Object strategy = ReflectionHelper.invokeMethod(rule, "getGrade");
                    if (strategy != null) {
                        String sg = switch ((int) strategy) {
                            case 0 -> "response_time";
                            case 1 -> "exception_ratio";
                            case 2 -> "exception_count";
                            default -> "unknown(" + strategy + ")";
                        };
                        r.put("strategy", sg);
                    }

                    r.put("count", ReflectionHelper.invokeMethod(rule, "getCount"));
                    r.put("timeWindow", ReflectionHelper.invokeMethod(rule, "getTimeWindow"));
                    r.put("minRequestAmount", ReflectionHelper.invokeMethod(rule, "getMinRequestAmount"));
                    r.put("statIntervalMs", ReflectionHelper.invokeMethod(rule, "getStatIntervalMs"));
                    r.put("slowRatioThreshold", ReflectionHelper.invokeMethod(rule, "getSlowRatioThreshold"));

                    result.add(r);
                }
            }
        } catch (Exception e) {
            result.add(Map.of("error", "Sentinel not available: " + e.getMessage()));
        }

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_degrade_rules"));
        }

        return result;
    }

    @DebugTool(description = "Get Sentinel real-time metrics for a resource: pass QPS, block QPS, "
            + "success QPS, exception QPS, response time (RT), and thread count. "
            + "Essential for understanding live traffic patterns and rate limiting behavior.")
    public Map<String, Object> getSentinelMetrics(
            @ToolParam(description = "Resource name (leave empty for all resources)") String resourceName
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            if (resourceName != null && !resourceName.isBlank()) {
                // Get metrics for specific resource
                Class<?> statClass = Class.forName("com.alibaba.csp.sentinel.slots.statistic.StatisticSlot", false, ctx.getClassLoader());
                Class<?> nodeClass = Class.forName("com.alibaba.csp.sentinel.node.DefaultNode", false, ctx.getClassLoader());

                // Use ConstantTree for cluster node
                Class<?> clusterNodeSlotClass = Class.forName("com.alibaba.csp.sentinel.slots.cluster.ClusterBuilderSlot", false, ctx.getClassLoader());
                java.lang.reflect.Method getClusterNode = clusterNodeSlotClass.getMethod(
                        "getClusterNode", String.class);
                Object node = getClusterNode.invoke(null, resourceName);

                if (node != null) {
                    result.put("resource", resourceName);
                    result.put("passQps", ReflectionHelper.invokeMethod(node, "passQps"));
                    result.put("blockQps", ReflectionHelper.invokeMethod(node, "blockQps"));
                    result.put("totalQps", ReflectionHelper.invokeMethod(node, "totalQps"));
                    result.put("successQps", ReflectionHelper.invokeMethod(node, "successQps"));
                    result.put("exceptionQps", ReflectionHelper.invokeMethod(node, "exceptionQps"));
                    result.put("rt", ReflectionHelper.invokeMethod(node, "avgRt"));
                    result.put("threadCount", ReflectionHelper.invokeMethod(node, "threadNum"));
                    result.put("totalRequest", ReflectionHelper.invokeMethod(node, "totalRequest"));
                    result.put("totalPassRequest", ReflectionHelper.invokeMethod(node, "totalPassRequest"));
                    result.put("totalBlockRequest", ReflectionHelper.invokeMethod(node, "totalBlockRequest"));
                } else {
                    result.put("resource", resourceName);
                    result.put("status", "no_metrics");
                    result.put("hint", "No metrics found for this resource. It may not have been called yet.");
                }
            } else {
                // List all resources with metrics
                Class<?> constantsClass = Class.forName("com.alibaba.csp.sentinel.Constants", false, ctx.getClassLoader());
                java.lang.reflect.Method getRootNode = constantsClass.getMethod("getRoot");
                Object root = getRootNode.invoke(null);
                if (root != null) {
                    Object children = ReflectionHelper.invokeMethod(root, "getChildList");
                    if (children instanceof List) {
                        List<Map<String, Object>> resources = new ArrayList<>();
                        for (Object child : (List<?>) children) {
                            Map<String, Object> r = new LinkedHashMap<>();
                            Object samples = ReflectionHelper.invokeMethod(child, "getSamples");
                            r.put("node", ReflectionHelper.invokeMethod(child, "getId"));
                            r.put("passQps", ReflectionHelper.invokeMethod(child, "passQps"));
                            r.put("blockQps", ReflectionHelper.invokeMethod(child, "blockQps"));
                            r.put("totalQps", ReflectionHelper.invokeMethod(child, "totalQps"));
                            r.put("rt", ReflectionHelper.invokeMethod(child, "avgRt"));
                            resources.add(r);
                        }
                        result.put("resources", resources);
                    }
                }
            }
        } catch (Exception e) {
            result.put("error", "Sentinel not available: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Inspect Sentinel datasource configuration: which rules are loaded from "
            + "Nacos, Apollo, Zookeeper, or file. Shows datasource type, rule type, and server address. "
            + "Useful for diagnosing rules not loading from remote configuration center.")
    public List<Map<String, Object>> getSentinelDatasources() {
        List<Map<String, Object>> result = new ArrayList<>();

        // Check for Sentinel datasource beans
        String[] dsTypes = {
                "com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource",
                "com.alibaba.csp.sentinel.datasource.apollo.ApolloDataSource",
                "com.alibaba.csp.sentinel.datasource.zookeeper.ZookeeperDataSource",
                "com.alibaba.csp.sentinel.datasource.file.FileRefreshableDataSource",
                "com.alibaba.csp.sentinel.datasource.redis.RedisDataSource"
        };

        for (String dsType : dsTypes) {
            List<?> beans = ReflectionHelper.getBeansOfType(ctx, dsType);
            for (Object bean : beans) {
                Map<String, Object> info = new LinkedHashMap<>();
                String simpleName = dsType.substring(dsType.lastIndexOf('.') + 1);
                info.put("type", simpleName.replace("DataSource", "").toLowerCase());
                info.put("class", simpleName);

                // Try to get configuration
                Object dataId = ReflectionHelper.getFieldValue(bean, "dataId");
                if (dataId != null) info.put("dataId", dataId);

                Object group = ReflectionHelper.getFieldValue(bean, "group");
                if (group != null) info.put("group", group);

                Object ruleType = ReflectionHelper.getFieldValue(bean, "ruleType");
                if (ruleType != null) info.put("ruleType", ruleType.toString());

                result.add(info);
            }
        }

        // Also check Spring properties
        Map<String, String> props = new LinkedHashMap<>();
        String[] propNames = {
                "spring.cloud.sentinel.transport.dashboard",
                "spring.cloud.sentinel.transport.port",
                "spring.cloud.sentinel.eager",
                "spring.cloud.sentinel.datasource"
        };
        for (String p : propNames) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null) props.put(p, val);
        }
        if (!props.isEmpty()) {
            Map<String, Object> springProps = new LinkedHashMap<>();
            springProps.put("springProperties", props);
            if (result.isEmpty()) result.add(springProps);
        }

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_datasources",
                    "hint", "No Sentinel datasources configured. Rules are loaded programmatically."));
        }

        return result;
    }
}
