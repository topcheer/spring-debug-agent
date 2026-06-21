package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * Apache Zookeeper diagnostic tools.
 * Inspects ZNode tree, node data, watchers, ephemeral nodes, and cluster status.
 * Conditional on curator-framework or zookeeper being on classpath.
 */
public class ZookeeperInspector implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(ZookeeperInspector.class);

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    private Object getCuratorClient() {
        return ReflectionHelper.getFirstBeanOfType(ctx,
                "org.apache.curator.framework.CuratorFramework");
    }

    private Object getZkClient() {
        Object client = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.apache.zookeeper.ZooKeeper");
        return client;
    }

    @DebugTool(description = "List Zookeeper ZNode children at a given path: child names, "
            + "ephemeral owner, data length, and child count. "
            + "Useful for inspecting service registry, leader election, or lock nodes. "
            + "Default path is '/' (root).")
    public List<Map<String, Object>> getZkChildren(
            @ToolParam(description = "ZNode path (default: '/')", required = false) String path
    ) {
        List<Map<String, Object>> result = new ArrayList<>();

        String p = (path != null && !path.isBlank()) ? path : "/";

        // Try Curator first
        Object curator = getCuratorClient();
        if (curator != null) {
            try {
                Object builder = ReflectionHelper.invokeMethod(curator, "getChildren");
                if (builder != null) {
                    Object children = ReflectionHelper.invokeMethod(builder, "forPath", p);
                    if (children instanceof List) {
                        for (Object child : (List<?>) children) {
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("name", child.toString());
                            c.put("fullPath", p.equals("/") ? "/" + child : p + "/" + child);
                            result.add(c);
                        }
                        // If list is empty, add informational entry
                        if (((List<?>) children).isEmpty()) {
                            result.add(Map.of("status", "empty",
                                    "message", "Path '" + p + "' exists but has no child nodes",
                                    "path", p,
                                    "namespace", String.valueOf(ReflectionHelper.invokeMethod(curator, "getNamespace"))));
                        }
                    } else {
                        result.add(Map.of("status", "no_children",
                                "hint", "Path '" + p + "' has no children or forPath returned: " + children,
                                "path", p));
                    }
                } else {
                    result.add(Map.of("status", "reflection_error",
                            "hint", "Could not invoke getChildren() on " + curator.getClass().getName(),
                            "curatorType", curator.getClass().getName()));
                }
            } catch (Exception e) {
                result.add(Map.of("error", "Curator: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
                return result;
            }
        } else {
            // Curator not found, try raw ZooKeeper
            Object zk = getZkClient();
            if (zk != null) {
                try {
                    Object children = ReflectionHelper.invokeMethod(zk, "getChildren", p, false);
                    if (children instanceof List) {
                        for (Object child : (List<?>) children) {
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("name", child.toString());
                            c.put("fullPath", p.equals("/") ? "/" + child : p + "/" + child);
                            result.add(c);
                        }
                    }
                } catch (Exception e) {
                    result.add(Map.of("error", "ZooKeeper: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
                    return result;
                }
            }
        }

        if (result.isEmpty()) {
            result.add(Map.of("status", "not_configured",
                    "hint", "No CuratorFramework or ZooKeeper client found. Add curator-framework or zookeeper dependency.",
                    "path", p));
        }

        return result;
    }

    @DebugTool(description = "Get detailed ZNode information: data (as string/hex), stat (version, "
            + "creation time, modification time, ephemeral owner, data length, children count), "
            + "and ACL. Useful for inspecting configuration data, service metadata, or lock state.")
    public Map<String, Object> getZkNode(
            @ToolParam(description = "ZNode path (e.g., '/services/my-app')") String path
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        Object curator = getCuratorClient();
        if (curator != null) {
            try {
                // Get data
                Object data = ReflectionHelper.invokeMethod(curator, "getData");
                data = ReflectionHelper.invokeMethod(data, "forPath", path);
                if (data instanceof byte[]) {
                    byte[] bytes = (byte[]) data;
                    String str = new String(bytes);
                    result.put("dataLength", bytes.length);
                    result.put("dataString", str.length() > 500 ? str.substring(0, 500) + "..." : str);
                    // Also hex for small binary data
                    if (bytes.length < 100) {
                        StringBuilder hex = new StringBuilder();
                        for (byte b : bytes) hex.append(String.format("%02X", b));
                        result.put("dataHex", hex.toString());
                    }
                }

                // Get stat
                Object stat = ReflectionHelper.invokeMethod(curator, "checkExists");
                stat = ReflectionHelper.invokeMethod(stat, "forPath", path);
                if (stat != null) {
                    result.putAll(extractZkStat(stat));
                }
            } catch (Exception e) {
                result.put("error", "Curator: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return result;
        }

        Object zk = getZkClient();
        if (zk != null) {
            try {
                // Create a Stat holder via reflection
                Class<?> statClass = Class.forName("org.apache.zookeeper.data.Stat");
                Object stat = statClass.getDeclaredConstructor().newInstance();
                Object data = ReflectionHelper.invokeMethod(zk, "getData", path, false, stat);
                if (data instanceof byte[]) {
                    byte[] bytes = (byte[]) data;
                    String str = new String(bytes);
                    result.put("dataLength", bytes.length);
                    result.put("dataString", str.length() > 500 ? str.substring(0, 500) + "..." : str);
                }
                result.putAll(extractZkStat(stat));
            } catch (Exception e) {
                result.put("error", "ZooKeeper: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return result;
        }

        result.put("status", "not_configured");
        result.put("hint", "No Zookeeper client found");
        return result;
    }

    @DebugTool(description = "Inspect Zookeeper watchers: registered data watchers, child watchers, "
            + "and exists watchers. Shows watcher class and watched path. "
            + "Useful for debugging watcher leaks, stale watchers, or events not firing.")
    public Map<String, Object> getZkWatchers() {
        Map<String, Object> result = new LinkedHashMap<>();

        Object zk = getZkClient();
        if (zk == null) {
            result.put("status", "not_configured");
            return result;
        }

        try {
            // Get watch manager
            Object watchManager = ReflectionHelper.getFieldValue(zk, "watchManager");
            if (watchManager != null) {
                Object dataWatches = ReflectionHelper.getFieldValue(watchManager, "dataWatches");
                Object childWatches = ReflectionHelper.getFieldValue(watchManager, "childWatches");
                Object existWatches = ReflectionHelper.getFieldValue(watchManager, "existWatches");

                if (dataWatches instanceof Map) {
                    result.put("dataWatcherPaths", new ArrayList<>(((Map<?, ?>) dataWatches).keySet()));
                    result.put("dataWatcherCount", ((Map<?, ?>) dataWatches).size());
                }
                if (childWatches instanceof Map) {
                    result.put("childWatcherPaths", new ArrayList<>(((Map<?, ?>) childWatches).keySet()));
                    result.put("childWatcherCount", ((Map<?, ?>) childWatches).size());
                }
                if (existWatches instanceof Map) {
                    result.put("existWatcherCount", ((Map<?, ?>) existWatches).size());
                }
            }

            // Session info
            Object sessionId = ReflectionHelper.invokeMethod(zk, "getSessionId");
            result.put("sessionId", "0x" + Long.toHexString((Long) sessionId));

            Object sessionTimeout = ReflectionHelper.invokeMethod(zk, "getSessionTimeout");
            result.put("sessionTimeoutMs", sessionTimeout);

            Object state = ReflectionHelper.invokeMethod(zk, "getState");
            result.put("state", state != null ? state.toString() : "unknown");

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Curator listener info
        Object curator = getCuratorClient();
        if (curator != null) {
            try {
                Object listeners = ReflectionHelper.getFieldValue(curator, "listeners");
                if (listeners instanceof List) {
                    List<String> listenerClasses = new ArrayList<>();
                    for (Object l : (List<?>) listeners) {
                        listenerClasses.add(l.getClass().getSimpleName());
                    }
                    result.put("curatorListeners", listenerClasses);
                }
            } catch (Exception ignored) {}
        }

        // Spring properties
        Map<String, String> props = new LinkedHashMap<>();
        String[] propNames = {
                "spring.zookeeper.connect-string",
                "spring.zookeeper.session-timeout",
                "spring.zookeeper.connection-timeout",
                "spring.cloud.zookeeper.connect-string",
                "spring.cloud.zookeeper.discovery.instance-id",
                "spring.cloud.zookeeper.discovery.root"
        };
        for (String p : propNames) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null) props.put(p, val);
        }
        if (!props.isEmpty()) result.put("springProperties", props);

        return result;
    }

    @DebugTool(description = "Get Zookeeper cluster status and connection info: connection string, "
            + "server addresses, session state, negotiated session timeout, and "
            + "whether the client is connected to the ensemble. "
            + "Essential for diagnosing connection loss or session expiry issues.")
    public Map<String, Object> getZkClusterStatus() {
        Map<String, Object> result = new LinkedHashMap<>();

        Object zk = getZkClient();
        if (zk != null) {
            try {
                Object state = ReflectionHelper.invokeMethod(zk, "getState");
                result.put("connectionState", state != null ? state.toString() : "unknown");

                Object sessionId = ReflectionHelper.invokeMethod(zk, "getSessionId");
                result.put("sessionId", "0x" + Long.toHexString((Long) sessionId));

                Object sessionPasswd = ReflectionHelper.invokeMethod(zk, "getSessionPasswd");
                result.put("hasSessionPassword", sessionPasswd != null);

                Object negotiatedSessionTimeout = ReflectionHelper.invokeMethod(zk, "getNegotiatedSessionTimeout");
                result.put("negotiatedSessionTimeoutMs", negotiatedSessionTimeout);

                Object clientId = ReflectionHelper.invokeMethod(zk, "getSessionId");
                result.put("clientId", clientId);

                Object isSecure = ReflectionHelper.invokeMethod(zk, "isSecure");
                if (isSecure != null) result.put("secure", isSecure);

                Object isReadOnly = ReflectionHelper.invokeMethod(zk, "getTestable");
                // Xid / lastZxid
                Object lastZxid = ReflectionHelper.getFieldValue(zk, "lastZxid");
                if (lastZxid != null) result.put("lastZxid", lastZxid);

                // Connection address
                Object cnxn = ReflectionHelper.getFieldValue(zk, "cnxn");
                if (cnxn != null) {
                    Object remoteAddress = ReflectionHelper.invokeMethod(cnxn, "getRemoteSocketAddress");
                    if (remoteAddress != null) result.put("serverAddress", remoteAddress.toString());

                    Object localAddress = ReflectionHelper.invokeMethod(cnxn, "getLocalSocketAddress");
                    if (localAddress != null) result.put("localAddress", localAddress.toString());
                }
            } catch (Exception e) {
                result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        Object curator = getCuratorClient();
        if (curator != null) {
            try {
                Object state = ReflectionHelper.invokeMethod(curator, "getState");
                result.put("curatorState", state != null ? state.toString() : "unknown");

                Object curatorZkClient = ReflectionHelper.invokeMethod(curator, "getZookeeperClient");
                if (curatorZkClient != null) {
                    Object currentConnectionString = ReflectionHelper.invokeMethod(
                            curatorZkClient, "getCurrentConnectionString");
                    result.put("connectionString", currentConnectionString != null
                            ? currentConnectionString.toString() : null);

                    Object connectionTimeout = ReflectionHelper.invokeMethod(
                            curatorZkClient, "getConnectionTimeoutMs");
                    result.put("connectionTimeoutMs", connectionTimeout);

                    Object sessionTimeout = ReflectionHelper.invokeMethod(
                            curatorZkClient, "getSessionTimeoutMs");
                    result.put("sessionTimeoutMs", sessionTimeout);
                }

                // Namespace
                Object namespace = ReflectionHelper.invokeMethod(curator, "getNamespace");
                if (namespace != null) result.put("namespace", namespace);
            } catch (Exception e) {
                result.put("curatorError", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        if (result.isEmpty()) {
            result.put("status", "not_configured");
            result.put("hint", "No CuratorFramework or ZooKeeper client found.");
        }

        return result;
    }

    private Map<String, Object> extractZkStat(Object stat) {
        Map<String, Object> s = new LinkedHashMap<>();
        Object czxid = ReflectionHelper.invokeMethod(stat, "getCzxid");
        s.put("creationZxid", czxid != null ? "0x" + Long.toHexString((Long) czxid) : null);
        s.put("version", ReflectionHelper.invokeMethod(stat, "getVersion"));
        s.put("dataVersion", ReflectionHelper.invokeMethod(stat, "getVersion"));
        s.put("aclVersion", ReflectionHelper.invokeMethod(stat, "getAversion"));
        s.put("cVersion", ReflectionHelper.invokeMethod(stat, "getCversion"));
        s.put("childrenCount", ReflectionHelper.invokeMethod(stat, "getNumChildren"));
        s.put("dataLength", ReflectionHelper.invokeMethod(stat, "getDataLength"));
        s.put("ephemeralOwner", ReflectionHelper.invokeMethod(stat, "getEphemeralOwner"));
        s.put("creationTime", ReflectionHelper.invokeMethod(stat, "getCtime"));
        s.put("modificationTime", ReflectionHelper.invokeMethod(stat, "getMtime"));
        s.put("isEphemeral", ((Long) ReflectionHelper.invokeMethod(stat, "getEphemeralOwner")) != 0);
        return s;
    }
}
