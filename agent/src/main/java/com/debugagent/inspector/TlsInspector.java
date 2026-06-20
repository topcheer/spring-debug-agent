package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;

import javax.net.ssl.*;
import java.io.*;
import java.lang.reflect.Method;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.Date;

/**
 * TLS / mTLS diagnostic tools.
 * Inspects keystore/truststore, certificate expiry, TLS handshake, and cipher suites.
 * No external dependencies — uses pure JDK SSL API + Spring Boot server.ssl.* config.
 */
public class TlsInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    // ================================================================
    //  Keystore / Truststore inspection
    // ================================================================

    @DebugTool(description = "Inspect TLS keystore and truststore configuration: file paths, types, providers, "
            + "and all loaded certificates with their alias, issuer, subject, serial number, and expiry date. "
            + "Reads from Spring Boot server.ssl.* properties. Useful for diagnosing certificate expiry "
            + "or misconfigured keystores in mTLS scenarios.")
    public Map<String, Object> getTlsKeystoreInfo(
            @ToolParam(description = "Keystore password (if not configured in properties). Optional — "
                    + "if omitted, uses server.ssl.key-store-password from config") String password
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        Environment env = ctx.getEnvironment();

        String ksPath = env.getProperty("server.ssl.key-store");
        String ksType = env.getProperty("server.ssl.key-store-type", "JKS");
        String ksProvider = env.getProperty("server.ssl.key-store-provider");
        String ksPassword = password != null && !password.isBlank() ? password
                : env.getProperty("server.ssl.key-store-password");

        String tsPath = env.getProperty("server.ssl.trust-store");
        String tsType = env.getProperty("server.ssl.trust-store-type", "JKS");
        String tsPassword = password != null && !password.isBlank() ? password
                : env.getProperty("server.ssl.trust-store-password");

        // Keystore
        if (ksPath != null) {
            Map<String, Object> ksInfo = inspectKeystore(ksPath, ksType, ksProvider, ksPassword);
            ksInfo.put("configuredPath", ksPath);
            ksInfo.put("type", ksType);
            result.put("keystore", ksInfo);
        } else {
            result.put("keystore", Map.of("status", "not_configured",
                    "hint", "No server.ssl.key-store property set. TLS is not enabled on this server."));
        }

        // Truststore
        if (tsPath != null) {
            Map<String, Object> tsInfo = inspectKeystore(tsPath, tsType, null, tsPassword);
            tsInfo.put("configuredPath", tsPath);
            tsInfo.put("type", tsType);
            result.put("truststore", tsInfo);
        } else {
            // Check default JDK truststore (cacerts)
            String cacertsPath = System.getProperty("java.home") + "/lib/security/cacerts";
            File cacerts = new File(cacertsPath);
            if (cacerts.exists()) {
                result.put("truststore", Map.of(
                        "status", "using_default_cacerts",
                        "path", cacertsPath,
                        "hint", "No custom truststore configured. JVM default cacerts is used."
                ));
            } else {
                result.put("truststore", Map.of("status", "not_configured"));
            }
        }

        // SSL bundle info (Spring Boot 3.1+)
        String sslBundleName = env.getProperty("server.ssl.bundle");
        if (sslBundleName != null) {
            result.put("sslBundle", Map.of("name", sslBundleName,
                    "hint", "Using Spring Boot SSL bundle configuration."));
        }

        // Client auth (mTLS)
        String clientAuth = env.getProperty("server.ssl.client-auth", "none");
        result.put("clientAuth", clientAuth);
        result.put("mtlsEnabled", !"none".equalsIgnoreCase(clientAuth));

        return result;
    }

    // ================================================================
    //  Certificate expiry check
    // ================================================================

    @DebugTool(description = "Check all X.509 certificates in the keystore and truststore for expiration. "
            + "Reports subject, issuer, expiry date, days remaining, and warns if any cert expires within "
            + "30 days. Critical for preventing production outages from expired certs.")
    public List<Map<String, Object>> checkCertificateExpiry(
            @ToolParam(description = "Keystore password (optional, uses config default if omitted)") String password
    ) {
        List<Map<String, Object>> certs = new ArrayList<>();
        Environment env = ctx.getEnvironment();
        String ksPassword = password != null && !password.isBlank() ? password
                : env.getProperty("server.ssl.key-store-password");
        String tsPassword = password != null && !password.isBlank() ? password
                : env.getProperty("server.ssl.trust-store-password");

        // Check keystore
        String ksPath = env.getProperty("server.ssl.key-store");
        if (ksPath != null) {
            certs.addAll(checkExpiry(ksPath,
                    env.getProperty("server.ssl.key-store-type", "JKS"), ksPassword, "keystore"));
        }

        // Check truststore
        String tsPath = env.getProperty("server.ssl.trust-store");
        if (tsPath != null) {
            certs.addAll(checkExpiry(tsPath,
                    env.getProperty("server.ssl.trust-store-type", "JKS"), tsPassword, "truststore"));
        }

        // Check default cacerts if no custom truststore
        if (tsPath == null) {
            String cacertsPath = System.getProperty("java.home") + "/lib/security/cacerts";
            File cacerts = new File(cacertsPath);
            if (cacerts.exists()) {
                certs.addAll(checkExpiry(cacertsPath, "JKS", "changeit", "cacerts(default)"));
            }
        }

        return certs;
    }

    // ================================================================
    //  SSL handshake test
    // ================================================================

    @DebugTool(description = "Test TLS handshake against a remote HTTPS endpoint. Reports negotiated protocol, "
            + "cipher suite, peer principal, certificate chain, and handshake duration. "
            + "Useful for diagnosing TLS version mismatches, certificate validation failures, "
            + "or mTLS client auth issues.")
    public Map<String, Object> testTlsHandshake(
            @ToolParam(description = "Target host:port (e.g., 'api.internal.com:443')") String hostPort
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (hostPort == null || hostPort.isBlank()) {
            result.put("error", "hostPort is required (e.g., 'api.example.com:443')");
            return result;
        }

        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 443;

        try {
            long start = System.currentTimeMillis();

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509CapturingTrustManager()}, null);
            SSLSocketFactory factory = sslContext.getSocketFactory();
            SSLSocket socket = (SSLSocket) factory.createSocket(host, port);
            socket.setSoTimeout(10000);

            // Start handshake
            socket.startHandshake();

            long duration = System.currentTimeMillis() - start;

            SSLSession session = socket.getSession();
            result.put("status", "success");
            result.put("host", host);
            result.put("port", port);
            result.put("protocol", session.getProtocol());
            result.put("cipherSuite", session.getCipherSuite());
            result.put("handshakeDurationMs", duration);
            result.put("sessionValid", session.isValid());

            // Peer certificates
            Certificate[] peerCerts = session.getPeerCertificates();
            if (peerCerts != null && peerCerts.length > 0) {
                List<Map<String, Object>> chain = new ArrayList<>();
                for (int i = 0; i < peerCerts.length; i++) {
                    chain.add(describeCert(peerCerts[i], i == 0 ? "server" : "CA(" + i + ")"));
                }
                result.put("certificateChain", chain);
            }

            // Peer principal
            try {
                result.put("peerPrincipal", session.getPeerPrincipal().toString());
            } catch (Exception ignored) {}

            socket.close();

        } catch (Exception e) {
            result.put("status", "failed");
            result.put("host", host);
            result.put("port", port);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());

            // Common error hints
            String msg = e.getMessage();
            if (msg != null) {
                if (msg.contains("PKIX path building failed")) {
                    result.put("hint", "Trust chain validation failed. The server's certificate is not trusted "
                            + "by this JVM. Check truststore configuration or import the CA certificate.");
                } else if (msg.contains("handshake_failure")) {
                    result.put("hint", "TLS handshake failed. Possible causes: no common cipher suites, "
                            + "server requires client certificate (mTLS), or protocol version mismatch.");
                } else if (msg.contains("Connection refused")) {
                    result.put("hint", "Connection refused. The target host/port is not reachable.");
                } else if (msg.contains("Unsupported or unrecognized SSL message")) {
                    result.put("hint", "The target endpoint may not be HTTPS. Verify the port and protocol.");
                }
            }
        }

        return result;
    }

    // ================================================================
    //  TLS protocols and cipher suites
    // ================================================================

    @DebugTool(description = "List all supported and enabled TLS protocols and cipher suites on this JVM. "
            + "Shows both default and supported lists from the JDK SSL engine. "
            + "Useful for diagnosing TLS 1.0/1.1 deprecation issues or weak cipher vulnerabilities.")
    public Map<String, Object> getTlsProtocolsAndCiphers() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            SSLContext context = SSLContext.getDefault();
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();

            result.put("defaultProtocols", Arrays.asList(socket.getEnabledProtocols()));
            result.put("supportedProtocols", Arrays.asList(socket.getSupportedProtocols()));
            result.put("defaultCipherSuites", Arrays.asList(socket.getEnabledCipherSuites()));
            result.put("supportedCipherSuites", Arrays.asList(socket.getSupportedCipherSuites()));

            // Spring Boot TLS config
            Environment env = ctx.getEnvironment();
            String[] tlsProps = {
                    "server.ssl.enabled-protocols", "server.ssl.ciphers",
                    "server.ssl.protocol", "server.ssl.enabled"
            };
            Map<String, String> springTls = new LinkedHashMap<>();
            for (String prop : tlsProps) {
                String val = env.getProperty(prop);
                if (val != null) {
                    springTls.put(prop, val);
                }
            }
            if (!springTls.isEmpty()) {
                result.put("springBootTlsConfig", springTls);
            }

            socket.close();
        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    // ================================================================
    //  SSLContext / TrustManager inspection
    // ================================================================

    @DebugTool(description = "Inspect the JVM's default SSLContext and TrustManager chain. "
            + "Shows the default SSLContext provider, algorithm, trust managers, and whether "
            + "custom X509TrustManager implementations are registered. "
            + "Useful for detecting custom trust managers that might bypass certificate validation.")
    public Map<String, Object> getSslContextInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Default SSLContext
            SSLContext defaultCtx = SSLContext.getDefault();
            result.put("defaultProtocol", defaultCtx.getProtocol());
            result.put("provider", defaultCtx.getProvider().getName());

            // Check javax.net.ssl properties
            Properties props = System.getProperties();
            Map<String, String> sslProps = new LinkedHashMap<>();
            String[] propNames = {
                    "javax.net.ssl.keyStore", "javax.net.ssl.keyStorePassword",
                    "javax.net.ssl.keyStoreType", "javax.net.ssl.trustStore",
                    "javax.net.ssl.trustStorePassword", "javax.net.ssl.trustStoreType"
            };
            for (String p : propNames) {
                String val = props.getProperty(p);
                if (val != null) {
                    // Mask passwords
                    if (p.toLowerCase().contains("password")) {
                        sslProps.put(p, "***masked***");
                    } else {
                        sslProps.put(p, val);
                    }
                }
            }
            result.put("jvmSslSystemProperties", sslProps);

            // Check for hostname verifier override
            try {
                HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
                result.put("defaultHostnameVerifier", verifier.getClass().getName());
                if (!verifier.getClass().getName().contains("sun")) {
                    result.put("hostnameVerifierWarning",
                            "Custom HostnameVerifier detected. Verify it doesn't accept all hostnames "
                                    + "(a common security vulnerability).");
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    // ================================================================
    //  Internal helpers
    // ================================================================

    private Map<String, Object> inspectKeystore(String path, String type, String provider, String password) {
        Map<String, Object> info = new LinkedHashMap<>();
        File file = new File(path.replace("classpath:", ""));

        if (!file.exists()) {
            info.put("status", "file_not_found");
            info.put("path", path);
            return info;
        }

        info.put("fileSize", file.length());

        if (password == null || password.isBlank()) {
            info.put("status", "no_password");
            info.put("hint", "Provide password to inspect certificate contents.");
            return info;
        }

        try (InputStream is = new FileInputStream(file)) {
            KeyStore ks = KeyStore.getInstance(type);
            ks.load(is, password.toCharArray());

            info.put("status", "loaded");
            info.put("entryCount", ks.size());

            List<Map<String, Object>> entries = new ArrayList<>();
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("alias", alias);

                if (ks.isCertificateEntry(alias)) {
                    Certificate cert = ks.getCertificate(alias);
                    entry.putAll(describeCert(cert, "trustedCA"));
                } else if (ks.isKeyEntry(alias)) {
                    entry.put("type", "privateKey");
                    Certificate[] chain = ks.getCertificateChain(alias);
                    if (chain != null && chain.length > 0) {
                        entry.putAll(describeCert(chain[0], "privateKey"));
                        entry.put("chainLength", chain.length);
                    }
                }
                entries.add(entry);
            }
            info.put("entries", entries);

        } catch (Exception e) {
            info.put("status", "error");
            info.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return info;
    }

    private List<Map<String, Object>> checkExpiry(String path, String type, String password, String source) {
        List<Map<String, Object>> certs = new ArrayList<>();
        if (password == null || password.isBlank()) return certs;

        File file = new File(path.replace("classpath:", ""));
        if (!file.exists()) return certs;

        try (InputStream is = new FileInputStream(file)) {
            KeyStore ks = KeyStore.getInstance(type);
            ks.load(is, password.toCharArray());

            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate cert = ks.getCertificate(alias);
                if (cert instanceof X509Certificate) {
                    X509Certificate x509 = (X509Certificate) cert;
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("alias", alias);
                    info.put("source", source);
                    info.put("subject", x509.getSubjectX500Principal().getName());
                    info.put("issuer", x509.getIssuerX500Principal().getName());
                    info.put("expiry", x509.getNotAfter().toString());
                    info.put("serialNumber", x509.getSerialNumber().toString(16));

                    long daysRemaining = (x509.getNotAfter().getTime() - System.currentTimeMillis())
                            / (1000 * 60 * 60 * 24);
                    info.put("daysRemaining", daysRemaining);

                    if (daysRemaining < 0) {
                        info.put("status", "EXPIRED");
                    } else if (daysRemaining < 30) {
                        info.put("status", "EXPIRING_SOON");
                    } else {
                        info.put("status", "valid");
                    }
                    certs.add(info);
                }
            }
        } catch (Exception ignored) {}

        return certs;
    }

    private Map<String, Object> describeCert(Certificate cert, String role) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("type", cert.getType());

        if (cert instanceof X509Certificate) {
            X509Certificate x509 = (X509Certificate) cert;
            info.put("role", role);
            info.put("subject", x509.getSubjectX500Principal().getName());
            info.put("issuer", x509.getIssuerX500Principal().getName());
            info.put("serialNumber", x509.getSerialNumber().toString(16));
            info.put("validFrom", x509.getNotBefore().toString());
            info.put("validTo", x509.getNotAfter().toString());
            info.put("sigAlgName", x509.getSigAlgName());

            long daysRemaining = (x509.getNotAfter().getTime() - System.currentTimeMillis())
                    / (1000 * 60 * 60 * 24);
            info.put("daysRemaining", daysRemaining);
        }
        return info;
    }

    /**
     * TrustManager that captures but does not validate certs (for inspection purposes).
     */
    private static class X509CapturingTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
