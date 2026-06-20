package com.demo.config;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Embedded LDAP server (UnboundID in-memory) + Spring LDAP ContextSource.
 * Seeds test users and groups for demo and testing.
 */
@Configuration
public class LdapDemoConfig {

    private static final String BASE_DN = "dc=example,dc=com";
    private static final int LDAP_PORT = 8389;
    private static final String ADMIN_DN = "cn=admin,dc=example,dc=com";
    private static final String ADMIN_PASSWORD = "adminpassword";

    private InMemoryDirectoryServer directoryServer;

    @PostConstruct
    public void startEmbeddedLdap() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", LDAP_PORT));
        config.addAdditionalBindCredentials(ADMIN_DN, ADMIN_PASSWORD);

        directoryServer = new InMemoryDirectoryServer(config);

        // Seed base entries
        Entry baseEntry = new Entry(BASE_DN);
        baseEntry.addAttribute("objectClass", "top", "domain");
        baseEntry.addAttribute("dc", "example");
        directoryServer.add(baseEntry);

        Entry usersOu = new Entry("ou=users," + BASE_DN);
        usersOu.addAttribute("objectClass", "top", "organizationalUnit");
        usersOu.addAttribute("ou", "users");
        directoryServer.add(usersOu);

        Entry groupsOu = new Entry("ou=groups," + BASE_DN);
        groupsOu.addAttribute("objectClass", "top", "organizationalUnit");
        groupsOu.addAttribute("ou", "groups");
        directoryServer.add(groupsOu);

        // Seed demo users
        addUser("john", "John Doe", "Engineering", "password123");
        addUser("jane", "Jane Smith", "Engineering", "password456");
        addUser("admin", "Administrator", "IT", "adminpass");

        // Seed groups
        addGroup("developers", new String[]{"john", "jane"});
        addGroup("admins", new String[]{"admin"});
        addGroup("engineers", new String[]{"john", "jane"});

        directoryServer.startListening();
        System.out.println("[LDAP] Embedded LDAP server started on port " + LDAP_PORT);
    }

    @Bean
    public ContextSource ldapContextSource() {
        LdapContextSource source = new LdapContextSource();
        source.setUrl("ldap://localhost:" + LDAP_PORT);
        source.setBase(BASE_DN);
        source.setUserDn(ADMIN_DN);
        source.setPassword(ADMIN_PASSWORD);
        source.setPooled(true);
        source.afterPropertiesSet();
        return source;
    }

    @Bean
    public LdapTemplate ldapTemplate(ContextSource contextSource) {
        return new LdapTemplate(contextSource);
    }

    @PreDestroy
    public void stopEmbeddedLdap() {
        if (directoryServer != null) {
            directoryServer.shutDown(true);
            System.out.println("[LDAP] Embedded LDAP server stopped");
        }
    }

    private void addUser(String uid, String cn, String department, String password) throws Exception {
        String dn = "uid=" + uid + ",ou=users," + BASE_DN;
        Entry entry = new Entry(dn);
        entry.addAttribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson");
        entry.addAttribute("uid", uid);
        entry.addAttribute("cn", cn);
        entry.addAttribute("sn", cn.contains(" ") ? cn.split(" ")[1] : cn);
        entry.addAttribute("ou", department);
        entry.addAttribute("mail", uid + "@example.com");
        entry.addAttribute("userPassword", password);
        directoryServer.add(entry);
    }

    private void addGroup(String name, String[] members) throws Exception {
        String dn = "cn=" + name + ",ou=groups," + BASE_DN;
        Entry entry = new Entry(dn);
        entry.addAttribute("objectClass", "top", "groupOfNames");
        entry.addAttribute("cn", name);
        for (String member : members) {
            entry.addAttribute("member", "uid=" + member + ",ou=users," + BASE_DN);
        }
        directoryServer.add(entry);
    }
}
