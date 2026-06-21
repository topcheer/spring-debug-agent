package com.demo.kerberos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Placeholder for Kerberos/SPNEGO config.
 * Actual Kerberos beans require a real KDC + keytab file.
 * The inspector can still read JAAS config and system properties.
 */
@Configuration
public class KerberosSecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(KerberosSecurityConfig.class);
}
