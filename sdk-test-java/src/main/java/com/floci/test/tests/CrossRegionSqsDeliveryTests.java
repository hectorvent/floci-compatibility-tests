package com.floci.test.tests;

import com.floci.test.FlociTestGroup;
import com.floci.test.TestContext;
import com.floci.test.TestGroup;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

/**
 * Tests cross-region SQS delivery for EventBridge and S3 notifications.
 *
 * Without the fix, both services ignored the region in the SQS target ARN and
 * defaulted to the emulator's region, causing NonExistentQueue errors when the
 * target queue lived in a different region.
 *
 * Issue: https://github.com/hectorvent/floci/pull/86
 */
@FlociTestGroup
public class CrossRegionSqsDeliveryTests implements TestGroup {

    private static final Region CROSS_REGION = Region.EU_WEST_1;
    private static final String ACCOUNT_ID = "000000000000";

    @Override
    public String name() { return "cross-region-sqs"; }

    @Override
    public void run(TestContext ctx) {
        System.out.println("--- Cross-Region SQS Delivery Tests ---");
        eventBridgeCrossRegionDelivery(ctx);
        s3CrossRegionNotification(ctx);
    }

    private void eventBridgeCrossRegionDelivery(TestContext ctx) {
        System.out.println("  [EventBridge -> cross-region SQS]");

        String queueName = "eb-cross-region-queue";
        String crossQueueArn = "arn:aws:sqs:" + CROSS_REGION.id() + ":" + ACCOUNT_ID + ":" + queueName;
        String crossQueueUrl = ctx.endpoint + "/" + ACCOUNT_ID + "/" + queueName;

        try (SqsClient sqsCross = SqsClient.builder()
                .endpointOverride(ctx.endpoint)
                .region(CROSS_REGION)
                .credentialsProvider(ctx.credentials)
                .build();
             EventBridgeClient eb = EventBridgeClient.builder()
                .endpointOverride(ctx.endpoint)
                .region(ctx.region)
                .credentialsProvider(ctx.credentials)
                .build()) {

            // Create the target queue in eu-west-1
            try {
                sqsCross.createQueue(b -> b.queueName(queueName));
                ctx.check("EB CrossRegion queue created in eu-west-1", true);
            } catch (Exception e) {
                ctx.check("EB CrossRegion queue created in eu-west-1", false, e);
                return;
            }

            // Create rule in us-east-1 targeting the eu-west-1 queue
            try {
                eb.putRule(b -> b
                        .name("cross-region-rule")
                        .eventPattern("{\"source\":[\"com.crossregion\"]}")
                        .state(RuleState.ENABLED));
                eb.putTargets(b -> b
                        .rule("cross-region-rule")
                        .targets(software.amazon.awssdk.services.eventbridge.model.Target.builder()
                                .id("cross-sqs-target")
                                .arn(crossQueueArn)
                                .build()));
                ctx.check("EB CrossRegion PutRule+PutTargets (eu-west-1 ARN)", true);
            } catch (Exception e) {
                ctx.check("EB CrossRegion PutRule+PutTargets (eu-west-1 ARN)", false, e);
                cleanupEb(eb, sqsCross, crossQueueUrl);
                return;
            }

            // Fire a matching event
            try {
                PutEventsResponse resp = eb.putEvents(b -> b
                        .entries(PutEventsRequestEntry.builder()
                                .source("com.crossregion")
                                .detailType("CrossRegionTest")
                                .detail("{\"region\":\"cross\"}")
                                .build()));
                ctx.check("EB CrossRegion PutEvents no failure", resp.failedEntryCount() == 0);
            } catch (Exception e) {
                ctx.check("EB CrossRegion PutEvents no failure", false, e);
            }

            // Event must arrive in the eu-west-1 queue
            try {
                Thread.sleep(500);
                ReceiveMessageResponse recv = sqsCross.receiveMessage(b -> b
                        .queueUrl(crossQueueUrl)
                        .maxNumberOfMessages(5)
                        .waitTimeSeconds(2));
                ctx.check("EB CrossRegion event delivered to eu-west-1 queue", !recv.messages().isEmpty());
                for (var msg : recv.messages()) {
                    sqsCross.deleteMessage(b -> b.queueUrl(crossQueueUrl).receiptHandle(msg.receiptHandle()));
                }
            } catch (Exception e) {
                ctx.check("EB CrossRegion event delivered to eu-west-1 queue", false, e);
            }

            cleanupEb(eb, sqsCross, crossQueueUrl);
        }
    }

    private void s3CrossRegionNotification(TestContext ctx) {
        System.out.println("  [S3 notification -> cross-region SQS]");

        String bucketName = "s3-cross-region-notif-bucket";
        String queueName = "s3-cross-region-notif-queue";
        String crossQueueArn = "arn:aws:sqs:" + CROSS_REGION.id() + ":" + ACCOUNT_ID + ":" + queueName;
        String crossQueueUrl = ctx.endpoint + "/" + ACCOUNT_ID + "/" + queueName;

        try (S3Client s3 = S3Client.builder()
                .endpointOverride(ctx.endpoint)
                .region(ctx.region)
                .credentialsProvider(ctx.credentials)
                .forcePathStyle(true)
                .build();
             SqsClient sqsCross = SqsClient.builder()
                .endpointOverride(ctx.endpoint)
                .region(CROSS_REGION)
                .credentialsProvider(ctx.credentials)
                .build()) {

            // Create the target queue in eu-west-1
            try {
                sqsCross.createQueue(b -> b.queueName(queueName));
                ctx.check("S3 CrossRegion queue created in eu-west-1", true);
            } catch (Exception e) {
                ctx.check("S3 CrossRegion queue created in eu-west-1", false, e);
                return;
            }

            // Create bucket and configure notification pointing at the eu-west-1 queue
            try {
                s3.createBucket(b -> b.bucket(bucketName));
                s3.putBucketNotificationConfiguration(b -> b
                        .bucket(bucketName)
                        .notificationConfiguration(NotificationConfiguration.builder()
                                .queueConfigurations(QueueConfiguration.builder()
                                        .id("cross-region-notif")
                                        .queueArn(crossQueueArn)
                                        .events(Event.S3_OBJECT_CREATED)
                                        .build())
                                .build()));
                ctx.check("S3 CrossRegion PutBucketNotificationConfiguration", true);
            } catch (Exception e) {
                ctx.check("S3 CrossRegion PutBucketNotificationConfiguration", false, e);
                cleanupS3(s3, sqsCross, bucketName, crossQueueUrl);
                return;
            }

            // Put an object — should fire notification to eu-west-1 queue
            try {
                s3.putObject(b -> b.bucket(bucketName).key("cross-region-test.txt"),
                        RequestBody.fromString("cross region payload"));
                ctx.check("S3 CrossRegion PutObject", true);
            } catch (Exception e) {
                ctx.check("S3 CrossRegion PutObject", false, e);
                cleanupS3(s3, sqsCross, bucketName, crossQueueUrl);
                return;
            }

            // Notification must arrive in the eu-west-1 queue
            try {
                Thread.sleep(300);
                ReceiveMessageResponse recv = sqsCross.receiveMessage(b -> b
                        .queueUrl(crossQueueUrl)
                        .maxNumberOfMessages(5)
                        .waitTimeSeconds(2));
                ctx.check("S3 CrossRegion notification delivered to eu-west-1 queue", !recv.messages().isEmpty());
                if (!recv.messages().isEmpty()) {
                    String body = recv.messages().get(0).body();
                    ctx.check("S3 CrossRegion notification contains object key", body.contains("cross-region-test.txt"));
                    for (var msg : recv.messages()) {
                        sqsCross.deleteMessage(b -> b.queueUrl(crossQueueUrl).receiptHandle(msg.receiptHandle()));
                    }
                }
            } catch (Exception e) {
                ctx.check("S3 CrossRegion notification delivered to eu-west-1 queue", false, e);
            }

            cleanupS3(s3, sqsCross, bucketName, crossQueueUrl);
        }
    }

    private void cleanupEb(EventBridgeClient eb, SqsClient sqs, String queueUrl) {
        try { eb.removeTargets(b -> b.rule("cross-region-rule").ids("cross-sqs-target")); } catch (Exception ignore) {}
        try { eb.deleteRule(b -> b.name("cross-region-rule")); } catch (Exception ignore) {}
        try { sqs.deleteQueue(b -> b.queueUrl(queueUrl)); } catch (Exception ignore) {}
    }

    private void cleanupS3(S3Client s3, SqsClient sqs, String bucket, String queueUrl) {
        try { s3.deleteBucket(b -> b.bucket(bucket)); } catch (Exception ignore) {}
        try { sqs.deleteQueue(b -> b.queueUrl(queueUrl)); } catch (Exception ignore) {}
    }
}
