package com.floci.test.tests;

import com.floci.test.FlociTestGroup;
import com.floci.test.TestContext;
import com.floci.test.TestGroup;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.List;

@FlociTestGroup
public class Ec2Tests implements TestGroup {

    @Override
    public String name() { return "ec2"; }

    @Override
    public void run(TestContext ctx) {
        System.out.println("--- EC2 Tests ---");

        try (Ec2Client ec2 = Ec2Client.builder()
                .endpointOverride(ctx.endpoint)
                .region(ctx.region)
                .credentialsProvider(ctx.credentials)
                .build()) {

            // ── Default resources ──────────────────────────────────────────────

            // 1. DescribeVpcs — default VPC exists
            try {
                DescribeVpcsResponse resp = ec2.describeVpcs();
                boolean hasDefault = resp.vpcs().stream().anyMatch(Vpc::isDefault);
                ctx.check("EC2 DescribeVpcs default VPC exists", hasDefault);
            } catch (Exception e) {
                ctx.check("EC2 DescribeVpcs default VPC exists", false, e);
            }

            // 2. DescribeSubnets — at least 3 default subnets
            try {
                DescribeSubnetsResponse resp = ec2.describeSubnets();
                long defaultCount = resp.subnets().stream().filter(Subnet::defaultForAz).count();
                ctx.check("EC2 DescribeSubnets default subnets", defaultCount >= 3);
            } catch (Exception e) {
                ctx.check("EC2 DescribeSubnets default subnets", false, e);
            }

            // 3. DescribeSecurityGroups — default SG exists
            try {
                DescribeSecurityGroupsResponse resp = ec2.describeSecurityGroups();
                boolean hasDefault = resp.securityGroups().stream()
                        .anyMatch(sg -> "default".equals(sg.groupName()));
                ctx.check("EC2 DescribeSecurityGroups default SG", hasDefault);
            } catch (Exception e) {
                ctx.check("EC2 DescribeSecurityGroups default SG", false, e);
            }

            // 4. DescribeAvailabilityZones
            try {
                DescribeAvailabilityZonesResponse resp = ec2.describeAvailabilityZones();
                ctx.check("EC2 DescribeAvailabilityZones", resp.availabilityZones().size() == 3);
            } catch (Exception e) {
                ctx.check("EC2 DescribeAvailabilityZones", false, e);
            }

            // 5. DescribeRegions
            try {
                DescribeRegionsResponse resp = ec2.describeRegions();
                ctx.check("EC2 DescribeRegions", !resp.regions().isEmpty());
            } catch (Exception e) {
                ctx.check("EC2 DescribeRegions", false, e);
            }

            // 6. DescribeImages — static AMI list
            try {
                DescribeImagesResponse resp = ec2.describeImages();
                ctx.check("EC2 DescribeImages", !resp.images().isEmpty()
                        && resp.images().stream().allMatch(img -> img.imageId().startsWith("ami-")));
            } catch (Exception e) {
                ctx.check("EC2 DescribeImages", false, e);
            }

            // 7. DescribeInstanceTypes
            try {
                DescribeInstanceTypesResponse resp = ec2.describeInstanceTypes(
                        DescribeInstanceTypesRequest.builder().build());
                ctx.check("EC2 DescribeInstanceTypes", !resp.instanceTypes().isEmpty());
            } catch (Exception e) {
                ctx.check("EC2 DescribeInstanceTypes", false, e);
            }

            // ── VPC lifecycle ──────────────────────────────────────────────────

            String vpcId;
            try {
                CreateVpcResponse resp = ec2.createVpc(CreateVpcRequest.builder()
                        .cidrBlock("10.0.0.0/16").build());
                vpcId = resp.vpc().vpcId();
                ctx.check("EC2 CreateVpc",
                        vpcId != null && vpcId.startsWith("vpc-")
                        && "10.0.0.0/16".equals(resp.vpc().cidrBlock())
                        && VpcState.AVAILABLE.equals(resp.vpc().state()));
            } catch (Exception e) {
                ctx.check("EC2 CreateVpc", false, e);
                return;
            }

            // 9. DescribeVpcs by ID
            try {
                DescribeVpcsResponse resp = ec2.describeVpcs(DescribeVpcsRequest.builder()
                        .vpcIds(vpcId).build());
                ctx.check("EC2 DescribeVpcs by ID",
                        resp.vpcs().size() == 1 && vpcId.equals(resp.vpcs().get(0).vpcId()));
            } catch (Exception e) {
                ctx.check("EC2 DescribeVpcs by ID", false, e);
            }

            // 10. DescribeVpcs — non-existent ID returns error
            try {
                ec2.describeVpcs(DescribeVpcsRequest.builder().vpcIds("vpc-doesnotexist").build());
                ctx.check("EC2 DescribeVpcs NotFound error", false);
            } catch (Ec2Exception e) {
                ctx.check("EC2 DescribeVpcs NotFound error",
                        "InvalidVpcID.NotFound".equals(e.awsErrorDetails().errorCode()));
            } catch (Exception e) {
                ctx.check("EC2 DescribeVpcs NotFound error", false, e);
            }

            // ── Subnet lifecycle ───────────────────────────────────────────────

            String subnetId;
            try {
                CreateSubnetResponse resp = ec2.createSubnet(CreateSubnetRequest.builder()
                        .vpcId(vpcId)
                        .cidrBlock("10.0.1.0/24")
                        .availabilityZone("us-east-1a")
                        .build());
                subnetId = resp.subnet().subnetId();
                ctx.check("EC2 CreateSubnet",
                        subnetId != null && subnetId.startsWith("subnet-")
                        && vpcId.equals(resp.subnet().vpcId())
                        && "10.0.1.0/24".equals(resp.subnet().cidrBlock()));
            } catch (Exception e) {
                ctx.check("EC2 CreateSubnet", false, e);
                subnetId = null;
            }

            // 12. DescribeSubnets by ID
            if (subnetId != null) {
                try {
                    DescribeSubnetsResponse resp = ec2.describeSubnets(DescribeSubnetsRequest.builder()
                            .subnetIds(subnetId).build());
                    ctx.check("EC2 DescribeSubnets by ID",
                            resp.subnets().size() == 1 && subnetId.equals(resp.subnets().get(0).subnetId()));
                } catch (Exception e) {
                    ctx.check("EC2 DescribeSubnets by ID", false, e);
                }
            }

            // ── Security Group lifecycle ───────────────────────────────────────

            String sgId;
            try {
                CreateSecurityGroupResponse resp = ec2.createSecurityGroup(
                        CreateSecurityGroupRequest.builder()
                                .groupName("sdk-test-sg")
                                .description("SDK test security group")
                                .vpcId(vpcId)
                                .build());
                sgId = resp.groupId();
                ctx.check("EC2 CreateSecurityGroup",
                        sgId != null && sgId.startsWith("sg-"));
            } catch (Exception e) {
                ctx.check("EC2 CreateSecurityGroup", false, e);
                sgId = null;
            }

            // 14. AuthorizeSecurityGroupIngress
            if (sgId != null) {
                try {
                    ec2.authorizeSecurityGroupIngress(AuthorizeSecurityGroupIngressRequest.builder()
                            .groupId(sgId)
                            .ipPermissions(IpPermission.builder()
                                    .ipProtocol("tcp")
                                    .fromPort(22)
                                    .toPort(22)
                                    .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                                    .build())
                            .build());
                    ctx.check("EC2 AuthorizeSecurityGroupIngress", true);
                } catch (Exception e) {
                    ctx.check("EC2 AuthorizeSecurityGroupIngress", false, e);
                }

                // 15. Ingress rule is reflected in DescribeSecurityGroups
                try {
                    DescribeSecurityGroupsResponse resp = ec2.describeSecurityGroups(
                            DescribeSecurityGroupsRequest.builder().groupIds(sgId).build());
                    boolean hasSshRule = resp.securityGroups().get(0).ipPermissions().stream()
                            .anyMatch(p -> p.fromPort() != null && p.fromPort() == 22);
                    ctx.check("EC2 SecurityGroup ingress rule present", hasSshRule);
                } catch (Exception e) {
                    ctx.check("EC2 SecurityGroup ingress rule present", false, e);
                }
            }

            // ── Key Pair lifecycle ─────────────────────────────────────────────

            String keyName = "sdk-test-key";
            try {
                CreateKeyPairResponse resp = ec2.createKeyPair(CreateKeyPairRequest.builder()
                        .keyName(keyName).build());
                ctx.check("EC2 CreateKeyPair",
                        keyName.equals(resp.keyName())
                        && resp.keyPairId() != null
                        && resp.keyMaterial() != null
                        && !resp.keyMaterial().isEmpty());
            } catch (Exception e) {
                ctx.check("EC2 CreateKeyPair", false, e);
            }

            // 17. DescribeKeyPairs
            try {
                DescribeKeyPairsResponse resp = ec2.describeKeyPairs(DescribeKeyPairsRequest.builder()
                        .keyNames(keyName).build());
                ctx.check("EC2 DescribeKeyPairs",
                        resp.keyPairs().size() == 1 && keyName.equals(resp.keyPairs().get(0).keyName()));
            } catch (Exception e) {
                ctx.check("EC2 DescribeKeyPairs", false, e);
            }

            // 18. CreateKeyPair duplicate → error
            try {
                ec2.createKeyPair(CreateKeyPairRequest.builder().keyName(keyName).build());
                ctx.check("EC2 CreateKeyPair duplicate error", false);
            } catch (Ec2Exception e) {
                ctx.check("EC2 CreateKeyPair duplicate error",
                        "InvalidKeyPair.Duplicate".equals(e.awsErrorDetails().errorCode()));
            } catch (Exception e) {
                ctx.check("EC2 CreateKeyPair duplicate error", false, e);
            }

            // ── Internet Gateway lifecycle ─────────────────────────────────────

            String igwId;
            try {
                CreateInternetGatewayResponse resp = ec2.createInternetGateway(
                        CreateInternetGatewayRequest.builder().build());
                igwId = resp.internetGateway().internetGatewayId();
                ctx.check("EC2 CreateInternetGateway",
                        igwId != null && igwId.startsWith("igw-"));
            } catch (Exception e) {
                ctx.check("EC2 CreateInternetGateway", false, e);
                igwId = null;
            }

            if (igwId != null) {
                // 20. AttachInternetGateway
                try {
                    ec2.attachInternetGateway(AttachInternetGatewayRequest.builder()
                            .internetGatewayId(igwId)
                            .vpcId(vpcId)
                            .build());
                    ctx.check("EC2 AttachInternetGateway", true);
                } catch (Exception e) {
                    ctx.check("EC2 AttachInternetGateway", false, e);
                }

                // 21. DescribeInternetGateways — attachment reflected
                try {
                    DescribeInternetGatewaysResponse resp = ec2.describeInternetGateways(
                            DescribeInternetGatewaysRequest.builder()
                                    .internetGatewayIds(igwId).build());
                    boolean attached = resp.internetGateways().get(0).attachments().stream()
                            .anyMatch(a -> vpcId.equals(a.vpcId()));
                    ctx.check("EC2 DescribeInternetGateways attached", attached);
                } catch (Exception e) {
                    ctx.check("EC2 DescribeInternetGateways attached", false, e);
                }
            }

            // ── Route Table lifecycle ──────────────────────────────────────────

            String rtId;
            try {
                CreateRouteTableResponse resp = ec2.createRouteTable(CreateRouteTableRequest.builder()
                        .vpcId(vpcId).build());
                rtId = resp.routeTable().routeTableId();
                ctx.check("EC2 CreateRouteTable",
                        rtId != null && rtId.startsWith("rtb-")
                        && vpcId.equals(resp.routeTable().vpcId()));
            } catch (Exception e) {
                ctx.check("EC2 CreateRouteTable", false, e);
                rtId = null;
            }

            if (rtId != null && igwId != null) {
                // 23. CreateRoute
                try {
                    ec2.createRoute(CreateRouteRequest.builder()
                            .routeTableId(rtId)
                            .destinationCidrBlock("0.0.0.0/0")
                            .gatewayId(igwId)
                            .build());
                    ctx.check("EC2 CreateRoute", true);
                } catch (Exception e) {
                    ctx.check("EC2 CreateRoute", false, e);
                }
            }

            String rtbAssocId = null;
            if (rtId != null && subnetId != null) {
                // 24. AssociateRouteTable
                try {
                    AssociateRouteTableResponse resp = ec2.associateRouteTable(
                            AssociateRouteTableRequest.builder()
                                    .routeTableId(rtId)
                                    .subnetId(subnetId)
                                    .build());
                    rtbAssocId = resp.associationId();
                    ctx.check("EC2 AssociateRouteTable",
                            rtbAssocId != null && rtbAssocId.startsWith("rtbassoc-"));
                } catch (Exception e) {
                    ctx.check("EC2 AssociateRouteTable", false, e);
                }
            }

            // ── Elastic IPs ────────────────────────────────────────────────────

            String allocationId;
            try {
                AllocateAddressResponse resp = ec2.allocateAddress(AllocateAddressRequest.builder()
                        .domain(DomainType.VPC).build());
                allocationId = resp.allocationId();
                ctx.check("EC2 AllocateAddress",
                        allocationId != null && allocationId.startsWith("eipalloc-")
                        && resp.publicIp() != null);
            } catch (Exception e) {
                ctx.check("EC2 AllocateAddress", false, e);
                allocationId = null;
            }

            // 26. DescribeAddresses
            if (allocationId != null) {
                try {
                    DescribeAddressesResponse resp = ec2.describeAddresses(
                            DescribeAddressesRequest.builder()
                                    .allocationIds(allocationId).build());
                    ctx.check("EC2 DescribeAddresses",
                            resp.addresses().size() == 1
                            && allocationId.equals(resp.addresses().get(0).allocationId()));
                } catch (Exception e) {
                    ctx.check("EC2 DescribeAddresses", false, e);
                }
            }

            // ── Instance lifecycle ─────────────────────────────────────────────

            String instanceId;
            try {
                RunInstancesResponse resp = ec2.runInstances(RunInstancesRequest.builder()
                        .imageId("ami-0abcdef1234567890")
                        .instanceType(InstanceType.T2_MICRO)
                        .minCount(1)
                        .maxCount(1)
                        .keyName(keyName)
                        .subnetId(subnetId != null ? subnetId : null)
                        .securityGroupIds(sgId != null ? List.of(sgId) : List.of())
                        .build());
                instanceId = resp.instances().get(0).instanceId();
                Instance launched = resp.instances().get(0);
                ctx.check("EC2 RunInstances",
                        instanceId != null && instanceId.startsWith("i-")
                        && InstanceStateName.RUNNING.equals(launched.state().name())
                        && InstanceType.T2_MICRO.equals(launched.instanceType())
                        && keyName.equals(launched.keyName()));
            } catch (Exception e) {
                ctx.check("EC2 RunInstances", false, e);
                instanceId = null;
            }

            // 28. DescribeInstances by ID
            final String finalInstanceId = instanceId;
            if (instanceId != null) {
                try {
                    DescribeInstancesResponse resp = ec2.describeInstances(
                            DescribeInstancesRequest.builder().instanceIds(instanceId).build());
                    Instance found = resp.reservations().get(0).instances().get(0);
                    ctx.check("EC2 DescribeInstances by ID",
                            instanceId.equals(found.instanceId())
                            && InstanceStateName.RUNNING.equals(found.state().name()));
                } catch (Exception e) {
                    ctx.check("EC2 DescribeInstances by ID", false, e);
                }

                // 29. DescribeInstances filter by state
                try {
                    DescribeInstancesResponse resp = ec2.describeInstances(
                            DescribeInstancesRequest.builder()
                                    .filters(Filter.builder()
                                            .name("instance-state-name")
                                            .values("running")
                                            .build())
                                    .build());
                    boolean found = resp.reservations().stream()
                            .flatMap(r -> r.instances().stream())
                            .anyMatch(i -> finalInstanceId.equals(i.instanceId()));
                    ctx.check("EC2 DescribeInstances filter by state", found);
                } catch (Exception e) {
                    ctx.check("EC2 DescribeInstances filter by state", false, e);
                }

                // 30. DescribeInstanceStatus
                try {
                    DescribeInstanceStatusResponse resp = ec2.describeInstanceStatus(
                            DescribeInstanceStatusRequest.builder().instanceIds(instanceId).build());
                    ctx.check("EC2 DescribeInstanceStatus",
                            !resp.instanceStatuses().isEmpty()
                            && instanceId.equals(resp.instanceStatuses().get(0).instanceId()));
                } catch (Exception e) {
                    ctx.check("EC2 DescribeInstanceStatus", false, e);
                }

                // 31. AssociateAddress with instance
                if (allocationId != null) {
                    String assocId = null;
                    try {
                        AssociateAddressResponse resp = ec2.associateAddress(
                                AssociateAddressRequest.builder()
                                        .allocationId(allocationId)
                                        .instanceId(instanceId)
                                        .build());
                        assocId = resp.associationId();
                        ctx.check("EC2 AssociateAddress",
                                assocId != null && assocId.startsWith("eipassoc-"));
                    } catch (Exception e) {
                        ctx.check("EC2 AssociateAddress", false, e);
                    }

                    if (assocId != null) {
                        try {
                            ec2.disassociateAddress(DisassociateAddressRequest.builder()
                                    .associationId(assocId).build());
                            ctx.check("EC2 DisassociateAddress", true);
                        } catch (Exception e) {
                            ctx.check("EC2 DisassociateAddress", false, e);
                        }
                    }
                }

                // 32. StopInstances
                try {
                    StopInstancesResponse resp = ec2.stopInstances(StopInstancesRequest.builder()
                            .instanceIds(instanceId).build());
                    ctx.check("EC2 StopInstances",
                            resp.stoppingInstances().get(0).currentState().name()
                                    == InstanceStateName.STOPPED);
                } catch (Exception e) {
                    ctx.check("EC2 StopInstances", false, e);
                }

                // 33. StartInstances
                try {
                    StartInstancesResponse resp = ec2.startInstances(StartInstancesRequest.builder()
                            .instanceIds(instanceId).build());
                    ctx.check("EC2 StartInstances",
                            resp.startingInstances().get(0).currentState().name()
                                    == InstanceStateName.RUNNING);
                } catch (Exception e) {
                    ctx.check("EC2 StartInstances", false, e);
                }

                // 34. RebootInstances (no-op, returns true)
                try {
                    ec2.rebootInstances(RebootInstancesRequest.builder()
                            .instanceIds(instanceId).build());
                    ctx.check("EC2 RebootInstances", true);
                } catch (Exception e) {
                    ctx.check("EC2 RebootInstances", false, e);
                }
            }

            // 35. DescribeInstances non-existent ID → error
            try {
                ec2.describeInstances(DescribeInstancesRequest.builder()
                        .instanceIds("i-0000000000000dead").build());
                ctx.check("EC2 DescribeInstances NotFound error", false);
            } catch (Ec2Exception e) {
                ctx.check("EC2 DescribeInstances NotFound error",
                        "InvalidInstanceID.NotFound".equals(e.awsErrorDetails().errorCode()));
            } catch (Exception e) {
                ctx.check("EC2 DescribeInstances NotFound error", false, e);
            }

            // ── Tags ───────────────────────────────────────────────────────────

            if (instanceId != null) {
                // 36. CreateTags
                try {
                    ec2.createTags(CreateTagsRequest.builder()
                            .resources(instanceId)
                            .tags(Tag.builder().key("Name").value("sdk-test-instance").build())
                            .build());
                    ctx.check("EC2 CreateTags", true);
                } catch (Exception e) {
                    ctx.check("EC2 CreateTags", false, e);
                }

                // 37. DescribeInstances — tag reflected
                try {
                    DescribeInstancesResponse resp = ec2.describeInstances(
                            DescribeInstancesRequest.builder().instanceIds(instanceId).build());
                    boolean hasTag = resp.reservations().get(0).instances().get(0).tags().stream()
                            .anyMatch(t -> "Name".equals(t.key()) && "sdk-test-instance".equals(t.value()));
                    ctx.check("EC2 Tags reflected on instance", hasTag);
                } catch (Exception e) {
                    ctx.check("EC2 Tags reflected on instance", false, e);
                }
            }

            // ── Cleanup ────────────────────────────────────────────────────────

            // 38. TerminateInstances
            if (instanceId != null) {
                try {
                    TerminateInstancesResponse resp = ec2.terminateInstances(
                            TerminateInstancesRequest.builder().instanceIds(instanceId).build());
                    ctx.check("EC2 TerminateInstances",
                            resp.terminatingInstances().get(0).currentState().name()
                                    == InstanceStateName.TERMINATED);
                } catch (Exception e) {
                    ctx.check("EC2 TerminateInstances", false, e);
                }
            }

            if (allocationId != null) {
                try {
                    ec2.releaseAddress(ReleaseAddressRequest.builder()
                            .allocationId(allocationId).build());
                    ctx.check("EC2 ReleaseAddress", true);
                } catch (Exception e) {
                    ctx.check("EC2 ReleaseAddress", false, e);
                }
            }

            if (rtbAssocId != null) {
                try {
                    ec2.disassociateRouteTable(DisassociateRouteTableRequest.builder()
                            .associationId(rtbAssocId).build());
                    ctx.check("EC2 DisassociateRouteTable", true);
                } catch (Exception e) {
                    ctx.check("EC2 DisassociateRouteTable", false, e);
                }
            }

            if (igwId != null) {
                try {
                    ec2.detachInternetGateway(DetachInternetGatewayRequest.builder()
                            .internetGatewayId(igwId).vpcId(vpcId).build());
                    ec2.deleteInternetGateway(DeleteInternetGatewayRequest.builder()
                            .internetGatewayId(igwId).build());
                    ctx.check("EC2 DetachAndDeleteInternetGateway", true);
                } catch (Exception e) {
                    ctx.check("EC2 DetachAndDeleteInternetGateway", false, e);
                }
            }

            if (rtId != null) {
                try {
                    ec2.deleteRouteTable(DeleteRouteTableRequest.builder()
                            .routeTableId(rtId).build());
                    ctx.check("EC2 DeleteRouteTable", true);
                } catch (Exception e) {
                    ctx.check("EC2 DeleteRouteTable", false, e);
                }
            }

            if (subnetId != null) {
                try {
                    ec2.deleteSubnet(DeleteSubnetRequest.builder().subnetId(subnetId).build());
                    ctx.check("EC2 DeleteSubnet", true);
                } catch (Exception e) {
                    ctx.check("EC2 DeleteSubnet", false, e);
                }
            }

            if (sgId != null) {
                try {
                    ec2.deleteSecurityGroup(DeleteSecurityGroupRequest.builder()
                            .groupId(sgId).build());
                    ctx.check("EC2 DeleteSecurityGroup", true);
                } catch (Exception e) {
                    ctx.check("EC2 DeleteSecurityGroup", false, e);
                }
            }

            try {
                ec2.deleteKeyPair(DeleteKeyPairRequest.builder().keyName(keyName).build());
                ctx.check("EC2 DeleteKeyPair", true);
            } catch (Exception e) {
                ctx.check("EC2 DeleteKeyPair", false, e);
            }

            try {
                ec2.deleteVpc(DeleteVpcRequest.builder().vpcId(vpcId).build());
                ctx.check("EC2 DeleteVpc", true);
            } catch (Exception e) {
                ctx.check("EC2 DeleteVpc", false, e);
            }
        }
    }
}
