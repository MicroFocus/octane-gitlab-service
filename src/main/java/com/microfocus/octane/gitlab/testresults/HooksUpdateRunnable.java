package com.microfocus.octane.gitlab.testresults;

import com.microfocus.octane.gitlab.helpers.GitLabApiWrapper;
import com.microfocus.octane.gitlab.helpers.HooksHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.AccessLevel;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.ProjectFilter;
import org.gitlab4j.api.models.User;

import java.net.URL;
import java.util.*;


public class HooksUpdateRunnable implements Runnable {

    static public final int INTERVAL = 60;
    private final GitLabApiWrapper gitLabApiWrapper;
    private final User currentUser;
    GitLabApi gitLabApi;
    Date lastUpdateTime;
    private URL webhookURL;
    private long lastUpdatedProjectId = 0;
    static final Logger log = LogManager.getLogger(HooksUpdateRunnable.class);

    public HooksUpdateRunnable(GitLabApiWrapper gitLabApiWrapper, URL webhookURL, User currentUser) {

        this.gitLabApi = gitLabApiWrapper.getGitLabApi();
        this.gitLabApiWrapper = gitLabApiWrapper;
        this.lastUpdateTime = new Date(System.currentTimeMillis());
        this.webhookURL = webhookURL;
        this.currentUser = currentUser;
    }

    /*
    * This thread handles web-hooks.
    * The hook help to get events from GitLab. for example: running of pipeline, merge requests, etc...
    * Adding a hook for each project in order to support pull request flow and get the data from projects without Octane's pipeline
    * Every time the thread starts, it scans for new projects and adds a new hook to each one
    * */
    public void addHooksToNewProjects() {

        try {

            ProjectFilter filter = new ProjectFilter().withIdAfter(lastUpdatedProjectId).withMembership(true)
                    .withMinAccessLevel(AccessLevel.MAINTAINER);
            List<Project> projects = gitLabApi.getProjectApi().getProjects(filter);

            if (projects.size() > 0) {
                projects.stream().forEach(project -> {
                    try {
                        if (gitLabApiWrapper.isUserHasPermissionForProject(project, currentUser)) {
                            HooksHelper.addWebHookToProject(gitLabApi, webhookURL, project.getId(), true);
                        }
                    } catch (GitLabApiException e) {
                        log.warn("Failed to create GitLab web hooks", e);
                    }

                });

                long maxId = projects.stream()
                        .max(Comparator.comparing(Project::getId))
                        .orElseThrow(NoSuchElementException::new).getId();
                //update the index of the last updated project.
                lastUpdatedProjectId = Math.max(maxId, lastUpdatedProjectId);
            }

        } catch (GitLabApiException e) {
            log.error("Failed to get GitLab projects", e);
            if (lastUpdatedProjectId == 0) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void run() {
        log.info("Start scanning for new projects and adding a hook.Last Updated Project Id =" +lastUpdatedProjectId);
        addHooksToNewProjects();
    }
}
