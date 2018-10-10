package com.microfocus.octane.gitlab.services;

import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.general.CIJobsList;
import com.hp.octane.integrations.dto.pipelines.PipelineNode;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.helpers.GitLabApiWrapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Branch;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.ProjectHook;
import org.gitlab4j.api.models.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
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
    private void initFactory() throws MalformedURLException {
        URL serverBaseUrl = new URL(applicationSettings.getConfig().getServerBaseUrl());
        URL webhookListenerUrl = new URL(serverBaseUrl, "events");
        gitLabApi = gitLabApiWrapper.getGitLabApi();
        try {
            List<Project> projects = isCurrentUserAdmin() ? gitLabApi.getProjectApi().getProjects() : gitLabApi.getProjectApi().getMemberProjects();
            for (Project project : projects) {
                try {
                    for (ProjectHook hook : gitLabApi.getProjectApi().getHooks(project.getId())) {
                        if (hook.getUrl().equals(webhookListenerUrl.toString())) {
                            try {
                                gitLabApi.getProjectApi().deleteHook(project.getId(), hook.getId());
                            } catch (GitLabApiException e) {
                                log.catching(e);
                            }
                        }
                    }
                    ProjectHook hook = new ProjectHook();
                    hook.setJobEvents(true);
                    hook.setPipelineEvents(true);
                    gitLabApi.getProjectApi().addHook(project.getId(), webhookListenerUrl.toString(), hook, false, "");
                } catch (GitLabApiException e) {
                    log.catching(e);
                }
            }
        } catch (GitLabApiException e) {
            log.catching(e);
        }
    }

    CIJobsList gotJobList() {
        CIJobsList ciJobsList = dtoFactory.newDTO(CIJobsList.class);
        List<PipelineNode> list = new ArrayList<>();
        try {
            List<Project> projects = isCurrentUserAdmin() ? gitLabApi.getProjectApi().getProjects() : gitLabApi.getProjectApi().getMemberProjects();
            for (Project project : projects) {
                for (Branch branch : gitLabApi.getRepositoryApi().getBranches(project.getId())) {
                    String buildId = project.getNamespace().getName() + "/" + project.getName() + "/" + branch.getName();
                    PipelineNode buildConf = dtoFactory.newDTO(PipelineNode.class)
                            .setJobCiId("pipeline:" + buildId)
                            .setName(buildId);
                    list.add(buildConf);
                }
                for (Tag tag : gitLabApi.getTagsApi().getTags(project.getId())) {
                    String buildId = project.getNamespace().getName() + "/" + project.getName() + "/" + tag.getName();
                    PipelineNode buildConf = dtoFactory.newDTO(PipelineNode.class)
                            .setJobCiId("pipeline:" + buildId)
                            .setName(buildId);
                    list.add(buildConf);
                }
            }
        } catch (Exception e) {
            log.catching(e);
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
