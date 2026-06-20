package com.demo.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.PrintWriter;

/**
 * Kerberos demo configuration.
 *
 * Sets up JAAS config and system properties for Kerberos/SPNEGO.
 * In production, you'd have a real KDC (Active Directory / MIT Kerberos).
 *
 * For demo purposes, we create a JAAS config with sample entries so the
 * KerberosInspector can inspect them. No real KDC is needed for inspection.
 */
@Configuration
public class KerberosDemoConfig {

    @PostConstruct
    public void setupKerberosProperties() {
        // Set Kerberos system properties (demo values)
        System.setProperty("java.security.krb5.conf",
                System.getProperty("user.dir") + "/demo/src/main/resources/krb5.conf");
        System.setProperty("sun.security.krb5.debug", "false");
        System.setProperty("javax.security.auth.useSubjectCredsOnly", "false");

        // Create a JAAS config file with demo entries
        String jaasConfigPath = System.getProperty("java.io.tmpdir") + "/demo-jaas.conf";
        try (PrintWriter pw = new PrintWriter(new File(jaasConfigPath))) {
            pw.println("spnego-server {");
            pw.println("    com.sun.security.auth.module.Krb5LoginModule required");
            pw.println("    keyTab=\"/etc/krb5.keytab\"");
            pw.println("    principal=\"HTTP/service.example.com@EXAMPLE.COM\"");
            pw.println("    useKeyTab=true");
            pw.println("    storeKey=true");
            pw.println("    debug=false;");
            pw.println("};");
            pw.println();
            pw.println("com.sun.security.jgss.krb5.initiate {");
            pw.println("    com.sun.security.auth.module.Krb5LoginModule required");
            pw.println("    useKeyTab=false");
            pw.println("    useTicketCache=true");
            pw.println("    debug=false;");
            pw.println("};");
            pw.println();
            pw.println("com.sun.security.jgss.krb5.accept {");
            pw.println("    com.sun.security.auth.module.Krb5LoginModule required");
            pw.println("    keyTab=\"/etc/krb5.keytab\"");
            pw.println("    principal=\"HTTP/service.example.com@EXAMPLE.COM\"");
            pw.println("    useKeyTab=true");
            pw.println("    storeKey=true");
            pw.println("    debug=false;");
            pw.println("};");
        } catch (Exception e) {
            // Non-fatal — inspector will still work with whatever config is available
        }

        System.setProperty("java.security.auth.login.config", jaasConfigPath);
    }
}
