package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Spring Boot auto-configuration condition evaluation inspector.
 * Shows which @Conditional features were enabled or disabled and why.
 */
public class FeatureFlagInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Show Spring Boot auto-configuration outcomes: which @Conditional features passed or failed and why. Useful for understanding why a bean was or wasn't created.")
    public Map<String, Object> getFeatureFlags() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // ConditionEvaluationReport is stored in the bean factory
            Object report = getConditionEvaluationReport();
            if (report == null) {
                result.put("info", "ConditionEvaluationReport not available");
                return result;
            }

            // Get positive matches (conditions that matched)
            Object positiveMatches = ReflectionHelper.invokeMethod(report, "getPositiveMatches");
            Object negativeMatches = ReflectionHelper.invokeMethod(report, "getUnconditionalClasses");
            Object exclusions = ReflectionHelper.invokeMethod(report, "getExclusions");

            // Count positive matches
            if (positiveMatches instanceof Map<?, ?> pm) {
                List<String> matched = new ArrayList<>();
                for (Object key : pm.keySet()) {
                    matched.add(key.toString());
                }
                matched.sort(Comparator.naturalOrder());
                result.put("matchedConfigurations", matched);
                result.put("matchedCount", matched.size());
            }

            // Negative matches
            if (negativeMatches instanceof Collection<?> nm) {
                List<String> unconditional = new ArrayList<>();
                for (Object item : nm) {
                    unconditional.add(item.toString());
                }
                result.put("unconditionalClasses", unconditional.size() > 30
                        ? unconditional.subList(0, 30)
                        : unconditional);
            }

            // Exclusions
            if (exclusions instanceof Collection<?> ex) {
                List<String> excluded = new ArrayList<>();
                for (Object item : ex) {
                    excluded.add(item.toString());
                }
                if (!excluded.isEmpty()) {
                    result.put("exclusions", excluded);
                }
            }

            // Try to get condition outcomes detail
            try {
                Object conditionOutcomes = ReflectionHelper.invokeMethod(report, "getConditionAndOutcomesBySource");
                if (conditionOutcomes instanceof Map<?, ?> coMap) {
                    List<Map<String, Object>> failed = new ArrayList<>();
                    for (Map.Entry<?, ?> entry : coMap.entrySet()) {
                        Object outcomes = entry.getValue();
                        // Check if all conditions in this source matched
                        if (outcomes != null) {
                            Object isEmpty = ReflectionHelper.invokeMethod(outcomes, "isEmpty");
                            if (Boolean.FALSE.equals(isEmpty)) {
                                Object isFullMatch = ReflectionHelper.invokeMethod(outcomes, "isFullMatch");
                                if (Boolean.FALSE.equals(isFullMatch)) {
                                    Map<String, Object> fail = new LinkedHashMap<>();
                                    fail.put("source", entry.getKey().toString());
                                    Object fullOutcomes = ReflectionHelper.invokeMethod(outcomes, "getConditionAndOutcomes");
                                    if (fullOutcomes instanceof List<?> list) {
                                        List<String> reasons = new ArrayList<>();
                                        for (Object o : list) {
                                            Object outcome = ReflectionHelper.invokeMethod(o, "getOutcome");
                                            Object condition = ReflectionHelper.invokeMethod(o, "getCondition");
                                            if (outcome != null) {
                                                reasons.add(condition + ": " + outcome);
                                            }
                                        }
                                        if (!reasons.isEmpty()) fail.put("reasons", reasons);
                                    }
                                    failed.add(fail);
                                }
                            }
                        }
                    }
                    if (!failed.isEmpty()) {
                        result.put("failedConfigurations", failed.size() > 20 ? failed.subList(0, 20) : failed);
                        result.put("failedCount", failed.size());
                    }
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            result.put("error", "Failed to get feature flags: " + e.getMessage());
        }

        return result;
    }

    private Object getConditionEvaluationReport() {
        try {
            // ConditionEvaluationReport is accessible via a static method on AutoConfigurationImportListener
            // or via ConfigurableListableBeanFactory attribute
            Class<?> reportClass = Class.forName(
                    "org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport");
            // Try to get from bean factory
            Object beanFactory = ctx.getAutowireCapableBeanFactory();
            if (beanFactory instanceof org.springframework.beans.factory.config.ConfigurableListableBeanFactory clf) {
                // The report is stored as a singleton
                try {
                    Object singleton = clf.getSingleton("autoConfigurationReport");
                    if (singleton != null) return singleton;
                } catch (Exception ignored) {}
            }
            // Try static get()
            Method getMethod = reportClass.getMethod("get",
                    org.springframework.beans.factory.config.ConfigurableListableBeanFactory.class);
            if (beanFactory instanceof org.springframework.beans.factory.config.ConfigurableListableBeanFactory clf) {
                return getMethod.invoke(null, clf);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }
}
