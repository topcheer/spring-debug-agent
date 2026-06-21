package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Object storage (S3/MinIO) diagnostic tools.
 * Inspects buckets and object metadata via reflection on the AWS S3 SDK
 * or the MinIO client when either is present on the classpath.
 */
public class ObjectStorageInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List all object storage buckets via AWS S3 SDK or MinIO client. Shows bucket name, creation date, region, and object count. Useful for verifying storage connectivity or taking inventory of buckets.")
    public List<Map<String, Object>> getS3Buckets() {
        List<Map<String, Object>> buckets = new ArrayList<>();

        Object s3Client = findS3Client();
        if (s3Client != null) {
            try {
                Method listBuckets = s3Client.getClass().getMethod("listBuckets");
                Object response = listBuckets.invoke(s3Client);
                if (response != null) {
                    Object bucketList = ReflectionHelper.invokeMethod(response, "buckets");
                    if (bucketList instanceof List<?> list) {
                        for (Object bucket : list) {
                            Map<String, Object> b = new LinkedHashMap<>();
                            b.put("provider", "AWS S3");
                            b.put("name", ReflectionHelper.invokeMethod(bucket, "name"));
                            Object creationDate = ReflectionHelper.invokeMethod(bucket, "creationDate");
                            b.put("creationDate", creationDate != null ? creationDate.toString() : null);

                            // Region
                            try {
                                Object regionVal = ReflectionHelper.invokeMethod(s3Client, "getRegion");
                                if (regionVal != null) b.put("region", regionVal.toString());
                            } catch (Exception ignored) {}

                            // Object count (best-effort)
                            Object count = getObjectCount(s3Client, String.valueOf(b.get("name")));
                            if (count != null) b.put("objectCount", count);

                            buckets.add(b);
                        }
                        if (buckets.isEmpty()) {
                            buckets.add(Map.of("provider", "AWS S3", "note", "No buckets found."));
                        }
                        return buckets;
                    }
                }
            } catch (Exception e) {
                buckets.add(Map.of("error", "AWS S3: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
                return buckets;
            }
        }

        Object minioClient = findMinioClient();
        if (minioClient != null) {
            try {
                Method listBuckets = minioClient.getClass().getMethod("listBuckets");
                Object bucketList = listBuckets.invoke(minioClient);
                if (bucketList instanceof List<?> list) {
                    for (Object bucket : list) {
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("provider", "MinIO");
                        b.put("name", ReflectionHelper.invokeMethod(bucket, "name"));
                        Object creationDate = ReflectionHelper.invokeMethod(bucket, "creationDate");
                        b.put("creationDate", creationDate != null ? creationDate.toString() : null);
                        buckets.add(b);
                    }
                    if (buckets.isEmpty()) {
                        buckets.add(Map.of("provider", "MinIO", "note", "No buckets found."));
                    }
                    return buckets;
                }
            } catch (Exception e) {
                buckets.add(Map.of("error", "MinIO: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
                return buckets;
            }
        }

        buckets.add(Map.of("error", "No S3/MinIO client found. Configure AWS S3 SDK or MinIO client."));
        return buckets;
    }

    @DebugTool(description = "Get metadata for a specific S3/MinIO object: size, content type, last modified, ETag, and storage class. Useful for verifying object existence or inspecting large objects.")
    public Map<String, Object> getS3ObjectInfo(
            @ToolParam(description = "Bucket name", required = true) String bucket,
            @ToolParam(description = "Object key", required = true) String key
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        Object s3Client = findS3Client();
        if (s3Client != null) {
            result.put("provider", "AWS S3");
            result.put("bucket", bucket);
            result.put("key", key);
            try {
                Class<?> reqClass = Class.forName("software.amazon.awssdk.services.s3.model.HeadObjectRequest", false, ctx.getClassLoader());
                Object req = buildRequest(reqClass, bucket, key, "bucket", "key");
                if (req == null) {
                    result.put("error", "Failed to build HeadObjectRequest");
                    return result;
                }
                Method headObject = s3Client.getClass().getMethod("headObject", reqClass);
                Object response = headObject.invoke(s3Client, req);
                if (response != null) {
                    result.put("size", ReflectionHelper.invokeMethod(response, "contentLength"));
                    result.put("contentType", ReflectionHelper.invokeMethod(response, "contentType"));
                    Object lastMod = ReflectionHelper.invokeMethod(response, "lastModified");
                    result.put("lastModified", lastMod != null ? lastMod.toString() : null);
                    result.put("eTag", ReflectionHelper.invokeMethod(response, "eTag"));
                    Object storageClass = ReflectionHelper.invokeMethod(response, "storageClass");
                    result.put("storageClass", storageClass != null ? storageClass.toString() : null);
                    Object metadata = ReflectionHelper.invokeMethod(response, "metadata");
                    if (metadata instanceof Map<?, ?> m && !m.isEmpty()) {
                        result.put("userMetadata", m);
                    }
                }
            } catch (Exception e) {
                result.put("error", "AWS S3: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return result;
        }

        Object minioClient = findMinioClient();
        if (minioClient != null) {
            result.put("provider", "MinIO");
            result.put("bucket", bucket);
            result.put("key", key);
            try {
                Class<?> argsClass = Class.forName("io.minio.StatObjectArgs", false, ctx.getClassLoader());
                Object args = buildRequest(argsClass, bucket, key, "bucket", "object");
                if (args == null) {
                    result.put("error", "Failed to build StatObjectArgs");
                    return result;
                }
                Method statObject = minioClient.getClass().getMethod("statObject", argsClass);
                Object response = statObject.invoke(minioClient, args);
                if (response != null) {
                    result.put("size", ReflectionHelper.invokeMethod(response, "size"));
                    result.put("contentType", ReflectionHelper.invokeMethod(response, "contentType"));
                    Object lastMod = ReflectionHelper.invokeMethod(response, "lastModified");
                    result.put("lastModified", lastMod != null ? lastMod.toString() : null);
                    result.put("eTag", ReflectionHelper.invokeMethod(response, "etag"));
                }
            } catch (Exception e) {
                result.put("error", "MinIO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return result;
        }

        result.put("error", "No S3/MinIO client found. Configure AWS S3 SDK or MinIO client.");
        return result;
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private Object findS3Client() {
        try {
            Class<?> s3ClientClass = Class.forName("software.amazon.awssdk.services.s3.S3Client", false, ctx.getClassLoader());
            String[] names = ctx.getBeanNamesForType(s3ClientClass);
            return names.length > 0 ? ctx.getBean(names[0]) : null;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private Object findMinioClient() {
        try {
            Class<?> minioClientClass = Class.forName("io.minio.MinioClient", false, ctx.getClassLoader());
            String[] names = ctx.getBeanNamesForType(minioClientClass);
            return names.length > 0 ? ctx.getBean(names[0]) : null;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Best-effort: count objects in a bucket via AWS SDK listObjectsV2.
     */
    private Object getObjectCount(Object s3Client, String bucketName) {
        try {
            Class<?> reqClass = Class.forName("software.amazon.awssdk.services.s3.model.ListObjectsV2Request", false, ctx.getClassLoader());
            Object req = buildRequest(reqClass, bucketName, null, "bucket", null);
            if (req == null) return null;
            Method listObjectsV2 = s3Client.getClass().getMethod("listObjectsV2", reqClass);
            Object response = listObjectsV2.invoke(s3Client, req);
            return ReflectionHelper.invokeMethod(response, "keyCount");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build a request/args object via its builder(), set bucket and key/object fields, then build().
     * field2 may be null to skip setting the second field (used for list requests).
     */
    private Object buildRequest(Class<?> reqClass, String bucket, String key, String field1, String field2) {
        try {
            Method builderMethod = reqClass.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            Class<?> builderClass = builder.getClass();
            builderClass.getMethod(field1, String.class).invoke(builder, bucket);
            if (field2 != null && key != null) {
                builderClass.getMethod(field2, String.class).invoke(builder, key);
            }
            return builderClass.getMethod("build").invoke(builder);
        } catch (Exception e) {
            return null;
        }
    }
}
