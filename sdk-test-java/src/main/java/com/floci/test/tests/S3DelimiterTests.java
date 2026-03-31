package com.floci.test.tests;

import com.floci.test.FlociTestGroup;
import com.floci.test.TestContext;
import com.floci.test.TestGroup;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tests S3 ListObjectsV2 delimiter and CommonPrefixes behavior.
 *
 * <p>When a delimiter is specified, S3 groups keys that share a common prefix
 * up to the delimiter into {@code CommonPrefixes} entries instead of returning
 * them as individual objects. This simulates a directory-like listing.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-prefixes.html">Using prefixes</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html">ListObjectsV2</a>
 */
@FlociTestGroup
public class S3DelimiterTests implements TestGroup {

    @Override
    public String name() { return "s3-delimiter"; }

    @Override
    public void run(TestContext ctx) {
        System.out.println("--- S3 Delimiter Tests ---");

        try (S3Client s3 = S3Client
                .builder()
                .endpointOverride(ctx.endpoint)
                .region(ctx.region)
                .credentialsProvider(ctx.credentials)
                .forcePathStyle(true)
                .build()) {

            String bucket = "sdk-test-delimiter";

            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (Exception e) {
                ctx.check("S3 Delimiter CreateBucket", false, e);
                return;
            }

            String[] keys = {
                    "root.txt",
                    "photos/cat.jpg",
                    "photos/dog.jpg",
                    "photos/vacation/beach.png",
                    "photos/vacation/mountain.png",
                    "docs/readme.md",
                    "docs/guide/intro.md",
                    "logs/2024/jan.log",
            };
            for (String key : keys) {
                s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),RequestBody.fromString("content-" + key));
            }

            // 1. Delimiter at root — direct objects + top-level prefixes
            try {
                ListObjectsV2Response response = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).delimiter("/").build());

                Set<String> objectKeys = response.contents().stream().map(S3Object::key).collect(Collectors.toSet());
                Set<String> prefixes = response.commonPrefixes().stream().map(CommonPrefix::prefix).collect(Collectors.toSet());

                List<String> prefixList = response.commonPrefixes().stream().map(CommonPrefix::prefix).collect(Collectors.toList());

                ctx.check("S3 Delimiter root direct objects", objectKeys.equals(Set.of("root.txt")));
                ctx.check("S3 Delimiter root common prefixes", prefixes.equals(Set.of("photos/", "docs/", "logs/")));
                ctx.check("S3 Delimiter root prefixes sorted", prefixList.equals(List.of("docs/", "logs/", "photos/")));
            } catch (Exception e) {
                ctx.check("S3 Delimiter root", false, e);
            }

            // 2. Delimiter + prefix — one level inside "photos/"
            try {
                ListObjectsV2Response response = s3.listObjectsV2(
                        ListObjectsV2Request
                                .builder()
                                .bucket(bucket)
                                .delimiter("/")
                                .prefix("photos/")
                                .build());

                Set<String> objectKeys = response.contents().stream().map(S3Object::key).collect(Collectors.toSet());
                Set<String> prefixes = response.commonPrefixes().stream().map(CommonPrefix::prefix).collect(Collectors.toSet());

                ctx.check("S3 Delimiter prefix photos/ direct objects", objectKeys.equals(Set.of("photos/cat.jpg", "photos/dog.jpg")));
                ctx.check("S3 Delimiter prefix photos/ common prefixes", prefixes.equals(Set.of("photos/vacation/")));
            } catch (Exception e) {
                ctx.check("S3 Delimiter prefix photos/", false, e);
            }

            // 3. Delimiter + prefix — leaf directory with no sub-prefixes
            try {
                ListObjectsV2Response response = s3
                        .listObjectsV2(
                                ListObjectsV2Request
                                        .builder()
                                        .bucket(bucket)
                                        .delimiter("/")
                                        .prefix("photos/vacation/")
                                        .build()
                        );

                Set<String> objectKeys = response.contents().stream().map(S3Object::key).collect(Collectors.toSet());

                ctx.check("S3 Delimiter leaf prefix direct objects", objectKeys.equals(Set.of("photos/vacation/beach.png", "photos/vacation/mountain.png")));
                ctx.check("S3 Delimiter leaf prefix no common prefixes", response.commonPrefixes().isEmpty());
            } catch (Exception e) {
                ctx.check("S3 Delimiter leaf prefix", false, e);
            }

            // 4. No delimiter — all objects returned flat
            try {
                ListObjectsV2Response response = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build());

                ctx.check("S3 Delimiter no delimiter all objects returned", response.contents().size() == keys.length);
                ctx.check("S3 Delimiter no delimiter no common prefixes", response.commonPrefixes().isEmpty());
            } catch (Exception e) {
                ctx.check("S3 Delimiter no delimiter", false, e);
            }

            // 5. KeyCount includes both objects and common prefixes
            try {
                ListObjectsV2Response response = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).delimiter("/").build());

                int expectedCount = response.contents().size() + response.commonPrefixes().size();
                ctx.check("S3 Delimiter KeyCount = objects + prefixes",response.keyCount() == expectedCount);
            } catch (Exception e) {
                ctx.check("S3 Delimiter KeyCount", false, e);
            }

            // 6. MaxKeys limits combined objects + prefixes
            try {
                ListObjectsV2Response response = s3.listObjectsV2(
                        ListObjectsV2Request
                                .builder()
                                .bucket(bucket)
                                .delimiter("/")
                                .maxKeys(2)
                                .build());

                int totalReturned = response.contents().size() + response.commonPrefixes().size();
                ctx.check("S3 Delimiter maxKeys total <= maxKeys", totalReturned <= 2);
                ctx.check("S3 Delimiter maxKeys isTruncated", response.isTruncated());
                ctx.check("S3 Delimiter maxKeys KeyCount matches returned", response.keyCount() == totalReturned);
            } catch (Exception e) {
                ctx.check("S3 Delimiter maxKeys", false, e);
            }

            // 7. Prefix with no matching keys — empty result
            try {
                ListObjectsV2Response response = s3.listObjectsV2(
                        ListObjectsV2Request
                                .builder()
                                .bucket(bucket)
                                .delimiter("/")
                                .prefix("nonexistent/")
                                .build());

                ctx.check("S3 Delimiter no match empty contents", response.contents().isEmpty());
                ctx.check("S3 Delimiter no match empty prefixes", response.commonPrefixes().isEmpty());
            } catch (Exception e) {
                ctx.check("S3 Delimiter no match", false, e);
            }

            // Cleanup
            try {
                ListObjectsV2Response list = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build());
                for (S3Object s3Object : list.contents()) {
                    s3.deleteObject(
                            DeleteObjectRequest
                                    .builder()
                                    .bucket(bucket)
                                    .key(s3Object.key())
                                    .build());
                }
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            } catch (Exception ignored) {}
        }
    }
}
