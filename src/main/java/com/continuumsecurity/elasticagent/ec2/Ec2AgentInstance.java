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

import com.continuumsecurity.elasticagent.ec2.models.AgentStatusReport;
import com.continuumsecurity.elasticagent.ec2.models.InstanceStatusReport;
import com.continuumsecurity.elasticagent.ec2.models.JobIdentifier;
import com.continuumsecurity.elasticagent.ec2.models.StatusReport;
import com.continuumsecurity.elasticagent.ec2.requests.CreateAgentRequest;

import org.joda.time.Period;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;

import static com.continuumsecurity.elasticagent.ec2.Ec2Plugin.LOG;

public class Ec2AgentInstance implements AgentInstance<Ec2Instance> {

    private final ConcurrentHashMap<String, Ec2Instance> instances = new ConcurrentHashMap<>();
    private List<JobIdentifier> jobsWaitingForAgentCreation = new ArrayList<>();
    private boolean refreshed;
    public Clock clock = Clock.DEFAULT;

    private final Semaphore semaphore = new Semaphore(0, true);

    @Override
    public Ec2Instance create(CreateAgentRequest request, PluginSettings settings) throws Exception {

        final Integer maxAllowedAgents = settings.getMaxElasticAgents();
        if (!jobsWaitingForAgentCreation.contains(request.jobIdentifier())) {
            jobsWaitingForAgentCreation.add(request.jobIdentifier());
        }
        synchronized (instances) {
            doWithLockOnSemaphore(new SetupSemaphore(maxAllowedAgents, instances, semaphore));
            if (semaphore.tryAcquire()) {
                Ec2Instance instance = Ec2Instance.create(request, settings);
                register(instance);
                jobsWaitingForAgentCreation.remove(request.jobIdentifier());
                return instance;
            } else {
                String maxLimitExceededMessage = String.format("The number of instances currently running is currently at the maximum permissible limit, \"%d\". Not creating more instances for jobs: %s.", instances.size(), jobsWaitingForAgentCreation.stream().map(JobIdentifier::getRepresentation)
                        .collect(Collectors.joining(", ")));
                LOG.warn(maxLimitExceededMessage);
                return null;
            }
        }
    }

    private void doWithLockOnSemaphore(Runnable runnable) {
        synchronized (semaphore) {
            runnable.run();
        }
    }

    @Override
    public void terminate(String agentId, PluginSettings settings) throws Exception {
        Ec2Instance instance = instances.get(agentId);
        if (instance != null) {
            instance.terminate(settings);
        } else {
            LOG.warn("Requested to terminate an instance that does not exist " + agentId);
        }

        doWithLockOnSemaphore(semaphore::release);

        synchronized (instances) {
            instances.remove(agentId);
        }
    }

    @Override
    public void terminateUnregisteredInstances(PluginSettings settings, Agents agents) throws Exception {

        Ec2AgentInstance toTerminate = unregisteredAfterTimeout(settings, agents);
        if (toTerminate.instances.isEmpty()) {
            return;
        }

        LOG.warn("Terminating instances that did not register " + toTerminate.instances.keySet());
        for (Ec2Instance instance : toTerminate.instances.values()) {
            terminate(instance.id(), settings);
        }
    }

    private Ec2AgentInstance unregisteredAfterTimeout(PluginSettings settings, Agents knownAgents) throws Exception {
        Period period = settings.getAutoRegisterPeriod();
        Ec2AgentInstance unregisteredContainers = new Ec2AgentInstance();

        for (String instanceId : instances.keySet()) {
            if (knownAgents.containsAgentWithId(instanceId)) {
                continue;
            }

            Ec2Instance instance = instances.get(instanceId);

            if (clock.now().isAfter(instance.createdAt().plus(period))) {
                unregisteredContainers.register(instance);
            }
        }
        return unregisteredContainers;
    }

    @Override
    public Agents instancesCreatedAfterTimeout(PluginSettings settings, Agents agents) {
        ArrayList<Agent> oldAgents = new ArrayList<>();
        for (Agent agent : agents.agents()) {
            Ec2Instance instance = instances.get(agent.elasticAgentId());
            if (instance == null) {
                continue;
            }

            if (clock.now().isAfter(instance.createdAt().plus(settings.getAutoRegisterPeriod()))) {
                oldAgents.add(agent);
            }
        }
        return new Agents(oldAgents);
    }

    @Override
    public void refreshAll(PluginRequest pluginRequest) throws Exception {
        if (!refreshed) {
            AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(
                    pluginRequest.getPluginSettings().getAwsAccessKeyId(),
                    pluginRequest.getPluginSettings().getAwsSecretAccessKey()
            );

            Ec2Client ec2 = Ec2Client.builder()
                    .region(pluginRequest.getPluginSettings().getAwsRegion())
                    .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
                    .build();

            DescribeInstancesResponse response = ec2.describeInstances(
                    DescribeInstancesRequest.builder()
                            .filters(
                                    Filter.builder()
                                            .name("instance-state-name")
                                            .values("pending", "running")
                                            .build(),
                                    Filter.builder()
                                            .name("tag:Type")
                                            .values(Constants.ELASTIC_AGENT_TAG)
                                            .build()
                            )
                            .build()
            );

            for (Reservation reservation : response.reservations()) {
                for (Instance instance : reservation.instances()) {
                    register(new Ec2Instance(instance.instanceId(),
                            Date.from(instance.launchTime()),
                            CreateAgentRequest.propertiesFromJson(getTag(instance.tags(), "JsonProperties")),
                            getTag(instance.tags(), "environment"),
                            JobIdentifier.fromJson(getTag(instance.tags(), "JsonJobIdentifier")))
                    );
                    LOG.debug("Refreshed instance " + instance.instanceId());
                }
            }
            refreshed = true;
        }
    }

    @Override
    public Ec2Instance find(String agentId) {
        return instances.get(agentId);
    }

    @Override
    public Ec2Instance find(JobIdentifier jobIdentifier) {
        return instances.values()
                .stream()
                .filter(x -> x.jobIdentifier().equals(jobIdentifier))
                .findFirst()
                .orElse(null);
    }

    @Override
    public StatusReport getStatusReport(PluginSettings pluginSettings) throws Exception {
        AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(
                pluginSettings.getAwsAccessKeyId(),
                pluginSettings.getAwsSecretAccessKey()
        );

        Ec2Client ec2 = Ec2Client.builder()
                .region(pluginSettings.getAwsRegion())
                .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
                .build();

        DescribeInstancesResponse response = ec2.describeInstances(
                DescribeInstancesRequest.builder()
                        .filters(
                                Filter.builder()
                                        .name("instance-state-name")
                                        .values("pending", "running", "shutting-down", "stopping", "stopped")
                                        .build(),
                                Filter.builder()
                                        .name("tag:Type")
                                        .values(Constants.ELASTIC_AGENT_TAG)
                                        .build()
                        )
                        .build()
        );

        List<InstanceStatusReport> instanceStatusReportList = new ArrayList<>();

        for (Reservation reservation : response.reservations()) {
            for (Instance instance : reservation.instances()) {
                instanceStatusReportList.add(new InstanceStatusReport(
                        instance.instanceId(),
                        instance.instanceTypeAsString(),
                        instance.imageId(),
                        instance.state().nameAsString(),
                        instance.privateIpAddress(),
                        Date.from(instance.launchTime()).getTime(),
                        extractPipelineNameFromTags(instance.tags())
                ));
            }
        }

        LOG.info("Status report " + instanceStatusReportList.size() + " instances");

        return new StatusReport(instanceStatusReportList.size(), instanceStatusReportList);
    }

    private String extractPipelineNameFromTags(List<Tag> tags) {
        for (Tag tag : tags) {
            if (tag.key().equals("pipelineName")) {
                return tag.value();
            }
        }
        return null;
    }

    @Override
    public AgentStatusReport getAgentStatusReport(PluginSettings pluginSettings, Ec2Instance agentInstance) {
        AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(
                pluginSettings.getAwsAccessKeyId(),
                pluginSettings.getAwsSecretAccessKey()
        );

        Ec2Client ec2 = Ec2Client.builder()
                .region(pluginSettings.getAwsRegion())
                .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
                .build();

        DescribeInstancesResponse response = ec2.describeInstances(
                DescribeInstancesRequest.builder()
                        .filters(
                                Filter.builder()
                                        .name("instance-id")
                                        .values(agentInstance.id())
                                        .build(),
                                Filter.builder()
                                        .name("tag:Type")
                                        .values(Constants.ELASTIC_AGENT_TAG)
                                        .build()
                        )
                        .build()
        );

        Instance instance = response.reservations().get(0).instances().get(0);

        return new AgentStatusReport(
                agentInstance.jobIdentifier(),
                instance,
                agentInstance.createdAt().getMillis()
        );
    }

    // used by tests
    public boolean hasInstance(String agentId) {
        return instances.containsKey(agentId);
    }

    private void register(Ec2Instance instance) {
        instances.put(instance.id(), instance);
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
