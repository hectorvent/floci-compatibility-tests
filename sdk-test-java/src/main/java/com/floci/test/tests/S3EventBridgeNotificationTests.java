package com.floci.test.tests;

import com.floci.test.FlociTestGroup;
import com.floci.test.TestContext;
import com.floci.test.TestGroup;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.Map;

@FlociTestGroup
public class S3EventBridgeNotificationTests implements TestGroup {

    @Override
    public String name() { return "s3-eventbridge-notifications"; }

    @Override
    public void run(TestContext ctx) {
        System.out.println("--- S3 EventBridge Notification Tests ---");

        try (S3Client s3 = S3Client.builder()
                .endpointOverride(ctx.endpoint)
                .region(ctx.region)
                .credentialsProvider(ctx.credentials)
                .forcePathStyle(true)
                .build();
             EventBridgeClient eb = EventBridgeClient.builder()
                .endpointOverride(ctx.endpoint)
                .region(ctx.region)
                .credentialsProvider(ctx.credentials)
                .build();
             SqsClient sqs = SqsClient.builder()
                .endpointOverride(ctx.endpoint)
                .region(ctx.region)
                .credentialsProvider(ctx.credentials)
                .build()) {

            final String bucket = "s3-eb-notif-bucket";
            final String queueName = "s3-eb-delivery-queue";
            final String accountId = "000000000000";
            final String queueUrl = ctx.endpoint + "/" + accountId + "/" + queueName;
            final String queueArn = "arn:aws:sqs:us-east-1:" + accountId + ":" + queueName;

            // Setup
            try { s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build()); } catch (Exception ignore) {}
            try { sqs.createQueue(CreateQueueRequest.builder().queueName(queueName).build()); } catch (Exception ignore) {}

            // 1. PutRule — match aws.s3 source on default bus
            try {
                eb.putRule(PutRuleRequest.builder()
                        .name("s3-eb-rule")
                        .eventPattern("{\"source\":[\"aws.s3\"]}")
                        .state(RuleState.ENABLED)
                        .build());
                ctx.check("S3-EB PutRule", true);
            } catch (Exception e) {
                ctx.check("S3-EB PutRule", false, e);
                cleanup(s3, eb, sqs, bucket, queueUrl);
                return;
            }

            // 2. PutTargets — SQS target on the rule
            try {
                PutTargetsResponse resp = eb.putTargets(PutTargetsRequest.builder()
                        .rule("s3-eb-rule")
                        .targets(Target.builder()
                                .id("sqs-1")
                                .arn(queueArn)
                                .build())
                        .build());
                ctx.check("S3-EB PutTargets", resp.failedEntryCount() == 0);
            } catch (Exception e) {
                ctx.check("S3-EB PutTargets", false, e);
                cleanup(s3, eb, sqs, bucket, queueUrl);
                return;
            }

            // 3. Enable EventBridge notifications on bucket
            try {
                s3.putBucketNotificationConfiguration(PutBucketNotificationConfigurationRequest.builder()
                        .bucket(bucket)
                        .notificationConfiguration(NotificationConfiguration.builder()
                                .eventBridgeConfiguration(EventBridgeConfiguration.builder().build())
                                .build())
                        .build());
                ctx.check("S3-EB PutBucketNotification", true);
            } catch (Exception e) {
                ctx.check("S3-EB PutBucketNotification", false, e);
                cleanup(s3, eb, sqs, bucket, queueUrl);
                return;
            }

            // 4. PutObject — should fire ObjectCreated event to EventBridge → SQS
            try {
                s3.putObject(PutObjectRequest.builder().bucket(bucket).key("test.txt").build(),
                        RequestBody.fromString("hello eventbridge"));
                ctx.check("S3-EB PutObject", true);
            } catch (Exception e) {
                ctx.check("S3-EB PutObject", false, e);
                cleanup(s3, eb, sqs, bucket, queueUrl);
                return;
            }

            // 5. Verify event arrived in SQS
            try {
                Thread.sleep(500);
                ReceiveMessageResponse recv = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(2)
                        .build());
                boolean received = !recv.messages().isEmpty();
                ctx.check("S3-EB received ObjectCreated event", received);
                if (received) {
                    String body = recv.messages().get(0).body();
                    ctx.check("S3-EB event source is aws.s3", body.contains("aws.s3"));
                    ctx.check("S3-EB event detail-type is Object Created", body.contains("Object Created"));
                    ctx.check("S3-EB event contains bucket name", body.contains(bucket));
                    ctx.check("S3-EB event contains object key", body.contains("test.txt"));
                    // Delete messages
                    for (var msg : recv.messages()) {
                        sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(msg.receiptHandle()));
                    }
                }
            } catch (Exception e) {
                ctx.check("S3-EB received ObjectCreated event", false, e);
            }

            // 6. DeleteObject — should fire ObjectDeleted event
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key("test.txt").build());
                Thread.sleep(500);
                ReceiveMessageResponse recv = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(2)
                        .build());
                boolean received = !recv.messages().isEmpty();
                ctx.check("S3-EB received ObjectDeleted event", received);
                if (received) {
                    String body = recv.messages().get(0).body();
                    ctx.check("S3-EB delete event source is aws.s3", body.contains("aws.s3"));
                    ctx.check("S3-EB delete event detail-type is Object Deleted", body.contains("Object Deleted"));
                    for (var msg : recv.messages()) {
                        sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(msg.receiptHandle()));
                    }
                }
            } catch (Exception e) {
                ctx.check("S3-EB received ObjectDeleted event", false, e);
            }

            // 7. Disable EventBridge — remove notification config
            try {
                s3.putBucketNotificationConfiguration(PutBucketNotificationConfigurationRequest.builder()
                        .bucket(bucket)
                        .notificationConfiguration(NotificationConfiguration.builder().build())
                        .build());
                s3.putObject(PutObjectRequest.builder().bucket(bucket).key("quiet.txt").build(),
                        RequestBody.fromString("no notification"));
                Thread.sleep(500);
                ReceiveMessageResponse recv = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(1)
                        .build());
                ctx.check("S3-EB no event after disable", recv.messages().isEmpty());
            } catch (Exception e) {
                ctx.check("S3-EB no event after disable", false, e);
            }

            cleanup(s3, eb, sqs, bucket, queueUrl);
        }
    }

    private void cleanup(S3Client s3, EventBridgeClient eb, SqsClient sqs,
                         String bucket, String queueUrl) {
        try { eb.removeTargets(RemoveTargetsRequest.builder().rule("s3-eb-rule").ids("sqs-1").build()); } catch (Exception ignored) {}
        try { eb.deleteRule(DeleteRuleRequest.builder().name("s3-eb-rule").build()); } catch (Exception ignored) {}
        try { sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build()); } catch (Exception ignored) {}
        try {
            var objects = s3.listObjects(ListObjectsRequest.builder().bucket(bucket).build()).contents();
            for (var obj : objects) {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(obj.key()).build());
            }
            s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
        } catch (Exception ignored) {}
    }
}
