package com.demo.mongo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class MongoDataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(MongoDataSeeder.class);
    private final AuditLogRepository repo;

    public MongoDataSeeder(AuditLogRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(String... args) {
        if (repo.count() == 0) {
            repo.save(new AuditLog("LOGIN", "admin", "User logged in"));
            repo.save(new AuditLog("CREATE_ORDER", "user1", "Created order #1001"));
            repo.save(new AuditLog("DELETE_ORDER", "admin", "Deleted order #500"));
            repo.save(new AuditLog("LOGIN", "user2", "User logged in"));
            repo.save(new AuditLog("UPDATE_PROFILE", "user1", "Updated email"));
            log.info("Seeded 5 audit log entries into MongoDB");
        }
    }
}
