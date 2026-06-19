package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * HashiCorp Vault diagnostic tools.
 * Inspects enabled secret engines and secret metadata via reflection on
 * Spring Vault's VaultTemplate when spring-vault-core is on the classpath.
 */
public class VaultInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List enabled HashiCorp Vault secret engines (kv, transit, database, pki, aws, etc.) via the Vault sys/mounts endpoint. Useful for verifying which backends are configured for the application.")
    public List<Map<String, Object>> getSecretEngines() {
        List<Map<String, Object>> engines = new ArrayList<>();

        Object vaultTemplate = findVaultTemplate();
        if (vaultTemplate == null) {
            engines.add(Map.of("error", "VaultTemplate not found. Add spring-vault-core to the classpath."));
            return engines;
        }

        try {
            // First strategy: use VaultTemplate.read("sys/mounts") which returns a VaultResponse
            Object response = readPath(vaultTemplate, "sys/mounts");
            if (response == null) {
                engines.add(Map.of("error", "Failed to read sys/mounts. Vault may be unreachable or token may lack permission."));
                return engines;
            }

            Object data = ReflectionHelper.invokeMethod(response, "getData");
            if (data instanceof Map<?, ?> mounts) {
                for (Map.Entry<?, ?> entry : mounts.entrySet()) {
                    Map<String, Object> engine = new LinkedHashMap<>();
                    engine.put("path", entry.getKey());
                    Object value = entry.getValue();
                    if (value instanceof Map<?, ?> vmap) {
                        engine.put("type", vmap.get("type"));
                        engine.put("description", vmap.get("description"));
                        engine.put("version", vmap.get("options") != null
                                ? ((Map<?, ?>) vmap.get("options")).get("version")
                                : null);
                        engine.put("defaultLeaseTtl", vmap.get("default_lease_ttl"));
                        engine.put("maxLeaseTtl", vmap.get("max_lease_ttl"));
                    } else {
                        engine.put("value", String.valueOf(value));
                    }
                    engines.add(engine);
                }
            }
            if (engines.isEmpty()) {
                engines.add(Map.of("note", "No secret engines reported by Vault."));
            }
        } catch (Exception e) {
            engines.add(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        return engines;
    }

    @DebugTool(description = "Get metadata for a Vault secret at a specific path: current versions, created/updated timestamps, deletion status, and destroyed status. Works with versioned KV mounts.")
    public Map<String, Object> getSecretMetadata(
            @ToolParam(description = "Secret path (e.g. secret/data/my-app or my-app)", required = true) String path
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        Object vaultTemplate = findVaultTemplate();
        if (vaultTemplate == null) {
            result.put("error", "VaultTemplate not found. Add spring-vault-core to the classpath.");
            return result;
        }

        result.put("path", path);

        // Try metadata endpoint first (versioned KV)
        String metadataPath = path;
        if (!path.startsWith("metadata/") && !path.contains("/metadata/")) {
            // If path looks like data/..., swap; otherwise prepend metadata/
            if (path.contains("/data/")) {
                metadataPath = path.replace("/data/", "/metadata/");
            } else if (path.startsWith("data/")) {
                metadataPath = "metadata/" + path.substring(5);
            } else {
                metadataPath = "metadata/" + path;
            }
        }

        try {
            Object metadataResponse = readPath(vaultTemplate, metadataPath);
            if (metadataResponse != null) {
                Object data = ReflectionHelper.invokeMethod(metadataResponse, "getData");
                if (data instanceof Map<?, ?> dmap) {
                    Object versions = dmap.get("versions");
                    result.put("currentVersion", dmap.get("current_version"));
                    result.put("oldestVersion", dmap.get("oldest_version"));
                    result.put("updatedTime", dmap.get("updated_time"));
                    result.put("createdTime", dmap.get("created_time"));
                    result.put("maxVersions", dmap.get("max_versions"));
                    result.put("casRequired", dmap.get("cas_required"));
                    result.put("deleteAllVersions", dmap.get("delete_all_versions"));

                    if (versions instanceof Map<?, ?> vmap && !vmap.isEmpty()) {
                        List<Map<String, Object>> versionList = new ArrayList<>();
                        int shown = 0;
                        for (Map.Entry<?, ?> entry : vmap.entrySet()) {
                            if (shown++ >= 50) break;
                            Map<String, Object> v = new LinkedHashMap<>();
                            v.put("version", entry.getKey());
                            Object val = entry.getValue();
                            if (val instanceof Map<?, ?> vm) {
                                v.put("created", vm.get("created_time"));
                                v.put("destroyed", vm.get("destroyed"));
                                v.put("deleted", vm.get("deletion_time"));
                            }
                            versionList.add(v);
                        }
                        result.put("versionCount", vmap.size());
                        result.put("versions", versionList);

                        long destroyed = versionList.stream()
                                .filter(v -> Boolean.TRUE.equals(v.get("destroyed")))
                                .count();
                        long deleted = versionList.stream()
                                .filter(v -> v.get("deleted") != null
                                        && !"null".equals(String.valueOf(v.get("deleted"))))
                                .count();
                        result.put("destroyedCount", destroyed);
                        result.put("deletedCount", deleted);
                    }
                    return result;
                }
            }
            result.put("note", "No metadata at " + metadataPath
                    + ". Path may not be versioned KV; falling back to direct read.");
        } catch (Exception e) {
            result.put("metadataError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Fallback: direct read of the path
        try {
            Object response = readPath(vaultTemplate, path);
            if (response != null) {
                result.put("found", true);
                Object data = ReflectionHelper.invokeMethod(response, "getData");
                if (data instanceof Map<?, ?> dmap) {
                    result.put("fieldCount", dmap.size());
                    result.put("keys", new ArrayList<>(dmap.keySet()));
                }
                Object lease = ReflectionHelper.invokeMethod(response, "getLeaseDuration");
                result.put("leaseDuration", lease);
                Object renewable = ReflectionHelper.invokeMethod(response, "getRenewable");
                result.put("renewable", renewable);
            } else {
                result.put("found", false);
            }
        } catch (Exception e) {
            result.put("error", "Read failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private Object findVaultTemplate() {
        try {
            Class<?> vtClass = Class.forName("org.springframework.vault.core.VaultTemplate");
            String[] names = ctx.getBeanNamesForType(vtClass);
            return names.length > 0 ? ctx.getBean(names[0]) : null;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Invoke vaultTemplate.read(path) reflectively; return the VaultResponse or null.
     */
    private Object readPath(Object vaultTemplate, String path) {
        try {
            Method readMethod = vaultTemplate.getClass().getMethod("read", String.class);
            return readMethod.invoke(vaultTemplate, path);
        } catch (Exception e) {
            return null;
        }
    }
}
