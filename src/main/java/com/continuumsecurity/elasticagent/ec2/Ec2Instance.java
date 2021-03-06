/*
 * Copyright 2017 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file incorporates changes by @continuumsecurity
 */

package com.continuumsecurity.elasticagent.ec2;

import com.continuumsecurity.elasticagent.ec2.models.JobIdentifier;
import com.continuumsecurity.elasticagent.ec2.requests.CreateAgentRequest;

import org.joda.time.DateTime;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import static com.continuumsecurity.elasticagent.ec2.Ec2Plugin.LOG;
import static org.apache.commons.codec.binary.Base64.encodeBase64String;

public class Ec2Instance {
    private final DateTime createdAt;
    private final Map<String, String> properties;
    private final String environment;
    private final JobIdentifier jobIdentifier;
    private String id;

    public Ec2Instance(String id, Date createdAt, Map<String, String> properties, String environment, JobIdentifier jobIdentifier) {
        this.id = id;
        this.createdAt = new DateTime(createdAt);
        this.properties = properties;
        this.environment = environment;
        this.jobIdentifier = jobIdentifier;
    }

    public String id() {
        return id;
    }

    public DateTime createdAt() {
        return createdAt;
    }

    public String environment() {
        return environment;
    }

    public Map<String, String> properties() {
        return properties;
    }

    public JobIdentifier jobIdentifier() {
        return jobIdentifier;
    }

    public static Ec2Instance create(CreateAgentRequest request, PluginSettings settings) {

        Ec2Client ec2 = createEc2Client(settings.getAwsAccessKeyId(), settings.getAwsSecretAccessKey(), settings.getAwsRegion());

        String userdata = "#!/bin/bash\n" +
                "echo \"GO_SERVER_URL=" + settings.getGoServerUrl() + "\" > /etc/default/go-agent\n" +
                "chown -R go:go /etc/default/go-agent\n" +
                "echo \"agent.auto.register.key=" + request.autoRegisterKey() + "\" > /usr/share/go-agent/config/autoregister.properties\n" +
                "echo \"agent.auto.register.hostname=EA_$(ec2-metadata --instance-id | cut -d \" \" -f 2)\" >> /usr/share/go-agent/config/autoregister.properties\n" +
                "echo \"agent.auto.register.elasticAgent.agentId=$(ec2-metadata --instance-id | cut -d \" \" -f 2)\" >> /usr/share/go-agent/config/autoregister.properties\n" +
                "echo \"agent.auto.register.elasticAgent.pluginId=" + Constants.PLUGIN_ID + "\" >> /usr/share/go-agent/config/autoregister.properties\n" +
                "chown -R go:go /usr/share/go-agent/\n" +
                "systemctl start go-agent.service\n";

        userdata += request.properties().get("ec2_user_data");

        List<String> items = Arrays.asList(request.properties().get("ec2_subnets").split("\\s*,\\s*"));
        // subnet is assigned randomly from all the subnets configured
        Collections.shuffle(items);

        boolean result = false;
        int i = 0;

        RunInstancesResponse response = null;
        // try create instance for each AZ if error
        while (!result && i < items.size()) {
            try {
                RunInstancesRequest runInstancesRequest = RunInstancesRequest.builder()
                        .imageId(request.properties().get("ec2_ami"))
                        .instanceType(InstanceType.fromValue(request.properties().get("ec2_instance_type")))
                        .maxCount(1)
                        .minCount(1)
                        .keyName(request.properties().get("ec2_key"))
                        .securityGroupIds(request.properties().get("ec2_sg"))
                        .subnetId(items.get(i))
                        .userData(encodeBase64String(userdata.getBytes()))
                        .build();

                response = ec2.runInstances(runInstancesRequest);
                result = true;

                LOG.info("Successfully created new instance " + response.instances().get(0).instanceId() + " in " + response.instances().get(0).subnetId());
            } catch (AwsServiceException | SdkClientException e) {
                LOG.error("Could not create instance", e);
                response = null;
            } finally {
                i++;
            }
        }

        if (i < items.size() && response != null) {
            Instance instance = response.instances().get(0);

            Tag tagName = Tag.builder()
                    .key("Name")
                    .value("GoCD EA "
                            + request.jobIdentifier().getPipelineName()
                            + "-" + request.jobIdentifier().getPipelineCounter().toString()
                            + "-" + request.jobIdentifier().getStageName()
                            + "-" + request.jobIdentifier().getJobName())
                    .build();
            Tag tagType = Tag.builder()
                    .key("Type")
                    .value(Constants.ELASTIC_AGENT_TAG)
                    .build();
            Tag tagPipelineName = Tag.builder()
                    .key("pipelineName")
                    .value(request.jobIdentifier().getPipelineName())
                    .build();
            Tag tagPipelineCounter = Tag.builder()
                    .key("pipelineCounter")
                    .value(request.jobIdentifier().getPipelineCounter().toString())
                    .build();
            Tag tagPipelineLabel = Tag.builder()
                    .key("pipelineLabel")
                    .value(request.jobIdentifier().getPipelineLabel())
                    .build();
            Tag tagStageName = Tag.builder()
                    .key("stageName")
                    .value(request.jobIdentifier().getStageName())
                    .build();
            Tag tagStageCounter = Tag.builder()
                    .key("stageCounter")
                    .value(request.jobIdentifier().getStageCounter())
                    .build();
            Tag tagJobName = Tag.builder()
                    .key("jobName")
                    .value(request.jobIdentifier().getJobName())
                    .build();
            Tag tagJobId = Tag.builder()
                    .key("jobId")
                    .value(request.jobIdentifier().getJobId().toString())
                    .build();
            Tag tagJsonJobIdentifier = Tag.builder()
                    .key("JsonJobIdentifier")
                    .value(request.jobIdentifier().toJson())
                    .build();
            Tag tagJsonProperties = Tag.builder()
                    .key("JsonProperties")
                    .value(request.propertiesToJson())
                    .build();

            CreateTagsRequest tag_request = CreateTagsRequest.builder()
                    .tags(
                            tagName,
                            tagType,
                            tagPipelineName,
                            tagPipelineCounter,
                            tagPipelineLabel,
                            tagStageName,
                            tagStageCounter,
                            tagJobName,
                            tagJobId,
                            tagJsonJobIdentifier,
                            tagJsonProperties
                    )
                    .resources(instance.instanceId())
                    .build();
            try {
                ec2.createTags(tag_request);

                LOG.info("Successfully assigned tags to the instance " + instance.instanceId());
            } catch (AwsServiceException | SdkClientException e) {
                LOG.error("Could not create tags for the instance", e);
            } finally {
                ec2.close();
            }

            return new Ec2Instance(instance.instanceId(), Date.from(instance.launchTime()), request.properties(), request.environment(), request.jobIdentifier());
        } else {
            LOG.error("Could not create instance in any provided subnet!");
        }

        return null;
    }

    public void terminate(PluginSettings settings) {

        Ec2Client ec2 = createEc2Client(settings.getAwsAccessKeyId(), settings.getAwsSecretAccessKey(), settings.getAwsRegion());

        TerminateInstancesRequest request = TerminateInstancesRequest.builder()
                .instanceIds(this.id).build();

        try {
            ec2.terminateInstances(request);

            LOG.info("Successfully terminated EC2 instance " + this.id + " in region " + settings.getAwsRegion());
        } catch (AwsServiceException | SdkClientException e) {
            LOG.error("Could not terminate instance", e);
            System.exit(1);
        } finally {
            ec2.close();
        }
    }

    private static Ec2Client createEc2Client(String awsAccessKeyId, String awsSecretAccessKey, Region region) {
        AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(awsAccessKeyId, awsSecretAccessKey);

        return Ec2Client.builder()
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Ec2Instance that = (Ec2Instance) o;

        return id != null ? id.equals(that.id) : that.id == null;
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }

}
