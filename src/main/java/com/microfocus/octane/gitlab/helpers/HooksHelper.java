package com.microfocus.octane.gitlab.helpers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.ProjectHook;
import org.gitlab4j.api.models.User;

import java.net.URL;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;


public final class HooksHelper {

    static final Logger log = LogManager.getLogger(HooksHelper.class);

    public static Boolean addWebHookToProject(GitLabApi gitLabApi,URL webhookURL, Object projectId, boolean deleteOldWebHook) throws GitLabApiException {

        try {
            if(deleteOldWebHook){
                deleteWebHooks(gitLabApi,webhookURL,projectId);
            }
            ProjectHook hook = new ProjectHook();
            hook.setJobEvents(true);
            hook.setPipelineEvents(true);
            hook.setMergeRequestsEvents(true);

            gitLabApi.getProjectApi().addHook(projectId, webhookURL.toString(), hook, false, generateNewToken());
        } catch (GitLabApiException e){
            log.warn("Failed to add web hooks to project: "+projectId, e);
            throw e;
        }

            return true;
    }


    public static void deleteWebHooks(List<Project> projects,URL webhookURL,GitLabApiWrapper gitLabApiWrapper,GitLabApi gitLabApi, User currentUser) throws GitLabApiException {
        for (Project project : projects) {
            if (gitLabApiWrapper.isUserHasPermissionForProject(project, currentUser)) {
                HooksHelper.deleteWebHooks(gitLabApi,webhookURL,project.getId());
            }
        }
    }
    public static void deleteWebHooks(GitLabApi gitLabApi,URL webhookURL, Object projectIdOrPath) throws GitLabApiException {
        for (ProjectHook hook : gitLabApi.getProjectApi().getHooks(projectIdOrPath)) {
            if (hook.getUrl().equals(webhookURL.toString())) {
                try {
                    gitLabApi.getProjectApi().deleteHook(projectIdOrPath, hook.getId());
                } catch (GitLabApiException e) {
                    log.warn("Failed to delete a GitLab web hook", e);
                }
            }
        }
    }

    private static String generateNewToken() {
        final SecureRandom secureRandom = new SecureRandom();
        final Base64.Encoder base64Encoder = Base64.getUrlEncoder();
        byte[] randomBytes = new byte[24];
        secureRandom.nextBytes(randomBytes);
        return base64Encoder.encodeToString(randomBytes);
    }
}
