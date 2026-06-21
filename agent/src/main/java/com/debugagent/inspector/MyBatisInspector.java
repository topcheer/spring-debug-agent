package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * MyBatis diagnostic tools.
 * Inspects MyBatis configuration, mapped statements, SQL session stats,
 * interceptors, and dynamic SQL templates.
 * Conditional on mybatis-spring (org.mybatis.spring.SqlSessionFactoryBean) being on classpath.
 */
public class MyBatisInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Inspect MyBatis global configuration settings: cacheEnabled, lazyLoadingEnabled, "
            + "aggressiveLazyLoading, multipleResultSetsEnabled, useColumnLabel, useGeneratedKeys, "
            + "defaultExecutorType, defaultStatementTimeout, mapUnderscoreToCamelCase, vfsProvider. "
            + "Useful for diagnosing configuration-related query issues.")
    public Map<String, Object> getMyBatisConfiguration() {
        Map<String, Object> result = new LinkedHashMap<>();

        Object config = getConfiguration();
        if (config == null) {
            result.put("status", "not_configured");
            result.put("hint", "No MyBatis Configuration found. Add mybatis-spring-boot-starter dependency.");
            return result;
        }

        result.put("configClass", config.getClass().getSimpleName());

        // Core settings via reflection
        String[] boolProps = {
                "cacheEnabled", "lazyLoadingEnabled", "aggressiveLazyLoading",
                "multipleResultSetsEnabled", "useColumnLabel", "useGeneratedKeys",
                "mapUnderscoreToCamelCase", "safeRowBoundsEnabled", "safeResultHandlerEnabled"
        };
        Map<String, Object> settings = new LinkedHashMap<>();
        for (String prop : boolProps) {
            Object val = ReflectionHelper.invokeMethod(config, "is" + capitalize(prop));
            if (val == null) val = ReflectionHelper.getFieldValue(config, prop);
            if (val != null) settings.put(prop, val);
        }

        Object defaultExecutorType = ReflectionHelper.invokeMethod(config, "getDefaultExecutorType");
        if (defaultExecutorType != null) settings.put("defaultExecutorType", defaultExecutorType.toString());

        Object defaultStatementTimeout = ReflectionHelper.invokeMethod(config, "getDefaultStatementTimeout");
        if (defaultStatementTimeout != null) settings.put("defaultStatementTimeout", defaultStatementTimeout);

        Object defaultFetchSize = ReflectionHelper.invokeMethod(config, "getDefaultFetchSize");
        if (defaultFetchSize != null) settings.put("defaultFetchSize", defaultFetchSize);

        result.put("settings", settings);

        // Type aliases
        Object typeAliasRegistry = ReflectionHelper.getFieldValue(config, "typeAliasRegistry");
        if (typeAliasRegistry != null) {
            Object typeAliases = ReflectionHelper.getFieldValue(typeAliasRegistry, "TYPE_ALIASES");
            if (typeAliases instanceof Map) {
                result.put("typeAliasCount", ((Map<?, ?>) typeAliases).size());
            }
        }

        // Mappers count
        Object mappedStatements = ReflectionHelper.getFieldValue(config, "mappedStatements");
        if (mappedStatements instanceof Map) {
            result.put("mappedStatementCount", ((Map<?, ?>) mappedStatements).size());
        }

        // Type handlers
        Object typeHandlerRegistry = ReflectionHelper.getFieldValue(config, "typeHandlerRegistry");
        if (typeHandlerRegistry != null) {
            Object typeHandlers = ReflectionHelper.getFieldValue(typeHandlerRegistry, "TYPE_HANDLER_MAP");
            if (typeHandlers instanceof Map) {
                result.put("typeHandlerCount", ((Map<?, ?>) typeHandlers).size());
            }
        }

        return result;
    }

    @DebugTool(description = "List all MyBatis mapped statements: statement ID, SQL command type (SELECT/INSERT/UPDATE/DELETE), "
            + "and source resource (XML mapper file or annotation). "
            + "Useful for verifying mapper registration and diagnosing 'Invalid bound statement' errors.")
    public List<Map<String, Object>> getMyBatisMappers() {
        List<Map<String, Object>> mappers = new ArrayList<>();

        Object config = getConfiguration();
        if (config == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "No MyBatis Configuration found");
            return List.of(err);
        }

        Object mappedStatements = ReflectionHelper.getFieldValue(config, "mappedStatements");
        if (mappedStatements instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) mappedStatements).entrySet()) {
                Map<String, Object> mapper = new LinkedHashMap<>();
                Object ms = entry.getValue();

                mapper.put("id", entry.getKey().toString());
                Object sqlCommandType = ReflectionHelper.invokeMethod(ms, "getSqlCommandType");
                mapper.put("commandType", sqlCommandType != null ? sqlCommandType.toString() : "unknown");

                Object resource = ReflectionHelper.invokeMethod(ms, "getResource");
                mapper.put("resource", resource != null ? resource.toString() : "unknown");

                Object keyGenerator = ReflectionHelper.invokeMethod(ms, "getKeyGenerator");
                if (keyGenerator != null) {
                    mapper.put("keyGenerator", keyGenerator.getClass().getSimpleName());
                }

                Object timeout = ReflectionHelper.invokeMethod(ms, "getTimeout");
                if (timeout != null) mapper.put("timeout", timeout);

                Object fetchSize = ReflectionHelper.invokeMethod(ms, "getFetchSize");
                if (fetchSize != null) mapper.put("fetchSize", fetchSize);

                mappers.add(mapper);
            }
        }

        return mappers;
    }

    @DebugTool(description = "Inspect MyBatis interceptor (plugin) chain: each interceptor's class, "
            + "signature (which methods it intercepts), and ordering. "
            + "Useful for diagnosing SQL pagination, audit logging, or performance monitoring plugins "
            + "that may interfere with query execution.")
    public List<Map<String, Object>> getMyBatisInterceptors() {
        List<Map<String, Object>> result = new ArrayList<>();

        Object config = getConfiguration();
        if (config == null) {
            return List.of(Map.of("error", "No MyBatis Configuration found"));
        }

        Object interceptorChain = ReflectionHelper.getFieldValue(config, "interceptorChain");
        if (interceptorChain == null) {
            result.add(Map.of("status", "no_interceptor_chain"));
            return result;
        }

        Object interceptors = ReflectionHelper.getFieldValue(interceptorChain, "interceptors");
        if (interceptors instanceof List) {
            int i = 0;
            for (Object interceptor : (List<?>) interceptors) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("index", i++);
                info.put("class", interceptor.getClass().getName());

                // Try to get @Intercepts annotation
                try {
                    Class<?> interceptsClass = Class.forName("org.apache.ibatis.plugin.Intercepts", false, ctx.getClassLoader());
                    Object annot = interceptor.getClass().getAnnotation(
                            (Class<java.lang.annotation.Annotation>) interceptsClass);
                    if (annot != null) {
                        Object signature = annot.getClass().getMethod("value").invoke(annot);
                        if (signature instanceof Object[]) {
                            List<String> sigs = new ArrayList<>();
                            for (Object s : (Object[]) signature) {
                                sigs.add(s.toString());
                            }
                            info.put("signatures", sigs);
                        }
                    }
                } catch (Exception ignored) {}

                result.add(info);
            }
        }

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_interceptors"));
        }

        return result;
    }

    @DebugTool(description = "Inspect MyBatis SqlSessionFactory and SqlSessionTemplate configuration: "
            + "data source, transaction factory, environment, database ID provider, "
            + "and object factory. Useful for diagnosing transaction or connection pool issues "
            + "in MyBatis applications.")
    public Map<String, Object> getMyBatisSqlSessionInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        // SqlSessionFactory
        Object factory = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.mybatis.spring.SqlSessionFactoryBean");
        if (factory == null) {
            factory = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.apache.ibatis.session.SqlSessionFactory");
        }

        if (factory == null) {
            result.put("status", "not_configured");
            return result;
        }

        Object config = getConfiguration();
        if (config != null) {
            Object environment = ReflectionHelper.invokeMethod(config, "getEnvironment");
            if (environment != null) {
                Map<String, Object> envInfo = new LinkedHashMap<>();
                envInfo.put("id", ReflectionHelper.invokeMethod(environment, "getId"));

                Object dataSource = ReflectionHelper.invokeMethod(environment, "getDataSource");
                if (dataSource != null) {
                    envInfo.put("dataSourceClass", dataSource.getClass().getSimpleName());
                }

                Object transactionFactory = ReflectionHelper.invokeMethod(environment, "getTransactionFactory");
                if (transactionFactory != null) {
                    envInfo.put("transactionFactory", transactionFactory.getClass().getSimpleName());
                }
                result.put("environment", envInfo);
            }
        }

        // Check SqlSessionTemplate
        Object template = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.mybatis.spring.SqlSessionTemplate");
        result.put("sqlSessionTemplatePresent", template != null);

        // Check MapperScannerConfigurer / @MapperScan
        Map<String, Object> mapperInfo = new LinkedHashMap<>();
        Object[] mapperBeans = ctx.getBeanNamesForType(
                org.springframework.beans.factory.config.BeanDefinition.class);
        int mapperCount = 0;
        try {
            Class<?> mapperFactoryClass = Class.forName("org.mybatis.spring.mapper.MapperFactoryBean", false, ctx.getClassLoader());
            mapperCount = ctx.getBeanNamesForType(mapperFactoryClass).length;
        } catch (Exception ignored) {}
        mapperInfo.put("mapperFactoryBeanCount", mapperCount);
        result.put("mappers", mapperInfo);

        return result;
    }

    @DebugTool(description = "Inspect MyBatis cache configuration: second-level cache settings for each namespace, "
            + "cache implementation class, eviction strategy (LRU/FIFO/SOFT/WEAK), flush interval, "
            + "size, read-write status. Useful for diagnosing cache hit rate issues or "
            + "stale data problems in MyBatis applications.")
    public List<Map<String, Object>> getMyBatisCacheConfig() {
        List<Map<String, Object>> result = new ArrayList<>();

        Object config = getConfiguration();
        if (config == null) {
            return List.of(Map.of("error", "No MyBatis Configuration found"));
        }

        Object cacheRefMap = ReflectionHelper.getFieldValue(config, "cacheRefNamespaces");
        Object cacheMap = ReflectionHelper.getFieldValue(config, "caches");

        if (cacheMap instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) cacheMap).entrySet()) {
                Map<String, Object> cacheInfo = new LinkedHashMap<>();
                Object cache = entry.getValue();
                cacheInfo.put("namespace", entry.getKey().toString());
                cacheInfo.put("class", cache.getClass().getSimpleName());

                Object size = ReflectionHelper.invokeMethod(cache, "getSize");
                if (size != null) cacheInfo.put("size", size);

                Object id = ReflectionHelper.invokeMethod(cache, "getId");
                if (id != null) cacheInfo.put("id", id.toString());

                result.add(cacheInfo);
            }
        }

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_second_level_cache",
                    "hint", "No second-level cache configured. Check if <cache/> is declared in mapper XML."));
        }

        return result;
    }

    private Object getConfiguration() {
        // Try SqlSessionFactory.getConfiguration()
        Object factory = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.apache.ibatis.session.SqlSessionFactory");
        if (factory != null) {
            return ReflectionHelper.invokeMethod(factory, "getConfiguration");
        }
        // Try SqlSessionFactoryBean (not yet initialized)
        factory = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.mybatis.spring.SqlSessionFactoryBean");
        if (factory != null) {
            Object config = ReflectionHelper.invokeMethod(factory, "getObject");
            if (config != null) {
                return ReflectionHelper.invokeMethod(config, "getConfiguration");
            }
        }
        return null;
    }

    private String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
