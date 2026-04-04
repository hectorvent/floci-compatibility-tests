package com.floci.test.tests;

import com.floci.test.FlociTestGroup;
import com.floci.test.TestContext;
import com.floci.test.TestGroup;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;

import java.util.List;

@FlociTestGroup
public class EcsTests implements TestGroup {

    @Override
    public String name() { return "ecs"; }

    @Override
    public void run(TestContext ctx) {
        System.out.println("--- ECS Tests ---");

        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        String clusterName = "sdk-test-cluster-" + suffix;
        String family = "sdk-test-task-" + suffix;
        String serviceName = "sdk-test-svc-" + suffix;

        try (EcsClient ecs = EcsClient.builder()
                .endpointOverride(ctx.endpoint)
                .region(ctx.region)
                .credentialsProvider(ctx.credentials)
                .build()) {

            // ── Clusters ──────────────────────────────────────────────────────

            // 1. CreateCluster
            Cluster cluster;
            try {
                cluster = ecs.createCluster(CreateClusterRequest.builder()
                        .clusterName(clusterName)
                        .build()).cluster();
                ctx.check("ECS CreateCluster",
                        cluster != null
                        && clusterName.equals(cluster.clusterName())
                        && cluster.clusterArn() != null
                        && cluster.clusterArn().contains(clusterName)
                        && "ACTIVE".equals(cluster.status()));
            } catch (Exception e) {
                ctx.check("ECS CreateCluster", false, e);
                return;
            }

            // 2. DescribeClusters by name
            try {
                List<Cluster> clusters = ecs.describeClusters(DescribeClustersRequest.builder()
                        .clusters(clusterName)
                        .build()).clusters();
                ctx.check("ECS DescribeClusters by name",
                        clusters.size() == 1
                        && clusterName.equals(clusters.get(0).clusterName()));
            } catch (Exception e) {
                ctx.check("ECS DescribeClusters by name", false, e);
            }

            // 3. DescribeClusters by ARN
            try {
                List<Cluster> clusters = ecs.describeClusters(DescribeClustersRequest.builder()
                        .clusters(cluster.clusterArn())
                        .build()).clusters();
                ctx.check("ECS DescribeClusters by ARN",
                        clusters.size() == 1
                        && clusterName.equals(clusters.get(0).clusterName()));
            } catch (Exception e) {
                ctx.check("ECS DescribeClusters by ARN", false, e);
            }

            // 4. ListClusters
            try {
                List<String> arns = ecs.listClusters(ListClustersRequest.builder().build()).clusterArns();
                ctx.check("ECS ListClusters", arns.contains(cluster.clusterArn()));
            } catch (Exception e) {
                ctx.check("ECS ListClusters", false, e);
            }

            // 5. UpdateCluster
            try {
                Cluster updated = ecs.updateCluster(UpdateClusterRequest.builder()
                        .cluster(clusterName)
                        .settings(ClusterSetting.builder()
                                .name(ClusterSettingName.CONTAINER_INSIGHTS)
                                .value("enabled")
                                .build())
                        .build()).cluster();
                ctx.check("ECS UpdateCluster",
                        updated != null && clusterName.equals(updated.clusterName()));
            } catch (Exception e) {
                ctx.check("ECS UpdateCluster", false, e);
            }

            // 6. UpdateClusterSettings
            try {
                Cluster updated = ecs.updateClusterSettings(UpdateClusterSettingsRequest.builder()
                        .cluster(clusterName)
                        .settings(ClusterSetting.builder()
                                .name(ClusterSettingName.CONTAINER_INSIGHTS)
                                .value("disabled")
                                .build())
                        .build()).cluster();
                ctx.check("ECS UpdateClusterSettings",
                        updated != null && clusterName.equals(updated.clusterName()));
            } catch (Exception e) {
                ctx.check("ECS UpdateClusterSettings", false, e);
            }

            // 7. PutClusterCapacityProviders
            try {
                Cluster updated = ecs.putClusterCapacityProviders(PutClusterCapacityProvidersRequest.builder()
                        .cluster(clusterName)
                        .capacityProviders("FARGATE", "FARGATE_SPOT")
                        .defaultCapacityProviderStrategy(
                                CapacityProviderStrategyItem.builder()
                                        .capacityProvider("FARGATE")
                                        .weight(1)
                                        .build())
                        .build()).cluster();
                ctx.check("ECS PutClusterCapacityProviders",
                        updated != null && updated.hasCapacityProviders()
                        && updated.capacityProviders().contains("FARGATE"));
            } catch (Exception e) {
                ctx.check("ECS PutClusterCapacityProviders", false, e);
            }

            // ── Task Definitions ──────────────────────────────────────────────

            // 8. RegisterTaskDefinition
            TaskDefinition taskDef;
            try {
                taskDef = ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
                        .family(family)
                        .networkMode(NetworkMode.BRIDGE)
                        .cpu("256")
                        .memory("512")
                        .containerDefinitions(
                                ContainerDefinition.builder()
                                        .name("web")
                                        .image("nginx:alpine")
                                        .cpu(256)
                                        .memory(512)
                                        .essential(true)
                                        .portMappings(PortMapping.builder()
                                                .containerPort(80)
                                                .hostPort(0)
                                                .protocol(TransportProtocol.TCP)
                                                .build())
                                        .environment(KeyValuePair.builder()
                                                .name("ENV")
                                                .value("test")
                                                .build())
                                        .build())
                        .build()).taskDefinition();
                ctx.check("ECS RegisterTaskDefinition",
                        taskDef != null
                        && family.equals(taskDef.family())
                        && taskDef.revision() == 1
                        && "ACTIVE".equals(taskDef.statusAsString())
                        && taskDef.taskDefinitionArn() != null
                        && taskDef.taskDefinitionArn().contains(family + ":1")
                        && taskDef.containerDefinitions().size() == 1
                        && "web".equals(taskDef.containerDefinitions().get(0).name()));
            } catch (Exception e) {
                ctx.check("ECS RegisterTaskDefinition", false, e);
                return;
            }

            // 9. RegisterTaskDefinition again → revision 2
            TaskDefinition taskDefRev2;
            try {
                taskDefRev2 = ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
                        .family(family)
                        .containerDefinitions(ContainerDefinition.builder()
                                .name("app")
                                .image("alpine:latest")
                                .essential(true)
                                .build())
                        .build()).taskDefinition();
                ctx.check("ECS RegisterTaskDefinition revision 2",
                        taskDefRev2.revision() == 2
                        && taskDefRev2.taskDefinitionArn().contains(family + ":2"));
            } catch (Exception e) {
                ctx.check("ECS RegisterTaskDefinition revision 2", false, e);
                taskDefRev2 = taskDef;
            }

            // 10. DescribeTaskDefinition by family:revision
            try {
                TaskDefinition described = ecs.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                        .taskDefinition(family + ":1")
                        .build()).taskDefinition();
                ctx.check("ECS DescribeTaskDefinition by family:revision",
                        described.revision() == 1 && family.equals(described.family()));
            } catch (Exception e) {
                ctx.check("ECS DescribeTaskDefinition by family:revision", false, e);
            }

            // 11. DescribeTaskDefinition by family (latest)
            try {
                TaskDefinition latest = ecs.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                        .taskDefinition(family)
                        .build()).taskDefinition();
                ctx.check("ECS DescribeTaskDefinition by family (latest)",
                        latest.revision() == 2);
            } catch (Exception e) {
                ctx.check("ECS DescribeTaskDefinition by family (latest)", false, e);
            }

            // 12. DescribeTaskDefinition by ARN
            try {
                TaskDefinition byArn = ecs.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                        .taskDefinition(taskDef.taskDefinitionArn())
                        .build()).taskDefinition();
                ctx.check("ECS DescribeTaskDefinition by ARN",
                        byArn.taskDefinitionArn().equals(taskDef.taskDefinitionArn()));
            } catch (Exception e) {
                ctx.check("ECS DescribeTaskDefinition by ARN", false, e);
            }

            // 13. ListTaskDefinitions
            try {
                List<String> arns = ecs.listTaskDefinitions(ListTaskDefinitionsRequest.builder()
                        .familyPrefix(family)
                        .build()).taskDefinitionArns();
                ctx.check("ECS ListTaskDefinitions",
                        arns.size() == 2
                        && arns.stream().allMatch(a -> a.contains(family)));
            } catch (Exception e) {
                ctx.check("ECS ListTaskDefinitions", false, e);
            }

            // 14. ListTaskDefinitionFamilies
            try {
                List<String> families = ecs.listTaskDefinitionFamilies(
                        ListTaskDefinitionFamiliesRequest.builder()
                                .familyPrefix(family)
                                .build()).families();
                ctx.check("ECS ListTaskDefinitionFamilies",
                        families.size() == 1 && families.contains(family));
            } catch (Exception e) {
                ctx.check("ECS ListTaskDefinitionFamilies", false, e);
            }

            // ── Tags ──────────────────────────────────────────────────────────

            // 15. TagResource (cluster)
            try {
                ecs.tagResource(TagResourceRequest.builder()
                        .resourceArn(cluster.clusterArn())
                        .tags(Tag.builder().key("env").value("test").build(),
                              Tag.builder().key("team").value("sdk").build())
                        .build());
                ctx.check("ECS TagResource", true);
            } catch (Exception e) {
                ctx.check("ECS TagResource", false, e);
            }

            // 16. ListTagsForResource
            try {
                List<Tag> tags = ecs.listTagsForResource(ListTagsForResourceRequest.builder()
                        .resourceArn(cluster.clusterArn())
                        .build()).tags();
                ctx.check("ECS ListTagsForResource",
                        tags.stream().anyMatch(t -> "env".equals(t.key()) && "test".equals(t.value()))
                        && tags.stream().anyMatch(t -> "team".equals(t.key()) && "sdk".equals(t.value())));
            } catch (Exception e) {
                ctx.check("ECS ListTagsForResource", false, e);
            }

            // 17. UntagResource
            try {
                ecs.untagResource(UntagResourceRequest.builder()
                        .resourceArn(cluster.clusterArn())
                        .tagKeys("team")
                        .build());
                List<Tag> tags = ecs.listTagsForResource(ListTagsForResourceRequest.builder()
                        .resourceArn(cluster.clusterArn())
                        .build()).tags();
                ctx.check("ECS UntagResource",
                        tags.stream().noneMatch(t -> "team".equals(t.key()))
                        && tags.stream().anyMatch(t -> "env".equals(t.key())));
            } catch (Exception e) {
                ctx.check("ECS UntagResource", false, e);
            }

            // ── Account Settings ──────────────────────────────────────────────

            // 18. PutAccountSetting
            try {
                Setting setting = ecs.putAccountSetting(PutAccountSettingRequest.builder()
                        .name(SettingName.CONTAINER_INSIGHTS)
                        .value("enabled")
                        .build()).setting();
                ctx.check("ECS PutAccountSetting",
                        setting != null && "enabled".equals(setting.value()));
            } catch (Exception e) {
                ctx.check("ECS PutAccountSetting", false, e);
            }

            // 19. PutAccountSettingDefault
            try {
                Setting setting = ecs.putAccountSettingDefault(PutAccountSettingDefaultRequest.builder()
                        .name(SettingName.TASK_LONG_ARN_FORMAT)
                        .value("enabled")
                        .build()).setting();
                ctx.check("ECS PutAccountSettingDefault",
                        setting != null && "enabled".equals(setting.value()));
            } catch (Exception e) {
                ctx.check("ECS PutAccountSettingDefault", false, e);
            }

            // 20. ListAccountSettings
            try {
                List<Setting> settings = ecs.listAccountSettings(ListAccountSettingsRequest.builder()
                        .build()).settings();
                ctx.check("ECS ListAccountSettings", settings.size() >= 2);
            } catch (Exception e) {
                ctx.check("ECS ListAccountSettings", false, e);
            }

            // 21. DeleteAccountSetting
            try {
                ecs.deleteAccountSetting(DeleteAccountSettingRequest.builder()
                        .name(SettingName.TASK_LONG_ARN_FORMAT)
                        .build());
                ctx.check("ECS DeleteAccountSetting", true);
            } catch (Exception e) {
                ctx.check("ECS DeleteAccountSetting", false, e);
            }

            // ── Capacity Providers ────────────────────────────────────────────

            // 22. DescribeCapacityProviders (built-ins)
            try {
                List<CapacityProvider> providers = ecs.describeCapacityProviders(
                        DescribeCapacityProvidersRequest.builder()
                                .capacityProviders("FARGATE", "FARGATE_SPOT")
                                .build()).capacityProviders();
                ctx.check("ECS DescribeCapacityProviders built-ins",
                        providers.size() == 2
                        && providers.stream().anyMatch(p -> "FARGATE".equals(p.name()))
                        && providers.stream().anyMatch(p -> "FARGATE_SPOT".equals(p.name())));
            } catch (Exception e) {
                ctx.check("ECS DescribeCapacityProviders built-ins", false, e);
            }

            // 23. CreateCapacityProvider
            String cpName = "cp-" + suffix;
            CapacityProvider capacityProvider = null;
            try {
                capacityProvider = ecs.createCapacityProvider(CreateCapacityProviderRequest.builder()
                        .name(cpName)
                        .autoScalingGroupProvider(AutoScalingGroupProvider.builder()
                                .autoScalingGroupArn("arn:aws:autoscaling:us-east-1:000000000000:autoScalingGroup:123:autoScalingGroupName/test-asg")
                                .build())
                        .build()).capacityProvider();
                ctx.check("ECS CreateCapacityProvider",
                        capacityProvider != null
                        && cpName.equals(capacityProvider.name())
                        && "ACTIVE".equals(capacityProvider.statusAsString()));
            } catch (Exception e) {
                ctx.check("ECS CreateCapacityProvider", false, e);
            }

            // 24. UpdateCapacityProvider
            if (capacityProvider != null) {
                try {
                    CapacityProvider updated = ecs.updateCapacityProvider(UpdateCapacityProviderRequest.builder()
                            .name(cpName)
                            .autoScalingGroupProvider(AutoScalingGroupProviderUpdate.builder()
                                    .build())
                            .build()).capacityProvider();
                    ctx.check("ECS UpdateCapacityProvider",
                            updated != null && cpName.equals(updated.name()));
                } catch (Exception e) {
                    ctx.check("ECS UpdateCapacityProvider", false, e);
                }

                // 25. DeleteCapacityProvider
                try {
                    CapacityProvider deleted = ecs.deleteCapacityProvider(DeleteCapacityProviderRequest.builder()
                            .capacityProvider(cpName)
                            .build()).capacityProvider();
                    ctx.check("ECS DeleteCapacityProvider",
                            deleted != null && cpName.equals(deleted.name()));
                } catch (Exception e) {
                    ctx.check("ECS DeleteCapacityProvider", false, e);
                }
            }

            // ── Container Instances ───────────────────────────────────────────

            // 26. RegisterContainerInstance
            ContainerInstance containerInstance;
            try {
                containerInstance = ecs.registerContainerInstance(RegisterContainerInstanceRequest.builder()
                        .cluster(clusterName)
                        .build()).containerInstance();
                ctx.check("ECS RegisterContainerInstance",
                        containerInstance != null
                        && containerInstance.containerInstanceArn() != null
                        && "ACTIVE".equals(containerInstance.status())
                        && containerInstance.agentConnected());
            } catch (Exception e) {
                ctx.check("ECS RegisterContainerInstance", false, e);
                containerInstance = null;
            }

            if (containerInstance != null) {
                String instanceArn = containerInstance.containerInstanceArn();

                // 27. ListContainerInstances
                try {
                    List<String> arns = ecs.listContainerInstances(ListContainerInstancesRequest.builder()
                            .cluster(clusterName)
                            .build()).containerInstanceArns();
                    ctx.check("ECS ListContainerInstances", arns.contains(instanceArn));
                } catch (Exception e) {
                    ctx.check("ECS ListContainerInstances", false, e);
                }

                // 28. DescribeContainerInstances
                try {
                    List<ContainerInstance> instances = ecs.describeContainerInstances(
                            DescribeContainerInstancesRequest.builder()
                                    .cluster(clusterName)
                                    .containerInstances(instanceArn)
                                    .build()).containerInstances();
                    ctx.check("ECS DescribeContainerInstances",
                            instances.size() == 1
                            && instanceArn.equals(instances.get(0).containerInstanceArn()));
                } catch (Exception e) {
                    ctx.check("ECS DescribeContainerInstances", false, e);
                }

                // 29. UpdateContainerAgent
                try {
                    ContainerInstance updated = ecs.updateContainerAgent(UpdateContainerAgentRequest.builder()
                            .cluster(clusterName)
                            .containerInstance(instanceArn)
                            .build()).containerInstance();
                    ctx.check("ECS UpdateContainerAgent",
                            updated != null && instanceArn.equals(updated.containerInstanceArn()));
                } catch (Exception e) {
                    ctx.check("ECS UpdateContainerAgent", false, e);
                }

                // 30. UpdateContainerInstancesState
                try {
                    List<ContainerInstance> updated = ecs.updateContainerInstancesState(
                            UpdateContainerInstancesStateRequest.builder()
                                    .cluster(clusterName)
                                    .containerInstances(instanceArn)
                                    .status(ContainerInstanceStatus.DRAINING)
                                    .build()).containerInstances();
                    ctx.check("ECS UpdateContainerInstancesState",
                            !updated.isEmpty()
                            && "DRAINING".equals(updated.get(0).status()));
                } catch (Exception e) {
                    ctx.check("ECS UpdateContainerInstancesState", false, e);
                }

                // Set back to ACTIVE for StartTask
                try {
                    ecs.updateContainerInstancesState(UpdateContainerInstancesStateRequest.builder()
                            .cluster(clusterName)
                            .containerInstances(instanceArn)
                            .status(ContainerInstanceStatus.ACTIVE)
                            .build());
                } catch (Exception ignored) {}

                // 31. StartTask
                try {
                    List<Task> started = ecs.startTask(StartTaskRequest.builder()
                            .cluster(clusterName)
                            .containerInstances(instanceArn)
                            .taskDefinition(family + ":1")
                            .build()).tasks();
                    ctx.check("ECS StartTask",
                            started.size() == 1
                            && started.get(0).taskArn() != null
                            && instanceArn.equals(started.get(0).containerInstanceArn()));
                    // Stop the task
                    if (!started.isEmpty()) {
                        ecs.stopTask(StopTaskRequest.builder()
                                .cluster(clusterName)
                                .task(started.get(0).taskArn())
                                .build());
                    }
                } catch (Exception e) {
                    ctx.check("ECS StartTask", false, e);
                }

                // 32. DeregisterContainerInstance
                try {
                    ContainerInstance deregistered = ecs.deregisterContainerInstance(
                            DeregisterContainerInstanceRequest.builder()
                                    .cluster(clusterName)
                                    .containerInstance(instanceArn)
                                    .force(true)
                                    .build()).containerInstance();
                    ctx.check("ECS DeregisterContainerInstance",
                            deregistered != null && "INACTIVE".equals(deregistered.status()));
                } catch (Exception e) {
                    ctx.check("ECS DeregisterContainerInstance", false, e);
                }
            }

            // ── Attributes ────────────────────────────────────────────────────

            // 33. PutAttributes
            String targetId = cluster.clusterArn();
            try {
                List<Attribute> stored = ecs.putAttributes(PutAttributesRequest.builder()
                        .cluster(clusterName)
                        .attributes(
                                Attribute.builder().name("com.example.attr").value("val1")
                                        .targetType(TargetType.CONTAINER_INSTANCE).targetId(targetId).build())
                        .build()).attributes();
                ctx.check("ECS PutAttributes", !stored.isEmpty());
            } catch (Exception e) {
                ctx.check("ECS PutAttributes", false, e);
            }

            // 34. ListAttributes
            try {
                List<Attribute> attrs = ecs.listAttributes(ListAttributesRequest.builder()
                        .cluster(clusterName)
                        .targetType(TargetType.CONTAINER_INSTANCE)
                        .build()).attributes();
                ctx.check("ECS ListAttributes",
                        attrs.stream().anyMatch(a -> "com.example.attr".equals(a.name())));
            } catch (Exception e) {
                ctx.check("ECS ListAttributes", false, e);
            }

            // 35. DeleteAttributes
            try {
                List<Attribute> deleted = ecs.deleteAttributes(DeleteAttributesRequest.builder()
                        .cluster(clusterName)
                        .attributes(Attribute.builder().name("com.example.attr")
                                .targetType(TargetType.CONTAINER_INSTANCE).targetId(targetId).build())
                        .build()).attributes();
                ctx.check("ECS DeleteAttributes", !deleted.isEmpty());
            } catch (Exception e) {
                ctx.check("ECS DeleteAttributes", false, e);
            }

            // ── Stubs ─────────────────────────────────────────────────────────

            // 36. DiscoverPollEndpoint
            try {
                DiscoverPollEndpointResponse pollResp = ecs.discoverPollEndpoint(
                        DiscoverPollEndpointRequest.builder()
                                .cluster(clusterName)
                                .build());
                ctx.check("ECS DiscoverPollEndpoint",
                        pollResp.endpoint() != null && !pollResp.endpoint().isEmpty());
            } catch (Exception e) {
                ctx.check("ECS DiscoverPollEndpoint", false, e);
            }

            // 37. SubmitTaskStateChange
            try {
                String ack = ecs.submitTaskStateChange(SubmitTaskStateChangeRequest.builder()
                        .cluster(clusterName)
                        .status("RUNNING")
                        .build()).acknowledgment();
                ctx.check("ECS SubmitTaskStateChange", ack != null && !ack.isEmpty());
            } catch (Exception e) {
                ctx.check("ECS SubmitTaskStateChange", false, e);
            }

            // 38. SubmitContainerStateChange
            try {
                String ack = ecs.submitContainerStateChange(SubmitContainerStateChangeRequest.builder()
                        .cluster(clusterName)
                        .status("RUNNING")
                        .build()).acknowledgment();
                ctx.check("ECS SubmitContainerStateChange", ack != null && !ack.isEmpty());
            } catch (Exception e) {
                ctx.check("ECS SubmitContainerStateChange", false, e);
            }

            // 39. SubmitAttachmentStateChanges
            try {
                String ack = ecs.submitAttachmentStateChanges(SubmitAttachmentStateChangesRequest.builder()
                        .cluster(clusterName)
                        .attachments(AttachmentStateChange.builder()
                                .attachmentArn("arn:aws:ecs:us-east-1:000000000000:attachment/test")
                                .status("ATTACHED")
                                .build())
                        .build()).acknowledgment();
                ctx.check("ECS SubmitAttachmentStateChanges", ack != null && !ack.isEmpty());
            } catch (Exception e) {
                ctx.check("ECS SubmitAttachmentStateChanges", false, e);
            }

            // ── Tasks ─────────────────────────────────────────────────────────

            // 40. RunTask
            Task task;
            try {
                List<Task> tasks = ecs.runTask(RunTaskRequest.builder()
                        .cluster(clusterName)
                        .taskDefinition(family + ":1")
                        .count(1)
                        .launchType(LaunchType.FARGATE)
                        .startedBy("sdk-test")
                        .build()).tasks();
                ctx.check("ECS RunTask",
                        tasks.size() == 1
                        && tasks.get(0).taskArn() != null
                        && tasks.get(0).clusterArn().equals(cluster.clusterArn())
                        && tasks.get(0).taskDefinitionArn().equals(taskDef.taskDefinitionArn())
                        && "RUNNING".equals(tasks.get(0).lastStatus()));
                task = tasks.get(0);
            } catch (Exception e) {
                ctx.check("ECS RunTask", false, e);
                return;
            }

            // 41. DescribeTasks
            try {
                List<Task> described = ecs.describeTasks(DescribeTasksRequest.builder()
                        .cluster(clusterName)
                        .tasks(task.taskArn())
                        .build()).tasks();
                ctx.check("ECS DescribeTasks",
                        described.size() == 1
                        && task.taskArn().equals(described.get(0).taskArn())
                        && "RUNNING".equals(described.get(0).lastStatus())
                        && "sdk-test".equals(described.get(0).startedBy()));
            } catch (Exception e) {
                ctx.check("ECS DescribeTasks", false, e);
            }

            // 42. ListTasks
            try {
                List<String> taskArns = ecs.listTasks(ListTasksRequest.builder()
                        .cluster(clusterName)
                        .build()).taskArns();
                ctx.check("ECS ListTasks", taskArns.contains(task.taskArn()));
            } catch (Exception e) {
                ctx.check("ECS ListTasks", false, e);
            }

            // 43. ListTasks desiredStatus=RUNNING
            try {
                List<String> taskArns = ecs.listTasks(ListTasksRequest.builder()
                        .cluster(clusterName)
                        .desiredStatus(DesiredStatus.RUNNING)
                        .build()).taskArns();
                ctx.check("ECS ListTasks desiredStatus=RUNNING",
                        taskArns.contains(task.taskArn()));
            } catch (Exception e) {
                ctx.check("ECS ListTasks desiredStatus=RUNNING", false, e);
            }

            // 44. Cluster runningTasksCount updated after RunTask
            try {
                Cluster updated = ecs.describeClusters(DescribeClustersRequest.builder()
                        .clusters(clusterName)
                        .build()).clusters().get(0);
                ctx.check("ECS cluster runningTasksCount after RunTask",
                        updated.runningTasksCount() == 1);
            } catch (Exception e) {
                ctx.check("ECS cluster runningTasksCount after RunTask", false, e);
            }

            // 45. UpdateTaskProtection
            try {
                List<ProtectedTask> protectedTasks = ecs.updateTaskProtection(
                        UpdateTaskProtectionRequest.builder()
                                .cluster(clusterName)
                                .tasks(task.taskArn())
                                .protectionEnabled(true)
                                .expiresInMinutes(60)
                                .build()).protectedTasks();
                ctx.check("ECS UpdateTaskProtection",
                        protectedTasks.size() == 1
                        && Boolean.TRUE.equals(protectedTasks.get(0).protectionEnabled())
                        && protectedTasks.get(0).expirationDate() != null);
            } catch (Exception e) {
                ctx.check("ECS UpdateTaskProtection", false, e);
            }

            // 46. GetTaskProtection
            try {
                List<ProtectedTask> protectedTasks = ecs.getTaskProtection(GetTaskProtectionRequest.builder()
                        .cluster(clusterName)
                        .tasks(task.taskArn())
                        .build()).protectedTasks();
                ctx.check("ECS GetTaskProtection",
                        protectedTasks.size() == 1
                        && Boolean.TRUE.equals(protectedTasks.get(0).protectionEnabled()));
            } catch (Exception e) {
                ctx.check("ECS GetTaskProtection", false, e);
            }

            // Disable protection
            try {
                ecs.updateTaskProtection(UpdateTaskProtectionRequest.builder()
                        .cluster(clusterName)
                        .tasks(task.taskArn())
                        .protectionEnabled(false)
                        .build());
            } catch (Exception ignored) {}

            // 47. StopTask
            try {
                Task stopped = ecs.stopTask(StopTaskRequest.builder()
                        .cluster(clusterName)
                        .task(task.taskArn())
                        .reason("sdk-test-stop")
                        .build()).task();
                ctx.check("ECS StopTask",
                        "STOPPED".equals(stopped.lastStatus())
                        && "sdk-test-stop".equals(stopped.stoppedReason())
                        && stopped.stoppedAt() != null);
            } catch (Exception e) {
                ctx.check("ECS StopTask", false, e);
            }

            // 48. DescribeTasks after stop
            try {
                Task stoppedTask = ecs.describeTasks(DescribeTasksRequest.builder()
                        .cluster(clusterName)
                        .tasks(task.taskArn())
                        .build()).tasks().get(0);
                ctx.check("ECS DescribeTasks after stop",
                        "STOPPED".equals(stoppedTask.lastStatus()));
            } catch (Exception e) {
                ctx.check("ECS DescribeTasks after stop", false, e);
            }

            // 49. ListTasks desiredStatus=STOPPED
            try {
                List<String> taskArns = ecs.listTasks(ListTasksRequest.builder()
                        .cluster(clusterName)
                        .desiredStatus(DesiredStatus.STOPPED)
                        .build()).taskArns();
                ctx.check("ECS ListTasks desiredStatus=STOPPED",
                        taskArns.contains(task.taskArn()));
            } catch (Exception e) {
                ctx.check("ECS ListTasks desiredStatus=STOPPED", false, e);
            }

            // ── Services ──────────────────────────────────────────────────────

            // 50. CreateService
            Service service;
            try {
                service = ecs.createService(CreateServiceRequest.builder()
                        .cluster(clusterName)
                        .serviceName(serviceName)
                        .taskDefinition(family + ":1")
                        .desiredCount(1)
                        .launchType(LaunchType.FARGATE)
                        .build()).service();
                ctx.check("ECS CreateService",
                        service != null
                        && serviceName.equals(service.serviceName())
                        && service.serviceArn() != null
                        && service.serviceArn().contains(serviceName)
                        && service.desiredCount() == 1
                        && "ACTIVE".equals(service.status()));
            } catch (Exception e) {
                ctx.check("ECS CreateService", false, e);
                return;
            }

            // 51. CreateService duplicate → InvalidParameterException
            try {
                ecs.createService(CreateServiceRequest.builder()
                        .cluster(clusterName)
                        .serviceName(serviceName)
                        .taskDefinition(family + ":1")
                        .desiredCount(1)
                        .build());
                ctx.check("ECS CreateService duplicate fails", false);
            } catch (InvalidParameterException e) {
                ctx.check("ECS CreateService duplicate fails", true);
            } catch (Exception e) {
                ctx.check("ECS CreateService duplicate fails", false, e);
            }

            // 52. DescribeServices
            try {
                List<Service> services = ecs.describeServices(DescribeServicesRequest.builder()
                        .cluster(clusterName)
                        .services(serviceName)
                        .build()).services();
                ctx.check("ECS DescribeServices",
                        services.size() == 1
                        && serviceName.equals(services.get(0).serviceName())
                        && services.get(0).desiredCount() == 1);
            } catch (Exception e) {
                ctx.check("ECS DescribeServices", false, e);
            }

            // 53. ListServices
            try {
                List<String> serviceArns = ecs.listServices(ListServicesRequest.builder()
                        .cluster(clusterName)
                        .build()).serviceArns();
                ctx.check("ECS ListServices", serviceArns.contains(service.serviceArn()));
            } catch (Exception e) {
                ctx.check("ECS ListServices", false, e);
            }

            // 54. Reconciler starts task for service (wait up to 10s)
            try {
                boolean reconciled = false;
                for (int i = 0; i < 10; i++) {
                    Thread.sleep(1000);
                    Service svc = ecs.describeServices(DescribeServicesRequest.builder()
                            .cluster(clusterName)
                            .services(serviceName)
                            .build()).services().get(0);
                    if (svc.runningCount() >= 1) {
                        reconciled = true;
                        break;
                    }
                }
                ctx.check("ECS service reconciler reaches desiredCount", reconciled);
            } catch (Exception e) {
                ctx.check("ECS service reconciler reaches desiredCount", false, e);
            }

            // 55. DescribeServiceDeployments via ListServiceDeployments
            try {
                List<ServiceDeploymentBrief> briefs = ecs.listServiceDeployments(
                        ListServiceDeploymentsRequest.builder()
                                .cluster(clusterName)
                                .service(serviceName)
                                .build()).serviceDeployments();
                ctx.check("ECS ListServiceDeployments", !briefs.isEmpty());

                if (!briefs.isEmpty()) {
                    String deploymentArn = briefs.get(0).serviceDeploymentArn();
                    List<ServiceDeployment> deployments = ecs.describeServiceDeployments(
                            DescribeServiceDeploymentsRequest.builder()
                                    .serviceDeploymentArns(deploymentArn)
                                    .build()).serviceDeployments();
                    ctx.check("ECS DescribeServiceDeployments",
                            deployments.size() == 1
                            && service.serviceArn().equals(deployments.get(0).serviceArn()));
                }
            } catch (Exception e) {
                ctx.check("ECS ListServiceDeployments", false, e);
            }

            // 56. UpdateService desiredCount=0
            try {
                Service updated = ecs.updateService(UpdateServiceRequest.builder()
                        .cluster(clusterName)
                        .service(serviceName)
                        .desiredCount(0)
                        .build()).service();
                ctx.check("ECS UpdateService desiredCount=0",
                        updated.desiredCount() == 0);
            } catch (Exception e) {
                ctx.check("ECS UpdateService desiredCount=0", false, e);
            }

            // 57. UpdateService taskDefinition
            try {
                Service updated = ecs.updateService(UpdateServiceRequest.builder()
                        .cluster(clusterName)
                        .service(serviceName)
                        .taskDefinition(family + ":2")
                        .build()).service();
                ctx.check("ECS UpdateService taskDefinition",
                        updated.taskDefinition() != null
                        && updated.taskDefinition().contains(family + ":2"));
            } catch (Exception e) {
                ctx.check("ECS UpdateService taskDefinition", false, e);
            }

            // 58. Task Sets (EXTERNAL deployment controller not required for CreateTaskSet)
            String taskSetArn = null;
            try {
                software.amazon.awssdk.services.ecs.model.TaskSet ts =
                        ecs.createTaskSet(CreateTaskSetRequest.builder()
                                .cluster(clusterName)
                                .service(serviceName)
                                .taskDefinition(family + ":1")
                                .launchType(LaunchType.FARGATE)
                                .scale(Scale.builder().value(50.0).unit(ScaleUnit.PERCENT).build())
                                .build()).taskSet();
                ctx.check("ECS CreateTaskSet",
                        ts != null && ts.taskSetArn() != null
                        && "ACTIVE".equals(ts.status()));
                taskSetArn = ts.taskSetArn();
            } catch (Exception e) {
                ctx.check("ECS CreateTaskSet", false, e);
            }

            if (taskSetArn != null) {
                // 59. DescribeTaskSets
                try {
                    List<software.amazon.awssdk.services.ecs.model.TaskSet> sets =
                            ecs.describeTaskSets(DescribeTaskSetsRequest.builder()
                                    .cluster(clusterName)
                                    .service(serviceName)
                                    .taskSets(taskSetArn)
                                    .build()).taskSets();
                    ctx.check("ECS DescribeTaskSets",
                            sets.size() == 1 && taskSetArn.equals(sets.get(0).taskSetArn()));
                } catch (Exception e) {
                    ctx.check("ECS DescribeTaskSets", false, e);
                }

                // 60. UpdateTaskSet
                try {
                    software.amazon.awssdk.services.ecs.model.TaskSet updated =
                            ecs.updateTaskSet(UpdateTaskSetRequest.builder()
                                    .cluster(clusterName)
                                    .service(serviceName)
                                    .taskSet(taskSetArn)
                                    .scale(Scale.builder().value(100.0).unit(ScaleUnit.PERCENT).build())
                                    .build()).taskSet();
                    ctx.check("ECS UpdateTaskSet",
                            updated != null && 100.0 == updated.scale().value());
                } catch (Exception e) {
                    ctx.check("ECS UpdateTaskSet", false, e);
                }

                // 61. UpdateServicePrimaryTaskSet
                try {
                    software.amazon.awssdk.services.ecs.model.TaskSet primary =
                            ecs.updateServicePrimaryTaskSet(UpdateServicePrimaryTaskSetRequest.builder()
                                    .cluster(clusterName)
                                    .service(serviceName)
                                    .primaryTaskSet(taskSetArn)
                                    .build()).taskSet();
                    ctx.check("ECS UpdateServicePrimaryTaskSet",
                            primary != null && "PRIMARY".equals(primary.status()));
                } catch (Exception e) {
                    ctx.check("ECS UpdateServicePrimaryTaskSet", false, e);
                }

                // 62. DeleteTaskSet
                try {
                    software.amazon.awssdk.services.ecs.model.TaskSet deleted =
                            ecs.deleteTaskSet(DeleteTaskSetRequest.builder()
                                    .cluster(clusterName)
                                    .service(serviceName)
                                    .taskSet(taskSetArn)
                                    .build()).taskSet();
                    ctx.check("ECS DeleteTaskSet", deleted != null);
                } catch (Exception e) {
                    ctx.check("ECS DeleteTaskSet", false, e);
                }
            }

            // 63. DeleteService
            try {
                Service deleted = ecs.deleteService(DeleteServiceRequest.builder()
                        .cluster(clusterName)
                        .service(serviceName)
                        .build()).service();
                ctx.check("ECS DeleteService", "INACTIVE".equals(deleted.status()));
            } catch (Exception e) {
                ctx.check("ECS DeleteService", false, e);
            }

            // 64. ListServices after delete
            try {
                List<String> serviceArns = ecs.listServices(ListServicesRequest.builder()
                        .cluster(clusterName)
                        .build()).serviceArns();
                ctx.check("ECS ListServices after delete",
                        !serviceArns.contains(service.serviceArn()));
            } catch (Exception e) {
                ctx.check("ECS ListServices after delete", false, e);
            }

            // ── Task Definition lifecycle ──────────────────────────────────────

            // 65. DeregisterTaskDefinition
            try {
                TaskDefinition deregistered = ecs.deregisterTaskDefinition(
                        DeregisterTaskDefinitionRequest.builder()
                                .taskDefinition(family + ":1")
                                .build()).taskDefinition();
                ctx.check("ECS DeregisterTaskDefinition",
                        "INACTIVE".equals(deregistered.statusAsString()));
            } catch (Exception e) {
                ctx.check("ECS DeregisterTaskDefinition", false, e);
            }

            // 66. ListTaskDefinitions filtered by ACTIVE
            try {
                List<String> activeArns = ecs.listTaskDefinitions(ListTaskDefinitionsRequest.builder()
                        .familyPrefix(family)
                        .status(TaskDefinitionStatus.ACTIVE)
                        .build()).taskDefinitionArns();
                ctx.check("ECS ListTaskDefinitions ACTIVE only",
                        activeArns.size() == 1
                        && activeArns.get(0).contains(family + ":2"));
            } catch (Exception e) {
                ctx.check("ECS ListTaskDefinitions ACTIVE only", false, e);
            }

            // 67. DeleteTaskDefinitions (INACTIVE only)
            try {
                List<TaskDefinition> deletedDefs = ecs.deleteTaskDefinitions(
                        DeleteTaskDefinitionsRequest.builder()
                                .taskDefinitions(family + ":1")
                                .build()).taskDefinitions();
                ctx.check("ECS DeleteTaskDefinitions",
                        deletedDefs.size() == 1
                        && deletedDefs.get(0).taskDefinitionArn().contains(family + ":1"));
            } catch (Exception e) {
                ctx.check("ECS DeleteTaskDefinitions", false, e);
            }

            // ── Cluster deletion ───────────────────────────────────────────────

            // 68. DeleteCluster fails when running tasks exist
            try {
                ecs.runTask(RunTaskRequest.builder()
                        .cluster(clusterName)
                        .taskDefinition(family + ":2")
                        .count(1)
                        .build());
                ecs.deleteCluster(DeleteClusterRequest.builder().cluster(clusterName).build());
                ctx.check("ECS DeleteCluster fails with running tasks", false);
            } catch (ClusterContainsTasksException e) {
                ctx.check("ECS DeleteCluster fails with running tasks", true);
            } catch (Exception e) {
                ctx.check("ECS DeleteCluster fails with running tasks", false, e);
            }

            // 69. Stop running tasks then delete cluster
            try {
                List<String> running = ecs.listTasks(ListTasksRequest.builder()
                        .cluster(clusterName)
                        .desiredStatus(DesiredStatus.RUNNING)
                        .build()).taskArns();
                for (String taskArn : running) {
                    ecs.stopTask(StopTaskRequest.builder()
                            .cluster(clusterName)
                            .task(taskArn)
                            .build());
                }
                Cluster deleted = ecs.deleteCluster(DeleteClusterRequest.builder()
                        .cluster(clusterName)
                        .build()).cluster();
                ctx.check("ECS DeleteCluster", "INACTIVE".equals(deleted.status()));
            } catch (Exception e) {
                ctx.check("ECS DeleteCluster", false, e);
            }

            // 70. ListClusters no longer contains deleted cluster
            try {
                List<String> arns = ecs.listClusters(ListClustersRequest.builder().build()).clusterArns();
                ctx.check("ECS ListClusters after delete", !arns.contains(cluster.clusterArn()));
            } catch (Exception e) {
                ctx.check("ECS ListClusters after delete", false, e);
            }

        } catch (Exception e) {
            ctx.check("ECS Client", false, e);
        }
    }
}
