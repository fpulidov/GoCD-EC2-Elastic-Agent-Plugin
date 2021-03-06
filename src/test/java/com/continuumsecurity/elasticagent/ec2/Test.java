package com.continuumsecurity.elasticagent.ec2;

import com.continuumsecurity.elasticagent.ec2.models.JobIdentifier;
import com.continuumsecurity.elasticagent.ec2.requests.CreateAgentRequest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;

public class Test {

    public static void main(String[] args) {

        List<String> items = Arrays.asList(Properties.SUBNETS.split("\\s*,\\s*"));
        System.out.println(items.get(new Random().nextInt(items.size())));

        Map<String, String> properties = new HashMap<>();
        properties.put("ec2_ami", Properties.AMI_ID);
        properties.put("ec2_instance_type", Properties.TYPE);
        properties.put("ec2_sg", Properties.SG_IDS);
        properties.put("ec2_subnet", Properties.SUBNETS);
        properties.put("ec2_key", Properties.KEY);
        properties.put("ec2_user_data", Properties.USERDATA);


        JobIdentifier jobIdentifier = new JobIdentifier(
                "examplePipeName",
                45L,
                "examplePipeLabel",
                "exampleStageName",
                "stage-6",
                "exampleJobName",
                68L
        );

        CreateAgentRequest createAgentRequest = new CreateAgentRequest(
                Properties.AUTO_REGISTER_KEY,
                properties,
                "aws",
                jobIdentifier
        );

        PluginSettings pluginSettings = new PluginSettings();
        pluginSettings.setGoServerUrl(Properties.SERVER_URL);
        pluginSettings.setAutoRegisterTimeout(Properties.AUTO_REGISTER_TIMEOUT);
        pluginSettings.setMaxElasticAgents(Properties.MAX_ELASTIC_AGENTS);
        pluginSettings.setAwsAccessKeyId(Properties.ACCESS_KEY_ID);
        pluginSettings.setAwsSecretAccessKey(Properties.SECRET_ACCESS_KEY);
        pluginSettings.setAwsRegion(Properties.REGION);

        Ec2Instance ec2Instance = Ec2Instance.create(createAgentRequest,pluginSettings);
        //*System.out.println(ec2Instance.toString());

        ConcurrentHashMap<String, Ec2Instance> instances = new ConcurrentHashMap<>();
        instances.put(ec2Instance.id(), ec2Instance);

        System.out.println(instances.get(ec2Instance.id()));
        System.out.println("");

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {}



        AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(Properties.ACCESS_KEY_ID, Properties.SECRET_ACCESS_KEY);
        Ec2Client ec2 = Ec2Client.builder()
                .region(Region.EU_WEST_1)
                .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
                .build();

        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .filters(
                        Filter.builder()
                                .name("instance-state-name")
                                .values("running","pending","terminated")
                                .build(),
                        Filter.builder()
                                .name("tag:Type")
                                .values(Constants.ELASTIC_AGENT_TAG)
                                .build()
                )
                .build();

        DescribeInstancesResponse response = ec2.describeInstances(request);
        int count = 0;
        for(Reservation reservation : response.reservations()) {
            count += reservation.instances().size();
            for(Instance instance : reservation.instances()) {
                System.out.printf(
                        "Found reservation with id %s, " +
                                "AMI %s, " +
                                "type %s, " +
                                "state %s",
                        instance.instanceId(),
                        instance.imageId(),
                        instance.instanceType(),
                        instance.state().name()
                );
                System.out.println("TAGS:");
                for (Tag tag : instance.tags()) {
                    System.out.println(tag.toString());
                }
                Map<String, String> pr = CreateAgentRequest.propertiesFromJson(getTag(instance.tags(), "JsonProperties"));
                JobIdentifier ji = JobIdentifier.fromJson(getTag(instance.tags(),"JsonJobIdentifier"));
                System.out.println("");
            }
        }
        System.out.println(count+" instances found");

        ec2Instance.terminate(pluginSettings);

    }

    @Nullable
    private static String getTag(List<Tag> tags, String key) {
        for (Tag tag : tags) {
            if (tag.key().equals(key)) {
                return tag.value();
            }
        }
        return null;
    }

}
