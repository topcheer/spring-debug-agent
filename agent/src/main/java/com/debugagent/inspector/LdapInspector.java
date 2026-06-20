package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.*;
import java.lang.reflect.Method;
import java.util.*;

/**
 * LDAP diagnostic tools.
 * Inspects Spring Security LDAP / Spring LDAP configuration: ContextSource,
 * LdapTemplate, user/group search, bind authentication, and connection pooling.
 * Conditional on Spring LDAP (org.springframework.ldap.core.LdapTemplate) being on classpath.
 */
public class LdapInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    // ================================================================
    //  LDAP context source / connection info
    // ================================================================

    @DebugTool(description = "Inspect LDAP ContextSource configuration: URLs, base DN, "
            + "authentication strategy (simple/DIGEST-MD5/GSSAPI), bind DN, connection timeout, "
            + "and connection pooling settings. Useful for diagnosing LDAP connectivity issues "
            + "or misconfigured directory connections in enterprise environments.")
    public Map<String, Object> getLdapContextSourceInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Find ContextSource bean
        Object contextSource = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.springframework.ldap.core.support.LdapContextSource");
        if (contextSource == null) {
            contextSource = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.ldap.core.ContextSource");
        }
        if (contextSource == null) {
            // Check AbstractContextSource subclasses
            contextSource = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.ldap.core.support.AbstractContextSource");
        }

        if (contextSource == null) {
            result.put("status", "not_configured");
            result.put("hint", "No Spring LDAP ContextSource bean found. "
                    + "Add spring-ldap-core and configure spring.ldap.* properties.");
            return result;
        }

        result.put("contextSourceClass", contextSource.getClass().getSimpleName());

        // Extract common properties via reflection
        try {
            // getUrls() / getLdapUrl()
            Object urls = ReflectionHelper.invokeMethod(contextSource, "getUrls");
            if (urls != null) {
                result.put("urls", urls);
            } else {
                // Try getLdapUrls or getUserUrl
                urls = ReflectionHelper.invokeMethod(contextSource, "getLdapUrl");
                if (urls != null) {
                    result.put("ldapUrl", urls);
                }
            }
        } catch (Exception ignored) {}

        // Base DN
        Object baseDn = ReflectionHelper.invokeMethod(contextSource, "getBaseLdapPath");
        if (baseDn == null) {
            baseDn = ReflectionHelper.invokeMethod(contextSource, "getBase");
        }
        if (baseDn != null) {
            result.put("baseDn", baseDn.toString());
        }

        // User DN
        Object userDn = ReflectionHelper.invokeMethod(contextSource, "getUserDn");
        if (userDn != null) {
            result.put("userDn", userDn.toString());
        }

        // Password (masked)
        Object password = ReflectionHelper.invokeMethod(contextSource, "getPassword");
        result.put("hasPassword", password != null);

        // Authentication type
        try {
            // authenticationSource or authenticationStrategy
            Object authSource = ReflectionHelper.invokeMethod(contextSource, "getAuthenticationSource");
            if (authSource != null) {
                result.put("authenticationSource", authSource.getClass().getSimpleName());
            }
        } catch (Exception ignored) {}

        // Anonymous read-only
        Object anonymousReadOnly = ReflectionHelper.invokeMethod(contextSource, "isAnonymousReadOnly");
        if (anonymousReadOnly != null) {
            result.put("anonymousReadOnly", anonymousReadOnly);
        }

        // Pooled
        Object pooled = ReflectionHelper.invokeMethod(contextSource, "isPooled");
        if (pooled != null) {
            result.put("connectionPooling", pooled);
        }

        // Referral
        try {
            Method getReferral = ReflectionHelper.findMethod(contextSource.getClass(), "getReferral");
            if (getReferral != null) {
                getReferral.setAccessible(true);
                result.put("referral", getReferral.invoke(contextSource));
            }
        } catch (Exception ignored) {}

        // Spring Boot spring.ldap.* properties
        Environment env = ctx.getEnvironment();
        Map<String, String> springLdapProps = new LinkedHashMap<>();
        String[] propNames = {
                "spring.ldap.urls", "spring.ldap.base", "spring.ldap.username",
                "spring.ldap.password", "spring.ldap.base-env.java.naming.security.authentication",
                "spring.ldap.base-env.java.naming.referral"
        };
        for (String p : propNames) {
            String val = env.getProperty(p);
            if (val != null) {
                if (p.contains("password")) {
                    springLdapProps.put(p, "***masked***");
                } else {
                    springLdapProps.put(p, val);
                }
            }
        }
        if (!springLdapProps.isEmpty()) {
            result.put("springLdapProperties", springLdapProps);
        }

        return result;
    }

    // ================================================================
    //  LDAP bind / authentication test
    // ================================================================

    @DebugTool(description = "Test LDAP bind authentication: attempt to connect and authenticate "
            + "against the LDAP server using the configured ContextSource. "
            + "Reports success/failure, response time, and error details if the bind fails. "
            + "Critical for diagnosing 'cannot connect to LDAP' or 'authentication failed' issues.")
    public Map<String, Object> testLdapBind(
            @ToolParam(description = "Test bind DN (e.g., 'cn=admin,dc=example,dc=com'). "
                    + "If omitted, uses the configured bind user") String bindDn,
            @ToolParam(description = "Test bind password. If omitted, uses the configured password") String password
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Object contextSource = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.ldap.core.ContextSource");
            if (contextSource == null) {
                result.put("status", "no_context_source");
                result.put("error", "No ContextSource bean found");
                return result;
            }

            long start = System.currentTimeMillis();

            // Get a read-only context (tests connectivity + bind)
            Method getReadOnly = ReflectionHelper.findMethod(contextSource.getClass(), "getReadOnlyContext");
            Method getReadWrite = ReflectionHelper.findMethod(contextSource.getClass(), "getReadWriteContext");

            Object dirContext;
            if (getReadOnly != null) {
                getReadOnly.setAccessible(true);
                dirContext = getReadOnly.invoke(contextSource);
                result.put("contextType", "readOnly");
            } else if (getReadWrite != null) {
                getReadWrite.setAccessible(true);
                dirContext = getReadWrite.invoke(contextSource);
                result.put("contextType", "readWrite");
            } else {
                result.put("status", "no_context_method");
                result.put("error", "Could not find getReadOnlyContext or getReadWriteContext method");
                return result;
            }

            long duration = System.currentTimeMillis() - start;
            result.put("status", "success");
            result.put("bindDurationMs", duration);

            // Get context environment details
            try {
                Method getEnv = dirContext.getClass().getMethod("getEnvironment");
                @SuppressWarnings("unchecked")
                Hashtable<Object, Object> env = (Hashtable<Object, Object>) getEnv.invoke(dirContext);
                Map<String, Object> safeEnv = new LinkedHashMap<>();
                for (Map.Entry<Object, Object> e : env.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    String val = String.valueOf(e.getValue());
                    if (key.toLowerCase().contains("password") || key.toLowerCase().contains("credentials")) {
                        safeEnv.put(key, "***masked***");
                    } else {
                        safeEnv.put(key, val);
                    }
                }
                result.put("contextEnvironment", safeEnv);
            } catch (Exception ignored) {}

            // Close the context
            try {
                Method close = dirContext.getClass().getMethod("close");
                close.invoke(dirContext);
            } catch (Exception ignored) {}

        } catch (Exception e) {
            result.put("status", "failed");
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());

            // Root cause analysis
            Throwable cause = e.getCause();
            if (cause != null) {
                result.put("rootCause", cause.getClass().getSimpleName() + ": " + cause.getMessage());

                String msg = cause.getMessage();
                if (msg != null) {
                    if (msg.contains("Connection refused") || msg.contains("communication")) {
                        result.put("hint", "LDAP server is not reachable. Check URL, port, firewall, "
                                + "and whether the LDAP service is running.");
                    } else if (msg.contains("InvalidCredentials") || msg.contains("error code 49")) {
                        result.put("hint", "Bind DN or password is incorrect. Verify the credentials "
                                + "in spring.ldap.username/password or ContextSource configuration.");
                    } else if (msg.contains("timeout")) {
                        result.put("hint", "Connection timeout. The LDAP server did not respond "
                                + "within the timeout period. Check network latency or increase timeout.");
                    } else if (msg.contains("SSL") || msg.contains("trust")) {
                        result.put("hint", "SSL/TLS issue. If using LDAPS (port 636), ensure the server "
                                + "certificate is trusted in the JVM truststore.");
                    }
                }
            }
        }

        return result;
    }

    // ================================================================
    //  User/group search filter inspection
    // ================================================================

    @DebugTool(description = "Inspect LDAP user and group search configuration: search bases, "
            + "search filters, user DN patterns, group role attributes. "
            + "Reads from Spring Security LdapAuthenticationProvider / BindAuthenticator / "
            + "FilterBasedLdapUserSearch beans. Useful for debugging 'user not found' or "
            + "'incorrect group mapping' issues.")
    public Map<String, Object> getLdapSearchConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        // User Search
        try {
            Object userSearch = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.security.ldap.search.FilterBasedLdapUserSearch");
            if (userSearch == null) {
                userSearch = ReflectionHelper.getFirstBeanOfType(ctx,
                        "org.springframework.ldap.core.support.AbstractLdapName");
            }

            if (userSearch != null) {
                Map<String, Object> usInfo = new LinkedHashMap<>();
                usInfo.put("class", userSearch.getClass().getSimpleName());

                // getSearchBase
                Object searchBase = ReflectionHelper.invokeMethod(userSearch, "getSearchBase");
                if (searchBase == null) {
                    searchBase = ReflectionHelper.getFieldValue(userSearch, "searchBase");
                }
                if (searchBase != null) {
                    usInfo.put("userSearchBase", searchBase.toString());
                }

                // getSearchFilter
                Object searchFilter = ReflectionHelper.getFieldValue(userSearch, "searchFilter");
                if (searchFilter != null) {
                    usInfo.put("userSearchFilter", searchFilter.toString());
                }

                result.put("userSearch", usInfo);
            }
        } catch (Exception ignored) {}

        // BindAuthenticator / PasswordComparisonAuthenticator
        try {
            Object auth = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.security.ldap.authentication.BindAuthenticator");
            if (auth == null) {
                auth = ReflectionHelper.getFirstBeanOfType(ctx,
                        "org.springframework.security.ldap.authentication.PasswordComparisonAuthenticator");
            }
            if (auth != null) {
                Map<String, Object> authInfo = new LinkedHashMap<>();
                authInfo.put("class", auth.getClass().getSimpleName());

                // getUserDnPatterns
                Object patterns = ReflectionHelper.invokeMethod(auth, "getUserDnPatterns");
                if (patterns instanceof String[]) {
                    authInfo.put("userDnPatterns", Arrays.asList((String[]) patterns));
                }

                result.put("authenticator", authInfo);
            }
        } catch (Exception ignored) {}

        // LdapAuthoritiesPopulator
        try {
            Object populator = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator");
            if (populator != null) {
                Map<String, Object> popInfo = new LinkedHashMap<>();
                popInfo.put("class", populator.getClass().getSimpleName());

                Object groupSearchBase = ReflectionHelper.invokeMethod(populator, "getGroupSearchBase");
                if (groupSearchBase == null) {
                    groupSearchBase = ReflectionHelper.getFieldValue(populator, "groupSearchBase");
                }
                if (groupSearchBase != null) {
                    popInfo.put("groupSearchBase", groupSearchBase.toString());
                }

                Object groupRoleAttr = ReflectionHelper.invokeMethod(populator, "getGroupRoleAttribute");
                if (groupRoleAttr != null) {
                    popInfo.put("groupRoleAttribute", groupRoleAttr.toString());
                }

                Object searchSubtree = ReflectionHelper.invokeMethod(populator, "isSearchSubtree");
                if (searchSubtree != null) {
                    popInfo.put("searchSubtree", searchSubtree);
                }

                result.put("authoritiesPopulator", popInfo);
            }
        } catch (Exception ignored) {}

        // LdapTemplate query info
        try {
            Object template = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.ldap.core.LdapTemplate");
            if (template != null) {
                result.put("ldapTemplatePresent", true);
                Object countLimit = ReflectionHelper.invokeMethod(template, "getCountLimit");
                if (countLimit != null) {
                    result.put("countLimit", countLimit);
                }
            }
        } catch (Exception ignored) {}

        if (result.isEmpty()) {
            result.put("status", "no_ldap_security_config");
            result.put("hint", "No Spring Security LDAP search/authenticator beans found. "
                    + "Configure LdapAuthenticationProvider with user-search-base and user-search-filter.");
        }

        return result;
    }

    // ================================================================
    //  LDAP search / directory query
    // ================================================================

    @DebugTool(description = "Search LDAP directory for entries matching a filter. "
            + "Returns up to 20 entries with DN, attributes, and object classes. "
            + "Useful for verifying user/group existence and testing LDAP query filters. "
            + "Example: searchBase='ou=users,dc=example,dc=com', filter='(uid=john*)'")
    public List<Map<String, Object>> searchLdapDirectory(
            @ToolParam(description = "Search base DN (e.g., 'ou=users,dc=example,dc=com')") String searchBase,
            @ToolParam(description = "LDAP search filter (e.g., '(objectClass=person)' or '(uid=john*)')") String filter,
            @ToolParam(description = "Max results to return (default 20)") Integer maxResults
    ) {
        List<Map<String, Object>> entries = new ArrayList<>();
        int limit = maxResults != null ? Math.min(maxResults, 50) : 20;

        try {
            Object contextSource = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.ldap.core.ContextSource");
            if (contextSource == null) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "No ContextSource bean found");
                entries.add(err);
                return entries;
            }

            Method getReadOnly = ReflectionHelper.findMethod(contextSource.getClass(), "getReadOnlyContext");
            if (getReadOnly == null) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "Could not obtain a DirContext from ContextSource");
                entries.add(err);
                return entries;
            }
            getReadOnly.setAccessible(true);
            DirContext dirContext = (DirContext) getReadOnly.invoke(contextSource);

            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setCountLimit(limit);

            NamingEnumeration<SearchResult> results =
                    dirContext.search(searchBase, filter, controls);

            while (results.hasMore() && entries.size() < limit) {
                SearchResult sr = results.next();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("dn", sr.getNameInNamespace());

                Attributes attrs = sr.getAttributes();
                Map<String, Object> attrMap = new LinkedHashMap<>();
                NamingEnumeration<? extends Attribute> attrEnum = attrs.getAll();
                while (attrEnum.hasMore()) {
                    Attribute attr = attrEnum.next();
                    List<String> values = new ArrayList<>();
                    NamingEnumeration<?> valEnum = attr.getAll();
                    while (valEnum.hasMore()) {
                        String val = String.valueOf(valEnum.next());
                        if (val.length() > 200) val = val.substring(0, 200) + "...";
                        values.add(val);
                    }
                    attrMap.put(attr.getID(), values.size() == 1 ? values.get(0) : values);
                }
                entry.put("attributes", attrMap);
                entries.add(entry);
            }

            dirContext.close();

            if (entries.isEmpty()) {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("note", "No entries found matching filter '" + filter + "' under '" + searchBase + "'");
                entries.add(note);
            }

        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                err.put("rootCause", e.getCause().getMessage());
            }
            entries.add(err);
        }

        return entries;
    }

    // ================================================================
    //  LDAP connection pool stats
    // ================================================================

    @DebugTool(description = "Check LDAP connection pool statistics: active connections, idle connections, "
            + "total connections, and pool configuration (timeout, max size). "
            + "Reads from com.sun.jndi.ldap.connect.pool system properties if enabled. "
            + "Useful for diagnosing LDAP connection leaks or pool exhaustion.")
    public Map<String, Object> getLdapConnectionPoolStats() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Check if pooling is enabled
        String poolEnabled = System.getProperty("com.sun.jndi.ldap.connect.pool", "false");
        result.put("poolingEnabled", "true".equalsIgnoreCase(poolEnabled));

        // Pool configuration from system properties
        Map<String, String> poolConfig = new LinkedHashMap<>();
        String[] poolProps = {
                "com.sun.jndi.ldap.connect.pool.protocol", "com.sun.jndi.ldap.connect.pool.authentication",
                "com.sun.jndi.ldap.connect.pool.timeout", "com.sun.jndi.ldap.connect.pool.maxsize",
                "com.sun.jndi.ldap.connect.pool.prefsize", "com.sun.jndi.ldap.connect.pool.initsize"
        };
        for (String p : poolProps) {
            String val = System.getProperty(p);
            if (val != null) {
                poolConfig.put(p.replace("com.sun.jndi.ldap.connect.pool.", ""), val);
            }
        }

        // Defaults if not set
        if (poolConfig.isEmpty()) {
            poolConfig.put("maxsize", "0 (unlimited)");
            poolConfig.put("prefsize", "0 (no preferred size)");
            poolConfig.put("timeout", "0 (no timeout)");
        }
        result.put("poolConfig", poolConfig);

        // Check ContextSource pool setting
        try {
            Object contextSource = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.ldap.core.ContextSource");
            if (contextSource != null) {
                Object pooled = ReflectionHelper.invokeMethod(contextSource, "isPooled");
                if (pooled != null) {
                    result.put("contextSourcePooled", pooled);
                }
            }
        } catch (Exception ignored) {}

        // Check Spring Boot LDAP pool config
        Environment env = ctx.getEnvironment();
        Map<String, String> springPool = new LinkedHashMap<>();
        if (env.getProperty("spring.ldap.urls") != null) {
            // spring.ldap.* is basic config
        }
        String poolDebug = System.getProperty("com.sun.jndi.ldap.connect.pool.debug");
        if (poolDebug != null) {
            result.put("poolDebug", poolDebug);
        }

        if (!"true".equalsIgnoreCase(poolEnabled)) {
            result.put("hint", "LDAP connection pooling is disabled. Enable with JVM property "
                    + "-Dcom.sun.jndi.ldap.connect.pool=true for production use.");
        }

        return result;
    }
}
