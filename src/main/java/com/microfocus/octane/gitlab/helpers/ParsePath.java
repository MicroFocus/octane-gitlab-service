package com.microfocus.octane.gitlab.helpers;


import com.microfocus.octane.gitlab.services.GitlabServices;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Branch;
import org.gitlab4j.api.models.Project;

import java.util.List;

public class ParsePath {
    private Project project;
    private String groups;
    private String displayName;
    private List<Branch> branches;
    private String currentBranch;
    private int id;
    private GitLabApi gitlabApi;
    private static final Logger log = LogManager.getLogger(ParsePath.class);

    private void init(String path, PathType pathType) {
        switch (pathType) {
            case PIPELINE:
                currentBranch = getLastPartOfPath(path);
                path = cutLastPartOfPath(path);
            case MULTI_BRUNCH:
                path = cutPipelinePrefix(path);
            case PROJECT:
                displayName = getLastPartOfPath(path);
                path = cutLastPartOfPath(path);
                groups = path;
        }
    }

    public ParsePath(String path, GitLabApi gitLabApi, PathType pathType) {
        gitlabApi = gitLabApi;
        init(path, pathType);
    }

    public ParsePath(Project project, GitLabApi gitLabApi) {
        this.project = project;
        gitlabApi = gitLabApi;
        this.groups = project.getNamespace().getFullPath();
        this.displayName = project.getName();
        this.id = project.getId();
    }

    public Boolean isMultiBranch() {
        return getBranches().size() > 1;
    }

    public String getFullPathOfProject() {
        return groups + "/" + displayName;
    }

    public String getFullPathOfProjectWithBranch() {
        return groups + "/" + displayName + "/" + getCurrentBranchOrDefault();
    }

    public String getFullPathOfPipelineWithBranch() {
        return "pipeline:" + groups + "/" + displayName + "/" + getCurrentBranchOrDefault();
    }

    public String getFullPathOfPipeline() {
        return "pipeline:" + groups + "/" + displayName;
    }

    public static String cutPipelinePrefix(String jobCiId) {
        return jobCiId.substring(jobCiId.indexOf(':') + 1);
    }

    public static String getLastPartOfPath(String s) {
        return s.substring(s.lastIndexOf("/") + 1);
    }

    public static String cutLastPartOfPath(String s) {
        return s.substring(0, s.lastIndexOf("/"));
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public List<Branch> getBranches() {
        if (branches == null) {
            try {
                branches = gitlabApi.getRepositoryApi().getBranches(this.getFullPathOfProject());
            } catch (GitLabApiException e) {
                log.error("failed while getting branches from "+this.getFullPathOfProject());
            }
        }
        return branches;
    }

    public int getId() {
        if (project == null) {
            try {
                this.project = gitlabApi.getProjectApi().getProject(this.getFullPathOfProject());
                this.id = project.getId();
            } catch (Exception e) {
                log.error("failed while getting project from "+this.getFullPathOfProject());
            }
        }
        return id;
    }

    public String getCurrentBranchOrDefault() {
        return currentBranch == null ? getBranches().get(0).getName() : currentBranch;
    }

    public String getCurrentBranch() {
        return currentBranch;
    }
}
