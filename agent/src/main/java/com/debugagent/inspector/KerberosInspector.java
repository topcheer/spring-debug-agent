package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import java.io.File;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.*;

/**
 * Kerberos / SPNEGO diagnostic tools.
 * Inspects JAAS configuration, keytab files, login modules, ticket cache,
 * and Spring Security Kerberos/SPNEGO beans.
 * Conditional on Spring Security Kerberos (KerberosServiceAuthenticationProvider) being on classpath.
 */
public class KerberosInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    // ================================================================
    //  JAAS Configuration inspection
    // ================================================================

    @DebugTool(description = "Inspect the JVM's JAAS (Java Authentication and Authorization Service) "
            + "configuration: login module entries, control flags (required/requisite/sufficient/optional), "
            + "and options for each entry. Reads from JAAS config file, system properties, "
            + "and any dynamically registered Configuration. "
            + "Critical for debugging Kerberos login failures and SPNEGO SSO issues.")
    public Map<String, Object> getJaasConfiguration() {
        Map<String, Object> result = new LinkedHashMap<>();

        // JAAS config file location
        String jaasConfigPath = System.getProperty("java.security.auth.login.config");
        if (jaasConfigPath != null) {
            result.put("configFile", jaasConfigPath);
            File f = new File(jaasConfigPath);
            result.put("fileExists", f.exists());
        } else {
            result.put("configFile", "not_set (using default Configuration provider)");
        }

        // Security manager login config
        String securityConfig = System.getProperty("java.security.auth.login.config");
        result.put("systemProperty", securityConfig != null ? securityConfig : "not set");

        try {
            Configuration config = Configuration.getConfiguration();
            result.put("configClass", config.getClass().getName());

            // Common Kerberos application names
            String[] appNames = {
                    "com.sun.security.jgss.krb5.initiate",
                    "com.sun.security.jgss.krb5.accept",
                    "Krb5LoginModule",
                    "spnego-server",
                    "spnego-client",
                    "JaasClient",
                    "JaasServer"
            };

            List<Map<String, Object>> entries = new ArrayList<>();
            for (String appName : appNames) {
                try {
                    AppConfigurationEntry[] appEntries = config.getAppConfigurationEntry(appName);
                    if (appEntries != null && appEntries.length > 0) {
                        for (AppConfigurationEntry entry : appEntries) {
                            Map<String, Object> entryInfo = new LinkedHashMap<>();
                            entryInfo.put("appName", appName);
                            entryInfo.put("loginModule", entry.getLoginModuleName());
                            entryInfo.put("controlFlag", entry.getControlFlag().toString());
                            entryInfo.put("options", maskSensitiveOptions(entry.getOptions()));
                            entries.add(entryInfo);
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Also check default config entries
            try {
                AppConfigurationEntry[] otherEntries = config.getAppConfigurationEntry("other");
                if (otherEntries != null && otherEntries.length > 0) {
                    for (AppConfigurationEntry entry : otherEntries) {
                        Map<String, Object> entryInfo = new LinkedHashMap<>();
                        entryInfo.put("appName", "other");
                        entryInfo.put("loginModule", entry.getLoginModuleName());
                        entryInfo.put("controlFlag", entry.getControlFlag().toString());
                        entryInfo.put("options", maskSensitiveOptions(entry.getOptions()));
                        entries.add(entryInfo);
                    }
                }
            } catch (Exception ignored) {}

            result.put("entries", entries);
            result.put("entryCount", entries.size());

            if (entries.isEmpty()) {
                result.put("hint", "No JAAS entries found for common Kerberos application names. "
                        + "Check if -Djava.security.auth.login.config points to the correct JAAS config file.");
            }

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Check kerberos system properties
        Map<String, String> krbProps = getKerberosSystemProperties();
        if (!krbProps.isEmpty()) {
            result.put("kerberosSystemProperties", krbProps);
        }

        return result;
    }

    // ================================================================
    //  Keytab inspection
    // ================================================================

    @DebugTool(description = "Inspect Kerberos keytab file: list all principals in the keytab, "
            + "encryption types, and verify keytab file integrity. "
            + "Also checks the JAAS config's keyTab option path. "
            + "Essential for diagnosing 'KeyTab null' or keytab not found errors.")
    public Map<String, Object> inspectKeytab(
            @ToolParam(description = "Path to keytab file. If omitted, reads from JAAS config keyTab option") String keytabPath
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        String path = keytabPath;
        if (path == null || path.isBlank()) {
            // Try to find keytab path from JAAS config
            path = findKeytabFromJaasConfig();
        }

        if (path == null || path.isBlank()) {
            result.put("status", "no_keytab_path");
            result.put("hint", "No keytab path provided. Pass the path or configure it in JAAS config "
                    + "with 'keyTab=/path/to/keytab' option.");
            return result;
        }

        File keytab = new File(path);
        result.put("path", path);
        result.put("exists", keytab.exists());

        if (!keytab.exists()) {
            result.put("status", "file_not_found");
            result.put("hint", "Keytab file not found. Verify the path is correct and accessible "
                    + "by the JVM process. Check file permissions.");
            return result;
        }

        result.put("size", keytab.length());
        result.put("readable", keytab.canRead());
        result.put("lastModified", new Date(keytab.lastModified()).toString());

        // Use klist equivalent — parse keytab via sun.security classes
        try {
            Class<?> keyTabClass = Class.forName("sun.security.krb5.internal.ktab.KeyTab");
            Method create = keyTabClass.getMethod("getInstance", String.class);
            Object keyTab = create.invoke(null, path);

            // getEntries()
            Method getEntries = keyTabClass.getMethod("getEntries");
            Object[] entries = (Object[]) getEntries.invoke(keyTab);

            List<Map<String, Object>> principalList = new ArrayList<>();
            Set<String> seenPrincipals = new HashSet<>();

            for (Object entry : entries) {
                try {
                    // KeyTabEntry has getService() returning PrincipalName
                    Method getService = entry.getClass().getMethod("getService");
                    Object principalName = getService.invoke(entry);
                    Method getName = principalName.getClass().getMethod("getName");
                    String principal = String.valueOf(getName.invoke(principalName));

                    if (!seenPrincipals.contains(principal)) {
                        seenPrincipals.add(principal);
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("principal", principal);
                        principalList.add(p);
                    }

                    // Get key info
                    Method getKeyType = entry.getClass().getMethod("getKey");
                    Object key = getKeyType.invoke(entry);
                    if (key != null) {
                        Method getEType = key.getClass().getMethod("getEType");
                        int encType = (Integer) getEType.invoke(key);
                        String encName = describeEncType(encType);
                        if (!principalList.isEmpty()) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> last = principalList.get(principalList.size() - 1);
                            @SuppressWarnings("unchecked")
                            List<String> encTypes = (List<String>) last.computeIfAbsent(
                                    "encryptionTypes", k -> new ArrayList<String>());
                            if (!encTypes.contains(encName)) {
                                encTypes.add(encName);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            result.put("status", "valid");
            result.put("entryCount", entries.length);
            result.put("principals", principalList);

        } catch (ClassNotFoundException e) {
            // Fallback: basic file info only
            result.put("status", "file_exists");
            result.put("note", "Keytab parsing requires sun.security.krb5 classes (JDK internal). "
                    + "File exists and is readable. Use 'klist -kt " + path + "' for detailed contents.");
        } catch (Exception e) {
            result.put("status", "parse_error");
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    // ================================================================
    //  Kerberos login / ticket test
    // ================================================================

    @DebugTool(description = "Test Kerberos login by attempting to obtain a TGT (Ticket Granting Ticket) "
            + "using the specified principal and keytab. "
            + "Reports success/failure, obtained credentials, and detailed error diagnostics. "
            + "Essential for verifying that the service can authenticate to the KDC.")
    public Map<String, Object> testKerberosLogin(
            @ToolParam(description = "Kerberos principal (e.g., 'HTTP/service.example.com@EXAMPLE.COM')") String principal,
            @ToolParam(description = "Keytab file path") String keytabPath,
            @ToolParam(description = "JAAS config entry name (default: 'com.sun.security.jgss.krb5.initiate')") String jaasEntryName
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (principal == null || principal.isBlank()) {
            result.put("status", "missing_principal");
            result.put("error", "Principal is required (e.g., 'HTTP/service.example.com@EXAMPLE.COM')");
            return result;
        }

        result.put("principal", principal);
        result.put("keytab", keytabPath);

        // Set system properties for keytab login
        if (keytabPath != null && !keytabPath.isBlank()) {
            System.setProperty("java.security.krb5.ktab", keytabPath);
        }

        String entryName = jaasEntryName != null && !jaasEntryName.isBlank()
                ? jaasEntryName : "com.sun.security.jgss.krb5.initiate";

        long start = System.currentTimeMillis();

        try {
            LoginContext lc = new LoginContext(entryName, new KerberosCallbackHandler(principal, keytabPath));
            lc.login();

            long duration = System.currentTimeMillis() - start;
            result.put("status", "success");
            result.put("loginDurationMs", duration);

            Subject subject = lc.getSubject();
            result.put("subjectReadOnly", subject.isReadOnly());

            // List principals obtained
            Set<Principal> principals = subject.getPrincipals();
            List<String> principalNames = new ArrayList<>();
            for (Principal p : principals) {
                principalNames.add(p.getName() + " (" + p.getClass().getSimpleName() + ")");
            }
            result.put("obtainedPrincipals", principalNames);

            // Check for TGT in private credentials
            Set<Object> creds = subject.getPrivateCredentials();
            List<String> credInfo = new ArrayList<>();
            for (Object cred : creds) {
                credInfo.add(cred.getClass().getSimpleName());
            }
            result.put("privateCredentials", credInfo);
            result.put("hasTGT", creds.stream().anyMatch(c -> c.getClass().getSimpleName().contains("Krb")));

            // Logout to clean up
            lc.logout();

        } catch (LoginException e) {
            long duration = System.currentTimeMillis() - start;
            result.put("status", "failed");
            result.put("loginDurationMs", duration);
            result.put("error", e.getMessage());

            // Root cause hints
            String msg = e.getMessage();
            if (msg != null) {
                if (msg.contains("KeyTab") || msg.contains("null keytab")) {
                    result.put("hint", "Keytab is null or unreadable. Verify the file path and permissions.");
                } else if (msg.contains("Preauthentication")) {
                    result.put("hint", "Pre-authentication failed. The keytab may not contain the correct "
                            + "key for this principal, or the encryption type is not supported by the KDC.");
                } else if (msg.contains("Client not found")) {
                    result.put("hint", "Principal '" + principal + "' not found in the KDC database. "
                            + "Verify the principal name and realm are correct.");
                } else if (msg.contains("Server not found")) {
                    result.put("hint", "KDC not found or unreachable. Check krb5.conf and DNS configuration.");
                } else if (msg.contains("Clock skew")) {
                    result.put("hint", "Clock skew too great. Ensure the server clock is synchronized "
                            + "with the KDC (Kerberos requires <5 minutes). Use NTP.");
                }
            }
        } catch (Exception e) {
            result.put("status", "failed");
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    // ================================================================
    //  Spring Security Kerberos / SPNEGO bean inspection
    // ================================================================

    @DebugTool(description = "Inspect Spring Security Kerberos/SPNEGO configuration beans: "
            + "KerberosServiceAuthenticationProvider, SpnegoAuthenticationProcessingFilter, "
            + "SunJaasKerberosTicketValidator, and KerberosAuthenticationProvider. "
            + "Useful for diagnosing SPNEGO SSO failures and Kerberos authentication chain issues.")
    public Map<String, Object> getKerberosSecurityConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        // SunJaasKerberosTicketValidator
        try {
            Object validator = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.security.kerberos.authentication.sun.SunJaasKerberosTicketValidator");
            if (validator != null) {
                Map<String, Object> vi = new LinkedHashMap<>();
                vi.put("class", validator.getClass().getSimpleName());

                Object servicePrincipal = ReflectionHelper.invokeMethod(validator, "getServicePrincipal");
                if (servicePrincipal == null) {
                    servicePrincipal = ReflectionHelper.getFieldValue(validator, "servicePrincipal");
                }
                vi.put("servicePrincipal", servicePrincipal);

                Object keyTabLocation = ReflectionHelper.invokeMethod(validator, "getKeyTabLocation");
                if (keyTabLocation == null) {
                    keyTabLocation = ReflectionHelper.getFieldValue(validator, "keyTabLocation");
                }
                vi.put("keyTabLocation", keyTabLocation != null ? keyTabLocation.toString() : null);

                Object holdonToTT = ReflectionHelper.invokeMethod(validator, "isHoldOnToTT");
                if (holdonToTT != null) {
                    vi.put("holdOnToTT", holdonToTT);
                }

                Object debug = ReflectionHelper.invokeMethod(validator, "isDebug");
                if (debug != null) {
                    vi.put("debug", debug);
                }

                result.put("ticketValidator", vi);
            }
        } catch (Exception ignored) {}

        // KerberosServiceAuthenticationProvider
        try {
            Object provider = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.security.kerberos.authentication.KerberosServiceAuthenticationProvider");
            if (provider != null) {
                Map<String, Object> pi = new LinkedHashMap<>();
                pi.put("class", provider.getClass().getSimpleName());

                Object ticketValidator = ReflectionHelper.getFieldValue(provider, "ticketValidator");
                if (ticketValidator != null) {
                    pi.put("ticketValidatorClass", ticketValidator.getClass().getSimpleName());
                }

                Object userDetailsService = ReflectionHelper.getFieldValue(provider, "userDetailsService");
                if (userDetailsService != null) {
                    pi.put("userDetailsService", userDetailsService.getClass().getSimpleName());
                }

                result.put("authenticationProvider", pi);
            }
        } catch (Exception ignored) {}

        // SpnegoAuthenticationProcessingFilter
        try {
            Object filter = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.security.kerberos.web.authentication.SpnegoAuthenticationProcessingFilter");
            if (filter != null) {
                Map<String, Object> fi = new LinkedHashMap<>();
                fi.put("class", filter.getClass().getSimpleName());

                Object skipIfError = ReflectionHelper.getFieldValue(filter, "skipIfError");
                if (skipIfError != null) {
                    fi.put("skipIfError", skipIfError);
                }

                result.put("spnegoFilter", fi);
            }
        } catch (Exception ignored) {}

        // SpnegoEntryPoint
        try {
            Object entryPoint = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.security.kerberos.web.authentication.SpnegoEntryPoint");
            if (entryPoint != null) {
                result.put("spnegoEntryPoint", true);
            }
        } catch (Exception ignored) {}

        // Kerberos properties
        Environment env = ctx.getEnvironment();
        Map<String, String> props = new LinkedHashMap<>();
        String[] propNames = {
                "spring.security.kerberos.service-principal",
                "spring.security.kerberos.keytab-location",
                "spring.security.kerberos.debug"
        };
        for (String p : propNames) {
            String val = env.getProperty(p);
            if (val != null) {
                props.put(p.replace("spring.security.kerberos.", ""), val);
            }
        }
        if (!props.isEmpty()) {
            result.put("springKerberosProperties", props);
        }

        if (result.isEmpty()) {
            result.put("status", "not_configured");
            result.put("hint", "No Spring Security Kerberos beans found. "
                    + "Add spring-security-kerberos-web and configure service principal + keytab.");
        }

        return result;
    }

    // ================================================================
    //  Kerberos system properties & krb5.conf
    // ================================================================

    @DebugTool(description = "Check Kerberos system properties and krb5.conf configuration: "
            + "realm settings, KDC addresses, DNS realm lookup, encryption types, "
            + "ticket lifetime, and forwardable/renewable settings. "
            + "Useful for diagnosing 'unable to obtain TGT' or realm resolution issues.")
    public Map<String, Object> getKerberosEnvironment() {
        Map<String, Object> result = new LinkedHashMap<>();

        // System properties
        result.put("systemProperties", getKerberosSystemProperties());

        // krb5.conf location
        String krb5conf = System.getProperty("java.security.krb5.conf");
        if (krb5conf == null) {
            // OS default
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("linux")) {
                krb5conf = "/etc/krb5.conf";
            } else if (os.contains("mac")) {
                krb5conf = "/etc/krb5.conf";
            } else if (os.contains("windows")) {
                krb5conf = "C:\\Windows\\krb5.ini";
            }
        }

        result.put("krb5confPath", krb5conf);

        if (krb5conf != null) {
            File f = new File(krb5conf);
            result.put("krb5confExists", f.exists());
            if (f.exists()) {
                result.put("krb5confSize", f.length());
                result.put("krb5confLastModified", new Date(f.lastModified()).toString());

                // Parse key sections
                try {
                    Map<String, Object> confSections = parseKrb5Conf(krb5conf);
                    result.put("krb5confContents", confSections);
                } catch (Exception e) {
                    result.put("krb5confParseError", e.getMessage());
                }
            } else {
                result.put("hint", "krb5.conf not found at expected location. Kerberos will not work "
                        + "without proper krb5.conf. Set -Djava.security.krb5.conf=/path/to/krb5.conf");
            }
        }

        // Check ticket cache
        String ticketCache = System.getenv("KRB5CCNAME");
        if (ticketCache != null) {
            result.put("ticketCache", ticketCache);
        }

        // DNS lookups
        result.put("useSubjectCredsOnly",
                System.getProperty("javax.security.auth.useSubjectCredsOnly", "true"));
        result.put("krb5Debug",
                System.getProperty("sun.security.krb5.debug", "false"));

        // Current user (relevant for ticket cache)
        result.put("currentUser", System.getProperty("user.name"));
        result.put("osUserHome", System.getProperty("user.home"));

        return result;
    }

    // ================================================================
    //  Internal helpers
    // ================================================================

    private Map<String, String> getKerberosSystemProperties() {
        Map<String, String> props = new LinkedHashMap<>();
        String[] krbPropNames = {
                "java.security.krb5.conf", "java.security.krb5.realm",
                "java.security.krb5.kdc", "java.security.krb5.ktab",
                "sun.security.krb5.debug", "sun.security.krb5.rcache",
                "sun.security.krb5.msdes.disable",
                "javax.security.auth.useSubjectCredsOnly"
        };
        for (String p : krbPropNames) {
            String val = System.getProperty(p);
            if (val != null) {
                props.put(p, val);
            }
        }
        return props;
    }

    private Map<String, Object> maskSensitiveOptions(Map<String, ?> options) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (options == null) return safe;
        for (Map.Entry<String, ?> e : options.entrySet()) {
            String key = e.getKey();
            if (key.toLowerCase().contains("password") || key.toLowerCase().contains("secret")) {
                safe.put(key, "***masked***");
            } else {
                safe.put(key, String.valueOf(e.getValue()));
            }
        }
        return safe;
    }

    private String findKeytabFromJaasConfig() {
        try {
            Configuration config = Configuration.getConfiguration();
            String[] appNames = {
                    "com.sun.security.jgss.krb5.accept", "com.sun.security.jgss.krb5.initiate",
                    "spnego-server", "Krb5LoginModule"
            };
            for (String appName : appNames) {
                AppConfigurationEntry[] entries = config.getAppConfigurationEntry(appName);
                if (entries != null) {
                    for (AppConfigurationEntry entry : entries) {
                        Object keyTab = entry.getOptions().get("keyTab");
                        if (keyTab != null) {
                            return String.valueOf(keyTab);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String describeEncType(int encType) {
        switch (encType) {
            case 1: return "des-cbc-crc";
            case 3: return "des-cbc-md5";
            case 16: return "des3-cbc-sha1-kd";
            case 17: return "aes128-cts-hmac-sha1-96";
            case 18: return "aes256-cts-hmac-sha1-96";
            case 23: return "rc4-hmac";
            default: return "encType-" + encType;
        }
    }

    private Map<String, Object> parseKrb5Conf(String path) {
        Map<String, Object> sections = new LinkedHashMap<>();
        try (java.util.Scanner sc = new java.util.Scanner(new File(path))) {
            String currentSection = null;
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1);
                    sections.put(currentSection, new ArrayList<String>());
                } else if (currentSection != null && !line.isEmpty() && !line.startsWith("#")) {
                    @SuppressWarnings("unchecked")
                    List<String> list = (List<String>) sections.get(currentSection);
                    list.add(line);
                }
            }
        } catch (Exception ignored) {}
        return sections;
    }

    /**
     * Callback handler for Kerberos login — provides principal and keytab info.
     */
    private static class KerberosCallbackHandler implements CallbackHandler {
        private final String principal;
        private final String keytabPath;

        KerberosCallbackHandler(String principal, String keytabPath) {
            this.principal = principal;
            this.keytabPath = keytabPath;
        }

        @Override
        public void handle(javax.security.auth.callback.Callback[] callbacks) {
            for (javax.security.auth.callback.Callback cb : callbacks) {
                if (cb instanceof javax.security.auth.callback.NameCallback) {
                    ((javax.security.auth.callback.NameCallback) cb).setName(principal);
                }
                // PasswordCallback would be needed for password-based auth
            }
        }
    }
}
