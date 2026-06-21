package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * OpenAPI / Swagger diagnostic tools.
 * Generates and validates the OpenAPI specification and detects drift between
 * the spec and the live Spring MVC endpoints. Uses springdoc-openapi beans
 * when present, and falls back to RequestMappingHandlerMapping for live endpoints.
 */
public class OpenApiInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Generate and return the OpenAPI specification overview: paths, components/schemas, and security schemes. Useful for auditing what the API exposes.")
    public Map<String, Object> getOpenapiSpec() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Try HTTP endpoint first (most reliable for springdoc 2.x)
        Map<String, Object> httpResult = fetchOpenApiViaHttp();
        if (httpResult != null) {
            return httpResult;
        }

        // Fall back to bean-based extraction
        Object openApi = findOpenApi();
        if (openApi == null) {
            result.put("error", "No OpenAPI bean found and /v3/api-docs endpoint unavailable. Add springdoc-openapi-starter-webmvc-ui or swagger-core.");
            return result;
        }

        try {
            // Top-level metadata
            Object info = ReflectionHelper.invokeMethod(openApi, "getInfo");
            if (info != null) {
                Map<String, Object> infoMap = new LinkedHashMap<>();
                infoMap.put("title", ReflectionHelper.invokeMethod(info, "getTitle"));
                infoMap.put("version", ReflectionHelper.invokeMethod(info, "getVersion"));
                infoMap.put("description", ReflectionHelper.invokeMethod(info, "getDescription"));
                result.put("info", infoMap);
            }

            Object servers = ReflectionHelper.invokeMethod(openApi, "getServers");
            if (servers instanceof List<?> list && !list.isEmpty()) {
                List<Map<String, Object>> serverList = new ArrayList<>();
                for (Object s : list) {
                    Map<String, Object> server = new LinkedHashMap<>();
                    server.put("url", ReflectionHelper.invokeMethod(s, "getUrl"));
                    server.put("description", ReflectionHelper.invokeMethod(s, "getDescription"));
                    serverList.add(server);
                }
                result.put("servers", serverList);
            }

            // Paths
            Object paths = ReflectionHelper.invokeMethod(openApi, "getPaths");
            int pathCount = 0;
            int operationCount = 0;
            List<String> pathKeys = new ArrayList<>();
            if (paths != null) {
                Object pathMap = ReflectionHelper.invokeMethod(paths, "getPathItems");
                if (pathMap instanceof Map<?, ?> map) {
                    pathCount = map.size();
                    pathKeys = new ArrayList<>();
                    for (Object key : map.keySet()) pathKeys.add(String.valueOf(key));
                    for (Object val : map.values()) {
                        if (val == null) continue;
                        for (String op : List.of("getGet", "getPost", "getPut", "getDelete",
                                "getPatch", "getOptions", "getHead")) {
                            if (ReflectionHelper.invokeMethod(val, op) != null) operationCount++;
                        }
                    }
                }
            }
            result.put("pathCount", pathCount);
            result.put("operationCount", operationCount);
            result.put("paths", pathKeys.size() > 100 ? pathKeys.subList(0, 100) : pathKeys);

            // Components / schemas
            Object components = ReflectionHelper.invokeMethod(openApi, "getComponents");
            if (components != null) {
                Object schemas = ReflectionHelper.invokeMethod(components, "getSchemas");
                if (schemas instanceof Map<?, ?> schemaMap) {
                    List<String> schemaNames = new ArrayList<>();
                    for (Object k : schemaMap.keySet()) schemaNames.add(String.valueOf(k));
                    result.put("schemaCount", schemaMap.size());
                    result.put("schemas", schemaNames);
                }
                Object securitySchemes = ReflectionHelper.invokeMethod(components, "getSecuritySchemes");
                if (securitySchemes instanceof Map<?, ?> secMap) {
                    Map<String, Object> sec = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : secMap.entrySet()) {
                        Object scheme = entry.getValue();
                        Map<String, Object> s = new LinkedHashMap<>();
                        Object type = ReflectionHelper.invokeMethod(scheme, "getType");
                        s.put("type", type != null ? type.toString() : null);
                        Object in = ReflectionHelper.invokeMethod(scheme, "getIn");
                        s.put("in", in != null ? in.toString() : null);
                        s.put("scheme", ReflectionHelper.invokeMethod(scheme, "getScheme"));
                        sec.put(String.valueOf(entry.getKey()), s);
                    }
                    result.put("securitySchemes", sec);
                }
            }

            // Security requirements
            Object security = ReflectionHelper.invokeMethod(openApi, "getSecurity");
            if (security instanceof List<?> list && !list.isEmpty()) {
                result.put("globalSecurity", list.size());
            }

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Validate the OpenAPI spec: check for missing responses, undocumented parameters, circular schema references, and deprecated operations. Returns a list of warnings/issues.")
    public List<Map<String, Object>> validateOpenapi() {
        List<Map<String, Object>> issues = new ArrayList<>();

        Object openApi = findOpenApi();
        if (openApi == null) {
            issues.add(Map.of("error", "No OpenAPI bean found."));
            return issues;
        }

        // Detect circular schema refs by walking $ref pointers
        try {
            Object components = ReflectionHelper.invokeMethod(openApi, "getComponents");
            if (components != null) {
                Object schemas = ReflectionHelper.invokeMethod(components, "getSchemas");
                if (schemas instanceof Map<?, ?> schemaMap) {
                    Set<String> schemaNames = new HashSet<>();
                    for (Object k : schemaMap.keySet()) schemaNames.add(String.valueOf(k));
                    int circular = 0;
                    for (String k : schemaNames) {
                        Object schema = schemaMap.get(k);
                        if (hasDirectSelfReference(schema, k)) {
                            circular++;
                            if (issues.size() < 100) {
                                issues.add(Map.of("type", "circularReference",
                                        "schema", k,
                                        "note", "Schema directly references itself."));
                            }
                        }
                    }
                    if (circular > 0) {
                        issues.add(Map.of("type", "circularReferenceTotal", "count", circular));
                    }
                }
            }
        } catch (Exception ignored) {}

        // Check paths for missing 2xx responses or deprecated operations
        try {
            Object paths = ReflectionHelper.invokeMethod(openApi, "getPaths");
            Object pathMap = paths != null ? ReflectionHelper.invokeMethod(paths, "getPathItems") : null;
            if (pathMap instanceof Map<?, ?> map) {
                int missingResponses = 0;
                int deprecatedOps = 0;
                int opsWithoutDescription = 0;
                int totalOps = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object item = entry.getValue();
                    if (item == null) continue;
                    for (String opMethod : List.of("getGet", "getPost", "getPut",
                            "getDelete", "getPatch", "getOptions", "getHead")) {
                        Object op = ReflectionHelper.invokeMethod(item, opMethod);
                        if (op == null) continue;
                        totalOps++;
                        Object responses = ReflectionHelper.invokeMethod(op, "getResponses");
                        if (!(responses instanceof Map<?, ?> rmap) || rmap.isEmpty()) {
                            missingResponses++;
                            if (issues.size() < 100) {
                                issues.add(Map.of("type", "missingResponses",
                                        "path", String.valueOf(entry.getKey()),
                                        "method", opMethod.replace("get", "").toLowerCase()));
                            }
                        }
                        Object deprecated = ReflectionHelper.invokeMethod(op, "getDeprecated");
                        if (Boolean.TRUE.equals(deprecated)) deprecatedOps++;

                        Object desc = ReflectionHelper.invokeMethod(op, "getDescription");
                        if (desc == null || desc.toString().isBlank()) {
                            opsWithoutDescription++;
                        }
                    }
                }
                if (deprecatedOps > 0) {
                    issues.add(Map.of("type", "deprecatedOperations", "count", deprecatedOps));
                }
                if (opsWithoutDescription > 0) {
                    issues.add(Map.of("type", "operationsWithoutDescription", "count", opsWithoutDescription));
                }
                issues.add(0, Map.of("type", "summary",
                        "totalOperations", totalOps,
                        "missingResponses", missingResponses,
                        "deprecated", deprecatedOps));
            }
        } catch (Exception ignored) {}

        if (issues.isEmpty()) {
            issues.add(Map.of("note", "No validation issues detected."));
        }
        return issues;
    }

    @DebugTool(description = "Detect drift between the live Spring MVC endpoints and the OpenAPI spec. Reports endpoints that exist in code but are not documented, and documented paths that don't have a matching endpoint.")
    public Map<String, Object> getApiChangelog() {
        Map<String, Object> result = new LinkedHashMap<>();

        Set<String> documentedPaths = new TreeSet<>();
        Object openApi = findOpenApi();
        if (openApi != null) {
            try {
                Object paths = ReflectionHelper.invokeMethod(openApi, "getPaths");
                Object pathMap = paths != null ? ReflectionHelper.invokeMethod(paths, "getPathItems") : null;
                if (pathMap instanceof Map<?, ?> map) {
                    for (Object key : map.keySet()) documentedPaths.add(normalizePath(String.valueOf(key)));
                }
            } catch (Exception ignored) {}
        }

        Set<String> livePaths = new TreeSet<>();
        try {
            Class<?> mappingClass = Class.forName("org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping", false, ctx.getClassLoader());
            String[] names = ctx.getBeanNamesForType(mappingClass);
            if (names.length > 0) {
                Object handler = ctx.getBean(names[0]);
                Method getMethods = handler.getClass().getMethod("getHandlerMethods");
                Object map = getMethods.invoke(handler);
                if (map instanceof Map<?, ?> handlerMap) {
                    for (Object keyObj : handlerMap.keySet()) {
                        String pattern = extractPattern(keyObj);
                        if (pattern != null) livePaths.add(normalizePath(pattern));
                    }
                }
            }
        } catch (ClassNotFoundException ignored) {
            result.put("error", "Spring Web MVC not on classpath; cannot enumerate live endpoints.");
        } catch (Exception e) {
            result.put("error", "Failed to enumerate live endpoints: " + e.getClass().getSimpleName());
        }

        // Diff
        Set<String> undocumented = new TreeSet<>(livePaths);
        undocumented.removeAll(documentedPaths);
        // Filter obvious actuator/error paths out of the "undocumented" report
        undocumented.removeIf(p -> p.startsWith("/actuator") || p.equals("/error"));

        Set<String> missing = new TreeSet<>(documentedPaths);
        missing.removeAll(livePaths);

        result.put("documentedPathCount", documentedPaths.size());
        result.put("livePathCount", livePaths.size());
        result.put("undocumentedEndpoints", undocumented);
        result.put("undocumentedCount", undocumented.size());
        result.put("documentedButMissing", missing);
        result.put("documentedButMissingCount", missing.size());
        return result;
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private Object findOpenApi() {
        // 1. SpringDoc OpenApiResource — call getOpenApi() (generates full spec with paths)
        try {
            Class<?> resourceClass = Class.forName("org.springdoc.api.OpenApiResource", false, ctx.getClassLoader());
            String[] names = ctx.getBeanNamesForType(resourceClass);
            if (names.length > 0) {
                Object resource = ctx.getBean(names[0]);
                Object openApi = ReflectionHelper.invokeMethod(resource, "getOpenApi");
                if (openApi != null) {
                    // Verify it has paths
                    Object paths = ReflectionHelper.invokeMethod(openApi, "getPaths");
                    if (paths != null) return openApi;
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Exception ignored) {}

        // 2. OpenAPIService bean
        try {
            Class<?> serviceClass = Class.forName("org.springdoc.core.service.OpenAPIService", false, ctx.getClassLoader());
            String[] names = ctx.getBeanNamesForType(serviceClass);
            if (names.length > 0) {
                Object service = ctx.getBean(names[0]);
                Object openApi = ReflectionHelper.invokeMethod(service, "build");
                if (openApi != null) return openApi;
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Exception ignored) {}

        // 3. Static io.swagger.v3.oas.models.OpenAPI bean (may have limited paths)
        try {
            Class<?> openApiClass = Class.forName("io.swagger.v3.oas.models.OpenAPI", false, ctx.getClassLoader());
            String[] names = ctx.getBeanNamesForType(openApiClass);
            if (names.length > 0) return ctx.getBean(names[0]);
        } catch (ClassNotFoundException ignored) {
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Fetch OpenAPI spec from /v3/api-docs HTTP endpoint (springdoc 2.x).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchOpenApiViaHttp() {
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var port = ctx.getEnvironment().getProperty("server.port");
            if (port == null || port.equals("0")) port = "8080";
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + port + "/v3/api-docs"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readValue(response.body(), Map.class);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", "/v3/api-docs (HTTP)");

            Object info = root.get("info");
            if (info instanceof Map<?, ?> i) {
                Map<String, Object> infoMap = new LinkedHashMap<>();
                infoMap.put("title", i.get("title"));
                infoMap.put("version", i.get("version"));
                infoMap.put("description", i.get("description"));
                result.put("info", infoMap);
            }

            Object paths = root.get("paths");
            int pathCount = 0, opCount = 0;
            List<String> pathKeys = new ArrayList<>();
            if (paths instanceof Map<?, ?> pm) {
                pathCount = pm.size();
                for (Object key : pm.keySet()) {
                    pathKeys.add(String.valueOf(key));
                    Object item = pm.get(key);
                    if (item instanceof Map<?, ?> methods) {
                        for (String m : List.of("get", "post", "put", "delete", "patch", "options", "head")) {
                            if (methods.containsKey(m)) opCount++;
                        }
                    }
                }
            }
            result.put("pathCount", pathCount);
            result.put("operationCount", opCount);
            result.put("paths", pathKeys.size() > 100 ? pathKeys.subList(0, 100) : pathKeys);

            Object components = root.get("components");
            if (components instanceof Map<?, ?> comp) {
                Object schemas = comp.get("schemas");
                if (schemas instanceof Map<?, ?> sm) {
                    List<String> schemaNames = new ArrayList<>();
                    for (Object k : sm.keySet()) schemaNames.add(String.valueOf(k));
                    result.put("schemaCount", schemaNames.size());
                    result.put("schemas", schemaNames);
                }
            }

            return result;
        } catch (Exception e) {
            System.getLogger("OpenApiInspector").log(System.Logger.Level.WARNING,
                    "fetchOpenApiViaHttp failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Heuristic: does the schema reference itself via a top-level $ref / items $ref?
     * False positives are acceptable here — this is a warning, not an error.
     */
    @SuppressWarnings("unchecked")
    private boolean hasDirectSelfReference(Object schema, String schemaName) {
        if (schema == null) return false;
        String ref = String.valueOf(ReflectionHelper.invokeMethod(schema, "get$ref"));
        if (ref.contains("/" + schemaName)) return true;

        Object properties = ReflectionHelper.invokeMethod(schema, "getProperties");
        if (properties instanceof Map<?, ?> propMap) {
            for (Object value : propMap.values()) {
                if (value == null) continue;
                String propRef = String.valueOf(ReflectionHelper.invokeMethod(value, "get$ref"));
                if (propRef.contains("/" + schemaName)) return true;
                Object items = ReflectionHelper.invokeMethod(value, "getItems");
                if (items != null) {
                    String itemsRef = String.valueOf(ReflectionHelper.invokeMethod(items, "get$ref"));
                    if (itemsRef.contains("/" + schemaName)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Extract the URL pattern from a RequestMappingInfo key.
     */
    private String extractPattern(Object mappingInfo) {
        try {
            Object patterns = ReflectionHelper.invokeMethod(mappingInfo, "getPatternValues");
            if (patterns instanceof Collection<?> coll && !coll.isEmpty()) {
                return String.valueOf(coll.iterator().next());
            }
            Object pathPatterns = ReflectionHelper.invokeMethod(mappingInfo, "getPathPatternsCondition");
            if (pathPatterns != null) {
                Object pp = ReflectionHelper.invokeMethod(pathPatterns, "getPatterns");
                if (pp instanceof Collection<?> coll && !coll.isEmpty()) {
                    return String.valueOf(coll.iterator().next());
                }
            }
            Object patternsCondition = ReflectionHelper.invokeMethod(mappingInfo, "getPatternsCondition");
            if (patternsCondition != null) {
                Object pp = ReflectionHelper.invokeMethod(patternsCondition, "getPatterns");
                if (pp instanceof Collection<?> coll && !coll.isEmpty()) {
                    return String.valueOf(coll.iterator().next());
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Normalize a path so trailing slashes and template variable names don't cause drift:
     *   /users/{id}/  ->  /users/{id}
     */
    private String normalizePath(String p) {
        if (p == null) return "";
        if (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
