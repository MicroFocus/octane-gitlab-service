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

package com.microfocus.octane.gitlab.services;

import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.events.MultiBranchType;
import com.hp.octane.integrations.dto.general.CIJobsList;
import com.hp.octane.integrations.dto.parameters.CIParameter;
import com.hp.octane.integrations.dto.parameters.CIParameterType;
import com.hp.octane.integrations.dto.pipelines.PipelineNode;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.helpers.*;
import com.microfocus.octane.gitlab.testresults.HooksUpdateRunnable;
import com.microfocus.octane.gitlab.testresults.TestResultsCleanUpRunnable;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.AccessLevel;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.ProjectFilter;
import org.gitlab4j.api.models.User;
import org.gitlab4j.api.models.Variable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Scope;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
@Scope("singleton")
public class GitlabServices {
    private static final Logger log = LogManager.getLogger(GitlabServices.class);
    private static final DTOFactory dtoFactory = DTOFactory.getInstance();
    private final ApplicationSettings applicationSettings;
    private final GitLabApiWrapper gitLabApiWrapper;
    private GitLabApi gitLabApi;
    private boolean cleanupOnly =false;
    private  ScheduledExecutorService testCleanupExecutor;
    private ScheduledFuture<?> testCleanupScheduledFuture;
    private  ScheduledExecutorService updateHooksExecutor;
    private ScheduledFuture<?> updateHooksScheduledFuture;


    private URL webhookURL;

    @Autowired
    public GitlabServices(ApplicationSettings applicationSettings, GitLabApiWrapper gitLabApiWrapper, ApplicationArguments applicationArguments ) {
        this.applicationSettings = applicationSettings;
        this.gitLabApiWrapper = gitLabApiWrapper;

        if(applicationArguments.containsOption("cleanupOnly") && (applicationArguments.getOptionValues("cleanupOnly").size()>0)){
            cleanupOnly = Boolean.parseBoolean(applicationArguments.getOptionValues("cleanupOnly").get(0));
        }
    }

    @PostConstruct
    private void init() throws MalformedURLException {
        //Adding webHooks
        initWebHookListenerURL();
        gitLabApi = gitLabApiWrapper.getGitLabApi();

        try {
            ProjectFilter filter = new ProjectFilter().withMembership(true)
                    .withMinAccessLevel(AccessLevel.MAINTAINER);

            List<Project> projects =  gitLabApi.getProjectApi().getProjects(filter);

            if(cleanupOnly){
                log.info("start with cleanup process");
                HooksHelper.deleteWebHooks(projects,webhookURL,gitLabApi);
            }else {

                //start hooks' update thread
                updateHooksExecutor =
                        Executors.newSingleThreadScheduledExecutor();
                updateHooksScheduledFuture = updateHooksExecutor.scheduleAtFixedRate(
                        new HooksUpdateRunnable(gitLabApiWrapper,webhookURL),0 , HooksUpdateRunnable.INTERVAL, TimeUnit.MINUTES);

            }
        } catch (GitLabApiException e) {
            log.warn("Failed to create GitLab web hooks", e);
            throw new RuntimeException(e);
        }
        if(!cleanupOnly) {
            //start test cleanUp thread
            testCleanupExecutor = Executors.newSingleThreadScheduledExecutor();
            testCleanupScheduledFuture = testCleanupExecutor.scheduleAtFixedRate(
                    new TestResultsCleanUpRunnable(applicationSettings.getConfig().getTestResultsOutputFolderPath()),
                    TestResultsCleanUpRunnable.INTERVAL, TestResultsCleanUpRunnable.INTERVAL, TimeUnit.MINUTES);
        }
    }

    private void initWebHookListenerURL() throws MalformedURLException {
        URL serverBaseUrl = (applicationSettings.getConfig().getServerWebhookRouteUrl() != null && !applicationSettings.getConfig().getServerWebhookRouteUrl().isEmpty())?
                new URL(applicationSettings.getConfig().getServerWebhookRouteUrl()) :
                new URL(applicationSettings.getConfig().getServerBaseUrl());

        webhookURL= new URL(serverBaseUrl, "events");
    }

    @PreDestroy
    private void stop() {
        try {
            if(!cleanupOnly) {
                stopExecutors();

                log.info("Destroying GitLab webhooks ...");

                ProjectFilter filter = new ProjectFilter().withMembership(true).withMinAccessLevel(AccessLevel.MAINTAINER);

                List<Project> projects = gitLabApi.getProjectApi().getProjects(filter);
                HooksHelper.deleteWebHooks(projects, webhookURL, gitLabApi);
            }

        } catch (Exception e) {
            log.warn("Failed to destroy GitLab webhooks", e);
        }
    }

    private void stopExecutors() {
        log.info("stop Executors");

        updateHooksScheduledFuture.cancel(true);
        updateHooksExecutor.shutdown();

        testCleanupScheduledFuture.cancel(true);
        testCleanupExecutor.shutdown();

    }

    @EventListener(ApplicationReadyEvent.class)
    private void validateEventsAPIAvailability() throws MalformedURLException {
        URL serverBaseUrl = new URL(applicationSettings.getConfig().getServerBaseUrl());
        URL webhookListenerUrl = new URL(serverBaseUrl, "events");
        String warning = String.format("Error while accessing the '%s' endpoint. Note that this endpoint must be accessible by GitLab.", webhookListenerUrl.toString());

        if(applicationSettings.getConfig().getServerWebhookRouteUrl()!=null){
            URL webhookRouteUrl = new URL(new URL(applicationSettings.getConfig().getServerWebhookRouteUrl()), "events");
            warning = String.format("Error while accessing the '%s' endpoint. Note that you should route '%s' to this endpoint, and this endpoint must be accessible",
                    webhookListenerUrl.toString(),webhookRouteUrl.toString());
        }

        try {
            CloseableHttpClient httpclient = HttpClients.createSystem();
            HttpGet httpGet = new HttpGet(webhookListenerUrl.toString());
            CloseableHttpResponse response = httpclient.execute(httpGet);
            if (response.getStatusLine().getStatusCode() == 200) {
                String responseStr = EntityUtils.toString(response.getEntity());
                if (responseStr.equals(com.microfocus.octane.gitlab.api.EventListener.LISTENING)) {
                    final String success = String.format("Success while accessing the '%s' endpoint.", webhookListenerUrl.toString());
                    log.info(success);
                } else {
                    log.error(warning);
                }
            } else {
                log.error(warning);
            }
            response.close();
            httpclient.close();
        } catch (Exception e) {
            log.error(warning, e);
        }
    }

    CIJobsList getJobList(boolean includeParameters, Long workspaceId) {
        CIJobsList ciJobsList = dtoFactory.newDTO(CIJobsList.class);
        List<PipelineNode> list = new ArrayList<>();
        String projectNames ="";
        try {
            ProjectFilter filter = new ProjectFilter();
            filter.withMembership(true).withMinAccessLevel(AccessLevel.MAINTAINER);
            List<Project> projectsFilters = gitLabApi.getProjectApi().getProjects(filter);
            log.info("There are only "+ projectsFilters.size() +" projects with access level => MAINTAINER for the integrated user");


            for (Project project : projectsFilters) {
                    try {
                        ParsedPath parseProject = new ParsedPath(project, gitLabApi);
                        PipelineNode buildConf;

                        buildConf = dtoFactory.newDTO(PipelineNode.class)
                                .setJobCiId(parseProject.getJobCiId(true))
                                .setName(project.getNameWithNamespace())
                                .setDefaultBranchName(project.getDefaultBranch())
                                .setMultiBranchType(MultiBranchType.MULTI_BRANCH_PARENT);

                        if(includeParameters){
                            buildConf.setParameters(getParameters(parseProject));
                        }
                        projectNames = projectNames + buildConf.getName()+",";
                        list.add(buildConf);
                    } catch (Exception e) {
                        log.warn("Failed to add some tags to the job list", e);
                    }
                }

        } catch (Exception e) {
            log.warn("Failed to add some jobs to the job list", e);
        }

        log.info("getJobList results:"+projectNames);
        ciJobsList.setJobs(list.toArray(new PipelineNode[list.size()]));
        return ciJobsList;
    }

    /*private Boolean isCurrentUserAdmin() throws GitLabApiException {
        return gitLabApi.getUserApi().getCurrentUser().getIsAdmin() != null && gitLabApi.getUserApi().getCurrentUser().getIsAdmin();
    }*/

    PipelineNode createStructure(String buildId, boolean isMultiBranchParent) {

        ParsedPath project = new ParsedPath(buildId, gitLabApi, isMultiBranchParent? PathType.MULTI_BRUNCH : PathType.PIPELINE);
        try {
            Project currentProject = gitLabApi.getProjectApi().getProject(project.getFullPathOfProject());
            HooksHelper.addWebHookToProject(gitLabApi,webhookURL,project.getFullPathOfProject(),true);
            return dtoFactory.newDTO(PipelineNode.class)
                    .setJobCiId(project.getJobCiId(isMultiBranchParent))
                    .setDefaultBranchName(currentProject.getDefaultBranch())
                    .setMultiBranchType(isMultiBranchParent ? MultiBranchType.MULTI_BRANCH_PARENT : MultiBranchType.MULTI_BRANCH_CHILD)
                    .setName(project.getNameWithNameSpaceForDisplayName())
                    .setParameters(getParameters(project));

        } catch (GitLabApiException e){
            if(e.getHttpStatus() != HttpStatus.SC_NOT_FOUND) {
                log.error("unable to update webhook when create a pipeline in Octane for project:"+ project.getDisplayName(),e);
            }
        }

        return null;
    }

    public List<CIParameter> getParameters(ParsedPath project) {
        List<CIParameter> parametersList = new ArrayList<>();
        List<Variable> projectVariables = VariablesHelper.getVariables(project,gitLabApi,applicationSettings.getConfig());

        projectVariables.forEach(var -> {
            CIParameter param = dtoFactory.newDTO(CIParameter.class);
            param.setType(CIParameterType.STRING);
            param.setName(var.getKey());
            param.setDefaultValue(var.getValue());
            parametersList.add(param);
        });

        return parametersList;
    }

    public boolean isCleanUpOnly() {
        return cleanupOnly;
    }

}
