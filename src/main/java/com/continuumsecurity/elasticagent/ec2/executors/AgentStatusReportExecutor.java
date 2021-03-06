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

package com.continuumsecurity.elasticagent.ec2.executors;

import com.google.gson.JsonObject;

import com.continuumsecurity.elasticagent.ec2.PluginRequest;
import com.continuumsecurity.elasticagent.ec2.models.AgentStatusReport;
import com.continuumsecurity.elasticagent.ec2.models.ExceptionMessage;
import com.continuumsecurity.elasticagent.ec2.models.JobIdentifier;
import com.continuumsecurity.elasticagent.ec2.models.NotRunningAgentStatusReport;
import com.continuumsecurity.elasticagent.ec2.requests.AgentStatusReportRequest;
import com.continuumsecurity.elasticagent.ec2.views.ViewBuilder;
import com.thoughtworks.go.plugin.api.logging.Logger;
import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;

import freemarker.template.Template;
import freemarker.template.TemplateException;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;

import com.continuumsecurity.elasticagent.ec2.AgentInstance;
import com.continuumsecurity.elasticagent.ec2.Ec2Instance;

public class AgentStatusReportExecutor {
    private static final Logger LOG = Logger.getLoggerFor(AgentStatusReportExecutor.class);
    private final AgentStatusReportRequest request;
    private final PluginRequest pluginRequest;
    private final AgentInstance<Ec2Instance> agentInstances;
    private final ViewBuilder viewBuilder;

    public AgentStatusReportExecutor(AgentStatusReportRequest request, PluginRequest pluginRequest,
                                     AgentInstance<Ec2Instance> agentInstances, ViewBuilder viewBuilder) {
        this.request = request;
        this.pluginRequest = pluginRequest;
        this.agentInstances = agentInstances;
        this.viewBuilder = viewBuilder;
    }

    public GoPluginApiResponse execute() throws Exception {
        String elasticAgentId = request.getElasticAgentId();
        JobIdentifier jobIdentifier = request.getJobIdentifier();
        LOG.info(String.format("[status-report] Generating status report for agent: %s with job: %s", elasticAgentId, jobIdentifier));

        try {
            if (StringUtils.isNotBlank(elasticAgentId)) {
                return getStatusReportUsingElasticAgentId(elasticAgentId);
            }
            return getStatusReportUsingJobIdentifier(jobIdentifier);
        } catch (Exception e) {
            LOG.error("Exception while generating agent status report", e);
            final String statusReportView = viewBuilder.build(viewBuilder.getTemplate("error.template.ftlh"), new ExceptionMessage(e));
            return constructResponseForReport(statusReportView);
        }
    }

    private GoPluginApiResponse getStatusReportUsingJobIdentifier(JobIdentifier jobIdentifier) throws Exception {
        Ec2Instance agentInstance = agentInstances.find(jobIdentifier);
        if (agentInstance != null) {
            AgentStatusReport agentStatusReport = agentInstances.getAgentStatusReport(pluginRequest.getPluginSettings(), agentInstance);
            String statusReportView = viewBuilder.build(viewBuilder.getTemplate("agent-status-report.template.ftlh"), agentStatusReport);
            return constructResponseForReport(statusReportView);
        }
        return containerNotFoundApiResponse(jobIdentifier);
    }

    private GoPluginApiResponse getStatusReportUsingElasticAgentId(String elasticAgentId) throws Exception {
        Ec2Instance agentInstance = agentInstances.find(elasticAgentId);
        if (agentInstance != null) {
            AgentStatusReport agentStatusReport = agentInstances.getAgentStatusReport(pluginRequest.getPluginSettings(), agentInstance);
            String statusReportView = viewBuilder.build(viewBuilder.getTemplate("agent-status-report.template.ftlh"), agentStatusReport);
            return constructResponseForReport(statusReportView);
        }
        return containerNotFoundApiResponse(elasticAgentId);
    }

    private GoPluginApiResponse constructResponseForReport(String statusReportView) {
        JsonObject responseJSON = new JsonObject();
        responseJSON.addProperty("view", statusReportView);
        return DefaultGoPluginApiResponse.success(responseJSON.toString());
    }

    private GoPluginApiResponse containerNotFoundApiResponse(JobIdentifier jobIdentifier) throws IOException, TemplateException {
        Template template = viewBuilder.getTemplate("not-running-agent-status-report.template.ftlh");
        String statusReportView = viewBuilder.build(template, new NotRunningAgentStatusReport(jobIdentifier));
        return constructResponseForReport(statusReportView);
    }

    private GoPluginApiResponse containerNotFoundApiResponse(String elasticAgentId) throws IOException, TemplateException {
        Template template = viewBuilder.getTemplate("not-running-agent-status-report.template.ftlh");
        String statusReportView = viewBuilder.build(template, new NotRunningAgentStatusReport(elasticAgentId));
        return constructResponseForReport(statusReportView);
    }
}
