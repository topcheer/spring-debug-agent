package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * Spring Authorization Server (OAuth2) diagnostic tools.
 * Inspects the authorization server configuration, registered clients,
 * tokens and consents. All access is via reflection because
 * spring-authorization-server is not on the compile classpath.
 */
public class OAuth2Inspector implements ApplicationContextAware {

    private static final String AUTH_SERVER_PKG =
            "org.springframework.security.oauth2.server.authorization.";

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Show Spring Authorization Server configuration: issuer URL, token endpoints, supported grant types, and token formats. Returns presence info when the authorization server is not configured.")
    public Map<String, Object> getAuthorizationServerConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (!ReflectionHelper.isClassAvailable(AUTH_SERVER_PKG + "settings.AuthorizationServerSettings")) {
            result.put("available", false);
            result.put("note", "spring-authorization-server is not on the classpath. " +
                    "Add spring-boot-starter-oauth2-authorization-server to enable.");
            return result;
        }

        Object settings = ReflectionHelper.getFirstBeanOfType(ctx,
                AUTH_SERVER_PKG + "settings.AuthorizationServerSettings");
        if (settings == null) {
            result.put("available", true);
            result.put("note", "AuthorizationServerSettings bean not found. " +
                    "Define a @Bean of AuthorizationServerSettings to enable.");
            return result;
        }

        result.put("available", true);
        result.put("settingsClass", settings.getClass().getSimpleName());

        // Common getters on AuthorizationServerSettings
        Object issuer = ReflectionHelper.invokeMethod(settings, "getIssuer");
        if (issuer != null) result.put("issuer", issuer);

        Map<String, String> endpoints = new LinkedHashMap<>();
        for (String name : new String[]{"authorizationEndpoint", "tokenEndpoint", "jwkSetEndpoint",
                "tokenRevocationEndpoint", "tokenIntrospectionEndpoint",
                "oidcUserInfoEndpoint", "oidcLogoutEndpoint", "deviceAuthorizationEndpoint",
                "deviceVerificationEndpoint"}) {
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            Object v = ReflectionHelper.invokeMethod(settings, getter);
            if (v != null) endpoints.put(name, v.toString());
        }
        if (!endpoints.isEmpty()) result.put("endpoints", endpoints);

        Object refreshToken = ReflectionHelper.invokeMethod(settings, "getRefreshTokenTimeToLive");
        if (refreshToken != null) result.put("refreshTokenTtl", refreshToken.toString());
        Object accessToken = ReflectionHelper.invokeMethod(settings, "getAccessTokenTimeToLive");
        if (accessToken != null) result.put("accessTokenTtl", accessToken.toString());
        Object codeTtl = ReflectionHelper.invokeMethod(settings, "getAuthorizationCodeTimeToLive");
        if (codeTtl != null) result.put("authorizationCodeTtl", codeTtl.toString());
        Object reuseRefresh = ReflectionHelper.invokeMethod(settings, "isReuseRefreshTokens");
        if (reuseRefresh != null) result.put("reuseRefreshTokens", reuseRefresh);

        return result;
    }

    @DebugTool(description = "List registered OAuth2 clients: client ID, grant types, scopes, redirect URIs, and token settings. Inspects RegisteredClientRepository beans.")
    public List<Map<String, Object>> getOAuth2Clients() {
        List<Map<String, Object>> results = new ArrayList<>();

        if (!ReflectionHelper.isClassAvailable(AUTH_SERVER_PKG + "client.RegisteredClient")) {
            results.add(Map.of("error", "spring-authorization-server not on classpath"));
            return results;
        }

        List<Object> repos = ReflectionHelper.getBeansOfType(ctx,
                AUTH_SERVER_PKG + "client.RegisteredClientRepository");
        if (repos.isEmpty()) {
            results.add(Map.of("info", "No RegisteredClientRepository found. " +
                    "Register clients (in-memory or JdbcRegisteredClientRepository) to enable."));
            return results;
        }

        for (Object repo : repos) {
            // try findAll() first (InMemoryRegisteredClientRepository exposes it)
            Object all = null;
            try {
                all = ReflectionHelper.invokeMethod(repo, "findAll");
            } catch (Exception ignored) {}

            if (all instanceof Iterable<?> iter) {
                for (Object client : iter) {
                    Map<String, Object> info = describeClient(client);
                    if (info != null) results.add(info);
                }
            } else {
                // Fallback: try reading the internal 'clients' map field (InMemoryRegisteredClientRepository)
                try {
                    Object fieldVal = ReflectionHelper.getFieldValue(repo, "clients");
                    if (fieldVal instanceof Iterable<?> fieldIter) {
                        for (Object entry : fieldIter) {
                            // entries may be Map.Entry<id, RegisteredClient>
                            Object client = entry;
                            if (entry instanceof Map.Entry<?, ?> me) {
                                client = me.getValue();
                            }
                            Map<String, Object> info = describeClient(client);
                            if (info != null) results.add(info);
                        }
                    }
                } catch (Exception ignored) {}

                if (results.isEmpty()) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("repositoryClass", repo.getClass().getSimpleName());
                    info.put("note", "RegisteredClientRepository present but findAll() not available. " +
                            "Clients may be persisted in a database — query the underlying store directly.");
                    results.add(info);
                }
            }
        }

        if (results.isEmpty()) {
            results.add(Map.of("info", "No registered OAuth2 clients."));
        }
        return results;
    }

    @DebugTool(description = "Report OAuth2 token information: active access tokens and refresh tokens count via the OAuth2AuthorizationService, plus token store class. In-memory stores may not support full enumeration.")
    public Map<String, Object> getOAuth2Tokens() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (!ReflectionHelper.isClassAvailable(AUTH_SERVER_PKG + "OAuth2AuthorizationService")) {
            result.put("available", false);
            result.put("note", "spring-authorization-server not on classpath");
            return result;
        }

        List<Object> services = ReflectionHelper.getBeansOfType(ctx,
                AUTH_SERVER_PKG + "OAuth2AuthorizationService");
        if (services.isEmpty()) {
            result.put("available", true);
            result.put("note", "No OAuth2AuthorizationService bean found.");
            return result;
        }

        List<Map<String, Object>> serviceInfo = new ArrayList<>();
        for (Object service : services) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("serviceClass", service.getClass().getSimpleName());

            // JWK token store introspection (best effort)
            Object tokenStore = ReflectionHelper.invokeMethod(service, "getTokenStore");
            if (tokenStore != null) {
                info.put("tokenStore", tokenStore.getClass().getSimpleName());
                // Try getAccessTokens / getRefreshTokens on legacy token stores
                for (String m : new String[]{"findTokensByClientId", "getAllAccessTokens", "getAllRefreshTokens"}) {
                    Object r = ReflectionHelper.invokeMethod(tokenStore, m);
                    if (r instanceof Collection<?> col) {
                        info.put(m + "Count", col.size());
                    }
                }
            }

            serviceInfo.add(info);
        }
        result.put("services", serviceInfo);

        // Note: spring-authorization-server's in-memory service does not support enumeration.
        if (serviceInfo.size() == 1) {
            Object only = serviceInfo.get(0).get("serviceClass");
            if (only != null && only.toString().contains("InMemory")) {
                result.put("enumerationSupported", false);
                result.put("note", "In-memory OAuth2AuthorizationService cannot enumerate tokens. " +
                        "Use a JdbcOAuth2AuthorizationService and query the table directly.");
            }
        }

        return result;
    }

    @DebugTool(description = "List OAuth2 user consents: which users consented to which clients, with granted scopes. Inspects OAuth2AuthorizationConsentService when present.")
    public List<Map<String, Object>> getOAuth2Consents() {
        List<Map<String, Object>> results = new ArrayList<>();

        if (!ReflectionHelper.isClassAvailable(AUTH_SERVER_PKG + "OAuth2AuthorizationConsentService")) {
            results.add(Map.of("error", "spring-authorization-server not on classpath"));
            return results;
        }

        List<Object> services = ReflectionHelper.getBeansOfType(ctx,
                AUTH_SERVER_PKG + "OAuth2AuthorizationConsentService");
        if (services.isEmpty()) {
            results.add(Map.of("info", "No OAuth2AuthorizationConsentService found."));
            return results;
        }

        for (Object service : services) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("serviceClass", service.getClass().getSimpleName());

            // The service interface only offers findByClientId / findByPrincipalName.
            // Try known client IDs via context properties if available (best-effort).
            try {
                Object clients = ReflectionHelper.invokeMethod(service, "findAll");
                if (clients instanceof Iterable<?> iter) {
                    List<Map<String, Object>> consents = new ArrayList<>();
                    for (Object consent : iter) {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("clientId", ReflectionHelper.invokeMethod(consent, "getRegisteredClientId"));
                        c.put("principalName", ReflectionHelper.invokeMethod(consent, "getPrincipalName"));
                        Object scopes = ReflectionHelper.invokeMethod(consent, "getScopes");
                        if (scopes instanceof Collection<?> sc) {
                            c.put("scopes", sc);
                        }
                        consents.add(c);
                    }
                    info.put("consents", consents);
                    info.put("count", consents.size());
                } else {
                    info.put("note", "Consent service does not support findAll(); " +
                            "look up consents via findByClientId or findByPrincipalName.");
                }
            } catch (Exception e) {
                info.put("note", "Could not enumerate consents: " + e.getClass().getSimpleName());
            }

            results.add(info);
        }

        if (results.isEmpty()) {
            results.add(Map.of("info", "No OAuth2 consents recorded."));
        }
        return results;
    }

    // ==================== Helpers ====================

    private Map<String, Object> describeClient(Object client) {
        if (client == null) return null;
        Map<String, Object> info = new LinkedHashMap<>();
        Object id = ReflectionHelper.invokeMethod(client, "getClientId");
        info.put("clientId", id != null ? id : "unknown");

        Object clientIdIssuedAt = ReflectionHelper.invokeMethod(client, "getClientIdIssuedAt");
        if (clientIdIssuedAt != null) info.put("clientIdIssuedAt", clientIdIssuedAt.toString());

        Object secret = ReflectionHelper.invokeMethod(client, "getClientSecret");
        info.put("hasSecret", secret != null);

        Object grantTypes = ReflectionHelper.invokeMethod(client, "getAuthorizationGrantTypes");
        if (grantTypes instanceof Collection<?> gts) {
            List<String> gtList = new ArrayList<>();
            for (Object gt : gts) gtList.add(gt.toString());
            info.put("grantTypes", gtList);
        }

        Object scopes = ReflectionHelper.invokeMethod(client, "getScopes");
        if (scopes instanceof Collection<?> sc) {
            info.put("scopes", sc);
        }

        Object redirectUris = ReflectionHelper.invokeMethod(client, "getRedirectUris");
        if (redirectUris instanceof Collection<?> ru) {
            info.put("redirectUris", ru);
        }

        Object postLogout = ReflectionHelper.invokeMethod(client, "getPostLogoutRedirectUris");
        if (postLogout instanceof Collection<?> pl) {
            info.put("postLogoutRedirectUris", pl);
        }

        Object clientSettings = ReflectionHelper.invokeMethod(client, "getClientSettings");
        if (clientSettings != null) {
            info.put("requireProofKey", ReflectionHelper.invokeMethod(clientSettings, "isRequireProofKey"));
            info.put("requireAuthorizationConsent",
                    ReflectionHelper.invokeMethod(clientSettings, "isRequireAuthorizationConsent"));
            Object jwkSetUrl = ReflectionHelper.invokeMethod(clientSettings, "getJwkSetUrl");
            if (jwkSetUrl != null) info.put("jwkSetUrl", jwkSetUrl);
        }

        return info;
    }
}
