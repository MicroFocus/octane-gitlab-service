package com.microfocus.octane.gitlab.helpers;

import com.microfocus.octane.gitlab.model.ConfigStructure;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import org.gitlab4j.api.GitLabApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
@Scope("singleton")
public class GitLabApiWrapper {

    @Autowired
    private ApplicationSettings applicationSettings;
    private GitLabApi gitLabApi;

    @PostConstruct
    public void initGitlabApiWrapper() {
        ConfigStructure config = applicationSettings.getConfig();
        gitLabApi = new GitLabApi(config.getGitlabLocation(), config.getGitlabPrivateToken());
    }

    public GitLabApi getGitLabApi() {
        return gitLabApi;
    }

}
