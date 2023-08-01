/*******************************************************************************
 * Copyright 2017-2023 Open Text.
 *
 * The only warranties for products and services of Open Text and
 * its affiliates and licensors (“Open Text”) are as may be set forth
 * in the express warranty statements accompanying such products and services.
 * Nothing herein should be construed as constituting an additional warranty.
 * Open Text shall not be liable for technical or editorial errors or
 * omissions contained herein. The information contained herein is subject
 * to change without notice.
 *
 * Except as specifically indicated otherwise, this document contains
 * confidential information and a valid license is required for possession,
 * use or copying. If this work is provided to the U.S. Government,
 * consistent with FAR 12.211 and 12.212, Commercial Computer Software,
 * Computer Software Documentation, and Technical Data for Commercial Items are
 * licensed to the U.S. Government under vendor's standard commercial license.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *   http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.microfocus.octane.gitlab.model;

import com.microfocus.octane.gitlab.helpers.Pair;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import javax.annotation.PostConstruct;
import javax.validation.ValidationException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
public class ConfigStructure {

    @Value("${ciserver.identity:#{null}}")
    private String ciServerIdentity;

    @Value("${octane.location:#{null}}")
    private String octaneLocation;

    @Value("${octane.apiClientID:#{null}}")
    private String octaneApiClientID;

    @Value("${octane.apiClientSecret:#{null}}")
    private String octaneApiClientSecret;

    @Value("${gitlab.location:#{null}}")
    private String gitlabLocation;

    @Value("${gitlab.personalAccessToken:#{null}}")
    private String gitlabPersonalAccessToken;

    @Value("${gitlab.testResultsFilePattern:glob:**.xml}")
    private String gitlabTestResultsFilePattern;

    @Value("${gitlab.gherkinTestResultsFilePattern:#{null}}")
    private String gitlabGherkinTestResultsFilePattern;

    @Value("${gitlab.testResultsOutputFolderPath:#{null}}")
    private String testResultsOutputFolderPath;

    @Value("${server.webhook.route.url:#{null}}")
    private  String serverWebhookRouteUrl;

    @Value("${server.baseUrl:#{null}}")
    private String serverBaseUrl;

    @Value("${http.proxyUrl:#{null}}")
    private String httpProxyUrl;

    @Value("${http.proxyUser:#{null}}")
    private String httpProxyUser;

    @Value("${http.proxyPassword:#{null}}")
    private String httpProxyPassword;

    @Value("${http.nonProxyHosts:#{null}}")
    private String httpNonProxyHosts;

    @Value("${https.proxyUrl:#{null}}")
    private String httpsProxyUrl;

    @Value("${https.proxyUser:#{null}}")
    private String httpsProxyUser;

    @Value("${https.proxyPassword:#{null}}")
    private String httpsProxyPassword;

    @Value("${https.nonProxyHosts:#{null}}")
    private String httpsNonProxyHosts;

    @Value("${gitlab.variables.pipeline.usage:#{null}}")
    private String[] gitlabVariablesPipelineUsage;

    @Value("${gitlab.ci.service.can.run.pipeline:#{null}}")
    private String canRunPipeline = "true";

    @Value("${gitlab.mergeRequests.variables.publishMergeRequestVarName:publishMergeRequests}")
    private String publishMergeRequestsVariableName;

    @Value("${gitlab.mergeRequests.variables.destinationWorkspaceVarName:destinationWorkspace}")
    private String destinationWorkspaceVariableName;

    @Value("${gitlab.mergeRequests.variables.useSSHFormatVarName:useSSHFormat}")
    private String useSSHFormatVariableName;

    @Value("${gitlab.mergeRequests.mergeRequestHistoryFolderPath:projectHistory}")
    private String mergeRequestHistoryFolderPath;

    @Value("${gitlab.codeCoverage.variables.generatedCoverageReportFilePathVarName:jacocoReportPath}")
    private String generatedCoverageReportFilePathVariableName;

    @PostConstruct
    public void init() {
        List<Map.Entry<String, Supplier<String>>> mandatoryGetters = new ArrayList<>();
        mandatoryGetters.add(Pair.of("octane.location", this::getOctaneLocation));
        mandatoryGetters.add(Pair.of("octane.apiClientID", this::getOctaneApiClientID));
        mandatoryGetters.add(Pair.of("octane.apiClientSecret", this::getOctaneApiClientSecret));
        mandatoryGetters.add(Pair.of("gitlab.location", this::getGitlabLocation));
        mandatoryGetters.add(Pair.of("gitlab.personalAccessToken", this::getGitlabPersonalAccessToken));
        Set<String> validationErrors = new LinkedHashSet<>();
        mandatoryGetters.forEach(mg -> {
            if (mg.getValue().get() == null || mg.getValue().get().trim().isEmpty()) {
                validationErrors.add("Missing property " + mg.getKey());
            }
        });

        if (validationErrors.size() > 0) {
            AtomicInteger counter = new AtomicInteger(1);
            throw new ValidationException(validationErrors.stream().map(e -> (counter.getAndIncrement() + ": " + e)).collect(Collectors.joining("\n", "\n", "")));
        }
    }

    public String getServerBaseUrl() {
        return serverBaseUrl;
    }

    public String getServerWebhookRouteUrl(){
        return serverWebhookRouteUrl;
    }

    /**
     * Returns whether the GitLab service can run pipelines from Octane UI
     * This is defined by a parameter since we could not find any corresponding definition used by GitLab API.
     * @return true when the service is permitted to run pipelines from Octane UI, otherwise- return false.
     */
    public boolean canRunPipeline() {
        return Boolean.valueOf(canRunPipeline);
    }

    public List<String> getGitlabVariablesPipelineUsage(){
        if(gitlabVariablesPipelineUsage == null){
            return new ArrayList<>();
        }
        return Arrays.asList(gitlabVariablesPipelineUsage);
    }

    public String getCiServerIdentity() {
        String val = !isNullOrSpaceOrEmpty(ciServerIdentity) ? ciServerIdentity : Hex.encodeHexString(DigestUtils.md5Digest(serverBaseUrl.getBytes()));
        return val.substring(0, Math.min(255, val.length()));
    }

    private boolean isNullOrSpaceOrEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    public String getOctaneLocation() {
        return octaneLocation;
    }

    public String getOctaneApiClientID() {
        return octaneApiClientID;
    }

    public String getOctaneApiClientSecret() {
        return octaneApiClientSecret;
    }

    public String getGitlabLocation() {
        return gitlabLocation;
    }

    public String getGitlabPersonalAccessToken() {
        return gitlabPersonalAccessToken;
    }

    public String getGitlabTestResultsFilePattern() {
        return gitlabTestResultsFilePattern;
    }

    public String getGitlabGherkinTestResultsFilePattern(){
        return gitlabGherkinTestResultsFilePattern;
    }

    public String getTestResultsOutputFolderPath(){
       return testResultsOutputFolderPath;
    }
    public String getProxyField(String protocol, String fieldName) {
        Optional<Field> field = Arrays.stream(this.getClass().getDeclaredFields()).filter(f -> f.getName().toLowerCase().equals(protocol.concat(fieldName).toLowerCase())).findFirst();
        if (!field.isPresent()) {
            throw new IllegalArgumentException(String.format("%s.%s", protocol, fieldName));
        }
        try {
            Object value = field.get().get(this);
            return value != null ? value.toString() : null;
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(String.format("%s.%s field in not accessible", protocol, fieldName));
        }
    }

    public String getPublishMergeRequestsVariableName() {
        return publishMergeRequestsVariableName;
    }

    public String getDestinationWorkspaceVariableName() {
        return destinationWorkspaceVariableName;
    }

    public String getUseSSHFormatVariableName() {
        return useSSHFormatVariableName;
    }

    public String getMergeRequestHistoryFolderPath() {
        return mergeRequestHistoryFolderPath;
    }

    public String getGeneratedCoverageReportFilePathVariableName() {
        return generatedCoverageReportFilePathVariableName;
    }
}
