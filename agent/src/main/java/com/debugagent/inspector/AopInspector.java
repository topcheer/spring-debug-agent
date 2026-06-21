package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Spring AOP inspection tool.
 * Shows @Aspect beans, pointcut expressions, advice types, and proxy beans.
 * Conditional on spring-aop being on classpath.
 */
public class AopInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List all @Aspect beans with their advice types (@Before, @After, @Around, @AfterReturning, @AfterThrowing). "
            + "Shows pointcut expressions and target packages.")
    public List<Map<String, Object>> getAopAspects() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            // Find beans annotated with @Aspect
            Class<?> aspectClass = ReflectionHelper.resolveClass("org.aspectj.lang.annotation.Aspect", ctx);
            if (aspectClass == null) {
                results.add(Map.of("info", "Spring AOP not on classpath. Add spring-boot-starter-aop."));
                return results;
            }

            Map<String, Object> aspectBeans = new LinkedHashMap<>();
            for (String name : ctx.getBeanDefinitionNames()) {
                try {
                    Object bean = ctx.getBean(name);
                    if (bean == null) continue;
                    // Use ClassUtils.getUserClass to see past CGLIB proxies
                    Class<?> beanType = org.springframework.util.ClassUtils.getUserClass(bean);
                    if (beanType.isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) aspectClass)) {
                        aspectBeans.put(name, bean);
                    }
                } catch (Exception ignored) {}
            }

            if (aspectBeans.isEmpty()) {
                results.add(Map.of("info", "No @Aspect beans found"));
                return results;
            }

            for (Map.Entry<String, Object> entry : aspectBeans.entrySet()) {
                Map<String, Object> aspectInfo = new LinkedHashMap<>();
                aspectInfo.put("beanName", entry.getKey());
                Class<?> userClass = org.springframework.util.ClassUtils.getUserClass(entry.getValue());
                aspectInfo.put("className", userClass.getName());

                List<Map<String, Object>> adviceList = extractAdvice(userClass, ctx);
                if (!adviceList.isEmpty()) {
                    aspectInfo.put("adviceCount", adviceList.size());
                    aspectInfo.put("advice", adviceList);
                }

                results.add(aspectInfo);
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to get aspects: " + e.getMessage()));
        }

        return results;
    }

    @DebugTool(description = "List all AOP pointcut expressions across all aspects. "
            + "Shows execution patterns, target classes, and method signatures.")
    public List<Map<String, Object>> getPointcutExpressions() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            Class<?> aspectClass = ReflectionHelper.resolveClass("org.aspectj.lang.annotation.Aspect", ctx);
            if (aspectClass == null) {
                results.add(Map.of("info", "Spring AOP not on classpath"));
                return results;
            }

            for (String name : ctx.getBeanDefinitionNames()) {
                try {
                    Object bean = ctx.getBean(name);
                    if (bean == null) continue;
                    Class<?> beanType = org.springframework.util.ClassUtils.getUserClass(bean);
                    if (beanType.isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) aspectClass)) {
                        for (Method m : beanType.getDeclaredMethods()) {
                            // Check for @Pointcut
                            for (Annotation ann : m.getAnnotations()) {
                                String annType = ann.annotationType().getName();
                                if (annType.equals("org.aspectj.lang.annotation.Pointcut")) {
                                    Map<String, Object> pc = new LinkedHashMap<>();
                                    pc.put("aspect", beanType.getSimpleName());
                                    pc.put("method", m.getName());
                                    pc.put("expression", ReflectionHelper.invokeMethod(ann, "value"));
                                    results.add(pc);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (results.isEmpty()) {
                results.add(Map.of("info", "No pointcut expressions found"));
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to get pointcuts: " + e.getMessage()));
        }

        return results;
    }

    @DebugTool(description = "List all beans that are AOP proxies (wrapped by Spring AOP). "
            + "Shows target class, proxy type (JDK dynamic or CGLIB), and interfaces.")
    public List<Map<String, Object>> getProxyBeans() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            for (String name : ctx.getBeanDefinitionNames()) {
                try {
                    Object bean = ctx.getBean(name);
                    if (bean == null) continue;

                    String className = bean.getClass().getName();
                    boolean isProxy = className.contains("$$EnhancerBySpringCGLIB")
                            || className.contains("$$SpringCGLIB")
                            || className.contains("$Proxy")
                            || java.lang.reflect.Proxy.isProxyClass(bean.getClass());

                    if (isProxy) {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("beanName", name);
                        info.put("proxyClass", className);

                        // Determine proxy type
                        if (className.contains("CGLIB")) {
                            info.put("proxyType", "CGLIB");
                        } else if (java.lang.reflect.Proxy.isProxyClass(bean.getClass())) {
                            info.put("proxyType", "JDK Dynamic Proxy");
                        }

                        // Target class
                        Class<?>[] interfaces = bean.getClass().getInterfaces();
                        if (interfaces.length > 0) {
                            List<String> ifaceNames = new ArrayList<>();
                            for (Class<?> i : interfaces) {
                                String in = i.getName();
                                if (!in.startsWith("org.springframework.aop")
                                        && !in.startsWith("org.aspectj")) {
                                    ifaceNames.add(in);
                                }
                            }
                            if (!ifaceNames.isEmpty()) info.put("targetInterfaces", ifaceNames);
                        }

                        results.add(info);
                    }
                } catch (Exception ignored) {}
            }

            if (results.isEmpty()) {
                results.add(Map.of("info", "No AOP proxy beans found"));
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to get proxy beans: " + e.getMessage()));
        }

        return results;
    }

    @DebugTool(description = "Get detailed AOP advice info: advice type (@Before/@After/@Around), pointcut expression, "
            + "and target method. Useful for understanding the aspect chain for a specific bean.")
    public Map<String, Object> getAopAdviceInfo(
            @ToolParam(description = "Aspect bean name or class name to inspect", required = false) String aspectName
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Class<?> aspectClass = ReflectionHelper.resolveClass("org.aspectj.lang.annotation.Aspect", ctx);
            if (aspectClass == null) {
                result.put("info", "Spring AOP not on classpath");
                return result;
            }

            String[] adviceTypes = {
                    "org.aspectj.lang.annotation.Before",
                    "org.aspectj.lang.annotation.After",
                    "org.aspectj.lang.annotation.AfterReturning",
                    "org.aspectj.lang.annotation.AfterThrowing",
                    "org.aspectj.lang.annotation.Around"
            };

            List<Map<String, Object>> allAdvice = new ArrayList<>();

            for (String name : ctx.getBeanDefinitionNames()) {
                try {
                    Object bean = ctx.getBean(name);
                    if (bean == null) continue;
                    Class<?> beanType = org.springframework.util.ClassUtils.getUserClass(bean);
                    if (!beanType.isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) aspectClass)) continue;

                    if (aspectName != null && !aspectName.isEmpty()
                            && !name.toLowerCase().contains(aspectName.toLowerCase())
                            && !beanType.getSimpleName().toLowerCase().contains(aspectName.toLowerCase())) {
                        continue;
                    }

                    List<Map<String, Object>> adviceList = extractAdvice(beanType, ctx);
                    allAdvice.addAll(adviceList);
                } catch (Exception ignored) {}
            }

            result.put("totalAdvice", allAdvice.size());

            // Group by advice type
            Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
            for (Map<String, Object> a : allAdvice) {
                String type = a.getOrDefault("type", "UNKNOWN").toString();
                grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(a);
            }
            result.put("byType", grouped);

            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
                counts.put(entry.getKey(), entry.getValue().size());
            }
            result.put("typeCounts", counts);

        } catch (Exception e) {
            result.put("error", "Failed to get advice info: " + e.getMessage());
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractAdvice(Class<?> aspectClass, ApplicationContext ctx) {
        List<Map<String, Object>> adviceList = new ArrayList<>();

        String[] adviceAnnotations = {
                "org.aspectj.lang.annotation.Before",
                "org.aspectj.lang.annotation.After",
                "org.aspectj.lang.annotation.AfterReturning",
                "org.aspectj.lang.annotation.AfterThrowing",
                "org.aspectj.lang.annotation.Around"
        };

        for (Method m : aspectClass.getDeclaredMethods()) {
            for (String adviceType : adviceAnnotations) {
                try {
                    Class<? extends Annotation> annClass = (Class<? extends Annotation>)
                            Class.forName(adviceType, false, ctx.getClassLoader());
                    Annotation ann = m.getAnnotation(annClass);
                    if (ann != null) {
                        Map<String, Object> advice = new LinkedHashMap<>();
                        advice.put("type", annClass.getSimpleName());
                        advice.put("method", m.getName());
                        advice.put("paramCount", m.getParameterCount());
                        advice.put("expression", ReflectionHelper.invokeMethod(ann, "value"));

                        // For AfterReturning/AfterThrowing, try getting extra attributes
                        Object returning = ReflectionHelper.invokeMethod(ann, "returning");
                        if (returning != null && !returning.toString().isEmpty()) {
                            advice.put("returningParam", returning);
                        }
                        Object throwing = ReflectionHelper.invokeMethod(ann, "throwing");
                        if (throwing != null && !throwing.toString().isEmpty()) {
                            advice.put("throwingParam", throwing);
                        }

                        adviceList.add(advice);
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }

        return adviceList;
    }
}
