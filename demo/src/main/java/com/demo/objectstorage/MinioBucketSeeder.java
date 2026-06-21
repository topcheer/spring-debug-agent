package com.demo.objectstorage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Creates demo buckets in MinIO at startup so ObjectStorageInspector can list them.
 * Uses MinIO's S3-compatible API directly.
 */
@Component
public class MinioBucketSeeder {

    private static final Logger log = LoggerFactory.getLogger(MinioBucketSeeder.class);

    @Value("${minio.endpoint:http://localhost:9000}")
    private String endpoint;

    @Value("${minio.access-key:minioadmin}")
    private String accessKey;

    @Value("${minio.secret-key:minioadmin}")
    private String secretKey;

    private static final String[] DEMO_BUCKETS = {
        "demo-uploads",
        "demo-backup",
        "demo-logs"
    };

    @PostConstruct
    public void createBuckets() {
        try {
            Thread.sleep(2000); // Wait for MinIO to be ready

            // Use MinIO's admin API to create buckets via HTTP PUT
            HttpClient client = HttpClient.newHttpClient();

            for (String bucket : DEMO_BUCKETS) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(endpoint + "/" + bucket))
                            .PUT(HttpRequest.BodyPublishers.noBody())
                            .build();

                    HttpResponse<String> response = client.send(request,
                            HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200 || response.statusCode() == 204) {
                        log.info("MinIO bucket created: {}", bucket);
                    } else if (response.statusCode() == 409) {
                        log.debug("MinIO bucket already exists: {}", bucket);
                    } else {
                        log.debug("MinIO bucket {} creation returned: {}", bucket, response.statusCode());
                    }
                } catch (Exception e) {
                    log.debug("Could not create bucket {}: {}", bucket, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.info("MinIO bucket seeding skipped: {}", e.getMessage());
        }
    }
}
