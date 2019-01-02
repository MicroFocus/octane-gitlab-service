package com.microfocus.octane.gitlab.services;

import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.general.CIJobsList;
import com.hp.octane.integrations.dto.pipelines.PipelineNode;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.helpers.GitLabApiWrapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Branch;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.ProjectHook;
import org.gitlab4j.api.models.Tag;
import org.springframework.beans.factory.annotation.Autowired;
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

@Component
@Scope("singleton")
public class GitlabServices {
    private static final Logger log = LogManager.getLogger(GitlabServices.class);
    private static final DTOFactory dtoFactory = DTOFactory.getInstance();
    private final ApplicationSettings applicationSettings;
    private final GitLabApiWrapper gitLabApiWrapper;
    private GitLabApi gitLabApi;

    @Autowired
    public GitlabServices(ApplicationSettings applicationSettings, GitLabApiWrapper gitLabApiWrapper) {
        this.applicationSettings = applicationSettings;
        this.gitLabApiWrapper = gitLabApiWrapper;
    }

    @PostConstruct
    private void init() throws MalformedURLException {
        URL serverBaseUrl = new URL(applicationSettings.getConfig().getServerBaseUrl());
        URL webhookListenerUrl = new URL(serverBaseUrl, "events");
        gitLabApi = gitLabApiWrapper.getGitLabApi();
        try {
            List<Project> projects = isCurrentUserAdmin() ? gitLabApi.getProjectApi().getProjects() : gitLabApi.getProjectApi().getOwnedProjects();
            for (Project project : projects) {
                try {
                    deleteWebHooks(webhookListenerUrl, project);
                    ProjectHook hook = new ProjectHook();
                    hook.setJobEvents(true);
                    hook.setPipelineEvents(true);
                    gitLabApi.getProjectApi().addHook(project.getId(), webhookListenerUrl.toString(), hook, false, "");
                } catch (GitLabApiException e) {
                    log.warn("Failed to create a GitLab web hook", e);
                }
            }
        } catch (GitLabApiException e) {
            log.warn("Failed to create GitLab web hooks", e);
        }
    }

    @PreDestroy
    private void stop() {
        try {
            log.info("Destroying GitLab webhooks ...");
            URL serverBaseUrl = new URL(applicationSettings.getConfig().getServerBaseUrl());
            URL webhookListenerUrl = new URL(serverBaseUrl, "events");
            List<Project> projects = isCurrentUserAdmin() ? gitLabApi.getProjectApi().getProjects() : gitLabApi.getProjectApi().getOwnedProjects();
            for (Project project : projects) {
                deleteWebHooks(webhookListenerUrl, project);
            }
        } catch (Exception e) {
            log.warn("Failed to destroy GitLab webhooks", e);
        }
    }

    private void deleteWebHooks(URL webhookListenerUrl, Project project) throws GitLabApiException {
        for (ProjectHook hook : gitLabApi.getProjectApi().getHooks(project.getId())) {
            if (hook.getUrl().equals(webhookListenerUrl.toString())) {
                try {
                    gitLabApi.getProjectApi().deleteHook(project.getId(), hook.getId());
                } catch (GitLabApiException e) {
                    log.warn("Failed to delete a GitLab web hook", e);
                }
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    private void validateEventsAPIAvailability() throws MalformedURLException {
        URL serverBaseUrl = new URL(applicationSettings.getConfig().getServerBaseUrl());
        URL webhookListenerUrl = new URL(serverBaseUrl, "events");
        final String warning = String.format("Error while accessing the '%s' endpoint. Note that this endpoint must be accessible by GitLab.", webhookListenerUrl.toString());
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
                    log.warn(warning);
                }
            } else {
                log.warn(warning);
            }
            response.close();
            httpclient.close();
        } catch (Exception e) {
            log.warn(warning, e);
        }
    }

    CIJobsList getJobList() {
        CIJobsList ciJobsList = dtoFactory.newDTO(CIJobsList.class);
        List<PipelineNode> list = new ArrayList<>();
        try {
            List<Project> projects = isCurrentUserAdmin() ? gitLabApi.getProjectApi().getProjects() : gitLabApi.getProjectApi().getMemberProjects();
            for (Project project : projects) {
                try {
                    for (Branch branch : gitLabApi.getRepositoryApi().getBranches(project.getId())) {
                        String buildId = project.getNamespace().getName() + "/" + project.getName() + "/" + branch.getName();
                        PipelineNode buildConf = dtoFactory.newDTO(PipelineNode.class)
                                .setJobCiId("pipeline:" + buildId)
                                .setName(buildId);
                        list.add(buildConf);
                    }
                } catch (Exception e) {
                    log.warn("Failed to add some branches to the job list", e);
                }

                try {
                    for (Tag tag : gitLabApi.getTagsApi().getTags(project.getId())) {
                        String buildId = project.getNamespace().getName() + "/" + project.getName() + "/" + tag.getName();
                        PipelineNode buildConf = dtoFactory.newDTO(PipelineNode.class)
                                .setJobCiId("pipeline:" + buildId)
                                .setName(buildId);
                        list.add(buildConf);
                    }
                } catch(Exception e) {
                    log.warn("Failed to add some tags to the job list", e);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to add some jobs to the job list", e);
        }

        ciJobsList.setJobs(list.toArray(new PipelineNode[list.size()]));
        return ciJobsList;
    }

    private Boolean isCurrentUserAdmin() throws GitLabApiException {
        return gitLabApi.getUserApi().getCurrentUser().getIsAdmin() != null && gitLabApi.getUserApi().getCurrentUser().getIsAdmin();
    }

    PipelineNode createStructure(String buildId) {
        String displayName = buildId.split("/")[2];
        return dtoFactory.newDTO(PipelineNode.class)
                .setJobCiId(buildId)
                .setName(displayName);
    }
}
