package com.microfocus.octane.gitlab.helpers;

import org.gitlab4j.api.GitLabApi;

public class Utils {
    public static String cutPipelinePrefix(String jobCiId) {
        return jobCiId.substring(jobCiId.indexOf(':') + 1);
    }

    public static String getPipelineDisplayName(String jobCiId) {
        String pipelinePath = cutPipelinePrefix(jobCiId);
        return getLastPartOfPath(pipelinePath);
    }

    public static Boolean isMultiBranch(String pipelinePath, GitLabApi gitLabApi) {
        try {
            return gitLabApi.getRepositoryApi().getBranches(pipelinePath).size() > 1;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    public static Boolean isMultiBranch(int projectId, GitLabApi gitLabApi) {
        try {
            return gitLabApi.getRepositoryApi().getBranches(projectId).size() > 1;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    public static String getLastPartOfPath(String s) {
        return s.substring(s.lastIndexOf("/") + 1);
    }
}
