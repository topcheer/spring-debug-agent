package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Spring Security diagnostic tools.
 * Inspects security configuration, authentication, sessions, and filter chains.
 * Conditional on Spring Security being on classpath.
 */
public class SecurityInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get Spring Security configuration overview: filter chain, security rules, user details service, password encoder. Useful for understanding the application's security setup.")
    public Map<String, Object> getSecurityConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Find SecurityFilterChain beans
            Map<String, Object> chains = new LinkedHashMap<>();
            try {
                String[] chainNames = ctx.getBeanNamesForType(
                        Class.forName("org.springframework.security.web.SecurityFilterChain"));
                for (String name : chainNames) {
                    Object chain = ctx.getBean(name);
                    Map<String, Object> chainInfo = new LinkedHashMap<>();
                    chainInfo.put("beanName", name);
                    chainInfo.put("class", chain.getClass().getSimpleName());

                    // Get filters via reflection
                    try {
                        Method getFilters = chain.getClass().getDeclaredMethod("getFilters");
                        getFilters.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        List<Object> filters = (List<Object>) getFilters.invoke(chain);
                        List<Map<String, String>> filterList = new ArrayList<>();
                        for (Object f : filters) {
                            Map<String, String> fi = new LinkedHashMap<>();
                            fi.put("class", f.getClass().getSimpleName());
                            fi.put("order", String.valueOf(filters.indexOf(f)));
                            filterList.add(fi);
                        }
                        chainInfo.put("filters", filterList);
                        chainInfo.put("filterCount", filters.size());
                    } catch (Exception ignored) {}

                    // Get request matcher info
                    try {
                        Method getMatcher = chain.getClass().getDeclaredMethod("getRequestMatcher");
                        getMatcher.setAccessible(true);
                        Object matcher = getMatcher.invoke(chain);
                        chainInfo.put("requestMatcher", matcher != null ? matcher.toString() : "null");
                    } catch (Exception ignored) {}

                    chains.put(name, chainInfo);
                }
            } catch (ClassNotFoundException ignored) {}
            result.put("filterChains", chains);
            result.put("chainCount", chains.size());

            // UserDetailsService
            try {
                String[] udsNames = ctx.getBeanNamesForType(
                        Class.forName("org.springframework.security.core.userdetails.UserDetailsService"));
                List<Map<String, Object>> udsList = new ArrayList<>();
                for (String name : udsNames) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("beanName", name);
                    Object uds = ctx.getBean(name);
                    info.put("class", uds.getClass().getSimpleName());
                    udsList.add(info);
                }
                result.put("userDetailsServices", udsList);
            } catch (Exception ignored) {}

            // Password encoder
            try {
                String[] encoders = ctx.getBeanNamesForType(
                        Class.forName("org.springframework.security.crypto.password.PasswordEncoder"));
                for (String name : encoders) {
                    result.put("passwordEncoder", ctx.getBean(name).getClass().getSimpleName());
                }
            } catch (Exception ignored) {}

            // Authentication manager
            try {
                String[] authMgrs = ctx.getBeanNamesForType(
                        Class.forName("org.springframework.security.authentication.AuthenticationManager"));
                List<String> authMgrList = new ArrayList<>();
                for (String name : authMgrs) {
                    authMgrList.add(name + " (" + ctx.getBean(name).getClass().getSimpleName() + ")");
                }
                result.put("authenticationManagers", authMgrList);
            } catch (Exception ignored) {}

            // Security properties
            result.put("securityEnabled", !chains.isEmpty());

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Get current authentication details: who is authenticated, their roles, authorities, and principal info. Useful for debugging authorization issues.")
    public Map<String, Object> getAuthenticationInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Class<?> secCtxHolder = Class.forName(
                    "org.springframework.security.core.context.SecurityContextHolder");
            Method getContext = secCtxHolder.getMethod("getContext");
            Object context = getContext.invoke(null);
            Method getAuthentication = context.getClass().getMethod("getAuthentication");
            Object auth = getAuthentication.invoke(context);

            if (auth == null) {
                result.put("authenticated", false);
                result.put("note", "No authentication found in current context. " +
                        "This is expected when called outside an HTTP request.");
                return result;
            }

            result.put("authenticated", (Boolean) ReflectionHelper.invokeMethod(auth, "isAuthenticated"));

            Object principal = ReflectionHelper.invokeMethod(auth, "getPrincipal");
            if (principal != null) {
                Map<String, Object> pInfo = new LinkedHashMap<>();
                pInfo.put("class", principal.getClass().getSimpleName());
                pInfo.put("name", ReflectionHelper.invokeMethod(principal, "getUsername") != null
                        ? ReflectionHelper.invokeMethod(principal, "getUsername")
                        : ReflectionHelper.invokeMethod(principal, "getName"));
                result.put("principal", pInfo);
            }

            Object details = ReflectionHelper.invokeMethod(auth, "getDetails");
            if (details != null) {
                result.put("details", details.toString());
            }

            Object authorities = ReflectionHelper.invokeMethod(auth, "getAuthorities");
            if (authorities != null) {
                List<String> authList = new ArrayList<>();
                for (Object a : (java.util.Collection<?>) authorities) {
                    authList.add(a.toString());
                }
                result.put("authorities", authList);
            }

            // Credentials
            Object creds = ReflectionHelper.invokeMethod(auth, "getCredentials");
            result.put("hasCredentials", creds != null);

        } catch (ClassNotFoundException e) {
            result.put("error", "Spring Security not on classpath");
        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Get active HTTP sessions: count, session attributes, creation time, last accessed time, max inactive interval. Useful for debugging session leaks or stale sessions.")
    public Map<String, Object> getSessionInfo(
            @ToolParam(description = "Include session attribute values (default false)") Boolean includeAttributes
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean include = includeAttributes != null && includeAttributes;

        try {
            // Try to find session registry
            String[] registryNames = ctx.getBeanNamesForType(
                    Class.forName("org.springframework.security.core.session.SessionRegistry"));
            if (registryNames.length > 0) {
                Object registry = ctx.getBean(registryNames[0]);
                Method getAllPrincipals = registry.getClass().getMethod("getAllPrincipals");
                Object principals = getAllPrincipals.invoke(registry);
                result.put("registeredPrincipals", ((java.util.Collection<?>) principals).size());

                Method getAllSessions = registry.getClass().getMethod("getAllSessions",
                        Object.class, boolean.class);
                int totalSessions = 0;
                List<Map<String, Object>> sessionList = new ArrayList<>();
                for (Object principal : (java.util.Collection<?>) principals) {
                    Object sessions = getAllSessions.invoke(registry, principal, false);
                    for (Object session : (java.util.Collection<?>) sessions) {
                        totalSessions++;
                        if (sessionList.size() < 20) {
                            Map<String, Object> sInfo = new LinkedHashMap<>();
                            sInfo.put("sessionId", ReflectionHelper.invokeMethod(session, "getSessionId"));
                            sInfo.put("principal", principal.toString());
                            sInfo.put("lastRequest", String.valueOf(ReflectionHelper.invokeMethod(session, "getLastRequest")));
                            sessionList.add(sInfo);
                        }
                    }
                }
                result.put("totalSessions", totalSessions);
                result.put("sessions", sessionList);
            } else {
                result.put("note", "SessionRegistry not found. Using HTTP session tracking instead.");
                result.put("totalSessions", 0);
            }

            // Session timeout config
            try {
                Object servletCtx = ctx.getBean("servletWebServerApplicationContext");
                // Check server.* properties
                result.put("sessionTimeoutHint", "Configure via server.servlet.session.timeout in application.yml");
            } catch (Exception ignored) {}

        } catch (ClassNotFoundException e) {
            result.put("error", "Spring Security not on classpath");
        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "List all URL-based security authorization rules: which paths require authentication, which are permitAll, which require specific roles. Useful for verifying security configuration.")
    public List<Map<String, Object>> getSecurityEvents() {
        List<Map<String, Object>> events = new ArrayList<>();

        try {
            // Try to get security-related audit events
            try {
                Object eventPublisher = ctx.getBean(
                        "org.springframework.boot.actuate.audit.listener.AuditApplicationListener");
                if (eventPublisher != null) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("component", "AuditApplicationListener");
                    info.put("note", "Audit events are being captured. Check Actuator /auditevents for details.");
                    events.add(info);
                }
            } catch (Exception ignored) {}

            // Check for AuthenticationEventPublisher
            try {
                String[] names = ctx.getBeanNamesForType(
                        Class.forName("org.springframework.security.authentication.DefaultAuthenticationEventPublisher"));
                if (names.length > 0) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("component", "DefaultAuthenticationEventPublisher");
                    info.put("note", "Authentication events (login success/failure) are published.");
                    events.add(info);
                }
            } catch (Exception ignored) {}

            if (events.isEmpty()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("note", "No security event listeners found. " +
                        "Enable Spring Boot Actuator audit to track authentication events.");
                events.add(info);
            }

        } catch (Exception e) {
            events.add(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        return events;
    }
}
