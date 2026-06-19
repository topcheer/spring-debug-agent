package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Distributed cache (Hazelcast / Infinispan) diagnostic tools.
 * Inspects cluster members and cache statistics via reflection when
 * either library is present on the classpath.
 */
public class DistributedCacheInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List distributed cache cluster members: address, UUID, and local member flag. Works with Hazelcast and Infinispan embedded caches. Useful for diagnosing cluster splits or membership issues.")
    public List<Map<String, Object>> getDistributedCacheMembers() {
        List<Map<String, Object>> members = new ArrayList<>();

        // Hazelcast
        for (Object hz : beansOfType("com.hazelcast.core.HazelcastInstance")) {
            try {
                Object cluster = ReflectionHelper.invokeMethod(hz, "getCluster");
                if (cluster == null) continue;
                Object memberSet = ReflectionHelper.invokeMethod(cluster, "getMembers");
                if (memberSet instanceof Collection<?> set) {
                    for (Object member : set) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("provider", "Hazelcast");
                        Object address = ReflectionHelper.invokeMethod(member, "getAddress");
                        m.put("address", address != null ? address.toString() : null);
                        Object uuid = ReflectionHelper.invokeMethod(member, "getUuid");
                        m.put("uuid", uuid != null ? uuid.toString() : null);
                        Object local = ReflectionHelper.invokeMethod(member, "isLocalMember");
                        if (local == null) local = ReflectionHelper.invokeMethod(member, "localMember");
                        m.put("localMember", local);
                        Object lite = ReflectionHelper.invokeMethod(member, "isLiteMember");
                        m.put("liteMember", lite);
                        members.add(m);
                    }
                }
                if (!members.isEmpty()) return members;
            } catch (Exception e) {
                members.add(Map.of("error", "Hazelcast: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        // Infinispan
        for (Object cm : beansOfType("org.infinispan.manager.EmbeddedCacheManager")) {
            try {
                Object transport = ReflectionHelper.invokeMethod(cm, "getTransport");
                if (transport == null) {
                    members.add(Map.of("provider", "Infinispan",
                            "note", "Embedded (local mode) — no cluster transport."));
                    continue;
                }
                Object memberList = ReflectionHelper.invokeMethod(transport, "getMembers");
                Object localAddr = ReflectionHelper.invokeMethod(transport, "getAddress");
                if (memberList instanceof List<?> list) {
                    for (Object addr : list) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("provider", "Infinispan");
                        m.put("address", addr != null ? addr.toString() : null);
                        m.put("localMember", localAddr != null && localAddr.equals(addr));
                        members.add(m);
                    }
                }
                if (!members.isEmpty()) return members;
            } catch (Exception e) {
                members.add(Map.of("error", "Infinispan: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        if (members.isEmpty()) {
            members.add(Map.of("error", "No Hazelcast or Infinispan cache manager found."));
        }
        return members;
    }

    @DebugTool(description = "Get distributed cache statistics: entry count, hit/miss ratio, backup entry count, locked entry count, plus the list of distributed maps (or caches) and their sizes. Works with Hazelcast and Infinispan.")
    public Map<String, Object> getDistributedCacheStats() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Hazelcast
        for (Object hz : beansOfType("com.hazelcast.core.HazelcastInstance")) {
            try {
                result.put("provider", "Hazelcast");
                result.put("instanceName", ReflectionHelper.invokeMethod(hz, "getName"));

                // Cluster state
                Object cluster = ReflectionHelper.invokeMethod(hz, "getCluster");
                if (cluster != null) {
                    result.put("clusterState", String.valueOf(ReflectionHelper.invokeMethod(cluster, "getClusterState")));
                    result.put("clusterTime", ReflectionHelper.invokeMethod(cluster, "getClusterTime"));
                }

                // Distributed objects (maps, queues, etc.)
                Object distObjects = ReflectionHelper.invokeMethod(hz, "getDistributedObjects");
                List<Map<String, Object>> objList = new ArrayList<>();
                if (distObjects instanceof Collection<?> coll) {
                    for (Object obj : coll) {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("name", ReflectionHelper.invokeMethod(obj, "getName"));
                        String svc = String.valueOf(ReflectionHelper.invokeMethod(obj, "getServiceName"));
                        info.put("service", svc);
                        // Best-effort stats for IMap
                        if (svc.contains("map")) {
                            try {
                                Object localStats = ReflectionHelper.invokeMethod(obj, "getLocalMapStats");
                                if (localStats != null) {
                                    Map<String, Object> stats = new LinkedHashMap<>();
                                    stats.put("ownedEntryCount", ReflectionHelper.invokeMethod(localStats, "getOwnedEntryCount"));
                                    stats.put("backupEntryCount", ReflectionHelper.invokeMethod(localStats, "getBackupEntryCount"));
                                    stats.put("lockedEntryCount", ReflectionHelper.invokeMethod(localStats, "getLockedEntryCount"));
                                    stats.put("dirtyEntryCount", ReflectionHelper.invokeMethod(localStats, "getDirtyEntryCount"));
                                    stats.put("hits", ReflectionHelper.invokeMethod(localStats, "getHits"));
                                    Object heapCost = ReflectionHelper.invokeMethod(localStats, "getHeapCost");
                                    stats.put("heapCost", heapCost);
                                    info.put("stats", stats);
                                }
                            } catch (Exception ignored) {}
                        }
                        objList.add(info);
                    }
                }
                result.put("distributedObjects", objList);
                result.put("distributedObjectCount", objList.size());
                return result;
            } catch (Exception e) {
                result.put("error", "Hazelcast: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return result;
            }
        }

        // Infinispan
        for (Object cm : beansOfType("org.infinispan.manager.EmbeddedCacheManager")) {
            try {
                result.put("provider", "Infinispan");
                Object cacheNames = ReflectionHelper.invokeMethod(cm, "getCacheNames");
                List<Map<String, Object>> cacheList = new ArrayList<>();
                if (cacheNames instanceof Collection<?> names) {
                    for (Object name : names) {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("name", String.valueOf(name));
                        try {
                            Method getCache = cm.getClass().getMethod("getCache", String.class);
                            Object cache = getCache.invoke(cm, name);
                            if (cache != null) {
                                Object stats = ReflectionHelper.invokeMethod(cache, "getAdvancedCache");
                                stats = stats != null ? ReflectionHelper.invokeMethod(stats, "getStats") : null;
                                if (stats != null) {
                                    Map<String, Object> s = new LinkedHashMap<>();
                                    s.put("evictions", ReflectionHelper.invokeMethod(stats, "getEvictions"));
                                    s.put("hits", ReflectionHelper.invokeMethod(stats, "getHits"));
                                    s.put("misses", ReflectionHelper.invokeMethod(stats, "getMisses"));
                                    s.put("removeHits", ReflectionHelper.invokeMethod(stats, "getRemoveHits"));
                                    s.put("stores", ReflectionHelper.invokeMethod(stats, "getStores"));
                                    info.put("stats", s);
                                }
                                info.put("size", ReflectionHelper.invokeMethod(cache, "size"));
                            }
                        } catch (Exception ignored) {}
                        cacheList.add(info);
                    }
                }
                result.put("caches", cacheList);
                result.put("cacheCount", cacheList.size());
                return result;
            } catch (Exception e) {
                result.put("error", "Infinispan: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return result;
            }
        }

        if (result.isEmpty()) {
            result.put("error", "No Hazelcast or Infinispan cache manager found.");
        }
        return result;
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private List<Object> beansOfType(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            String[] names = ctx.getBeanNamesForType(clazz);
            List<Object> beans = new ArrayList<>();
            for (String n : names) {
                try {
                    beans.add(ctx.getBean(n));
                } catch (Exception ignored) {}
            }
            return beans;
        } catch (ClassNotFoundException e) {
            return Collections.emptyList();
        }
    }
}
