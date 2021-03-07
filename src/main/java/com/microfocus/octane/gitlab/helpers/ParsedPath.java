package com.microfocus.octane.gitlab.helpers;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Branch;
import org.gitlab4j.api.models.Project;

import java.util.List;

public class ParsedPath {
    private Project project;
    private String groups;
    private String displayName;
    private String pathWithNameSpace;
    private List<Branch> branches;
    private String currentBranch;
    private int id;
    private GitLabApi gitlabApi;
    private static final Logger log = LogManager.getLogger(ParsedPath.class);

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

    public ParsedPath(String path, GitLabApi gitLabApi, PathType pathType) {
        gitlabApi = gitLabApi;
        init(path, pathType);
    }

    public ParsedPath(Project project, GitLabApi gitLabApi) {
        this.project = project;
        gitlabApi = gitLabApi;
        this.groups = project.getNamespace().getFullPath();
        this.displayName = project.getName();
        this.pathWithNameSpace = project.getPathWithNamespace();
        this.id = project.getId();
    }

    public Boolean isMultiBranch() {
        List<Branch> branches = getBranches();
        return branches != null ? branches.size() > 1 : false;
    }

    public String getPathWithNameSpace() {
       // return groups + "/" + displayName;
       return (this.pathWithNameSpace!= null ? this.pathWithNameSpace : this.getFullPathOfProject());
    }

    public String getFullPathOfProject() {
        return groups + "/" + displayName;
    }

    public String getFullPathOfProjectWithBranch() {
        return getPathWithNameSpace() + "/" + getCurrentBranchOrDefault();
    }

    public String getFullPathOfPipelineWithBranch() {
        return getFullPathOfPipeline() + "/" + getCurrentBranchOrDefault();
    }

    public String getFullPathOfPipeline() {
        return "pipeline:" + getPathWithNameSpace();
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
                branches = gitlabApi.getRepositoryApi().getBranches( this.getPathWithNameSpace());
            } catch (GitLabApiException e) {
                log.error("failed while getting branches from " + this.getPathWithNameSpace(), e);
            }
        }
        return branches;
    }

    public int getId() {
        if (project == null) {
            try {
                this.project = gitlabApi.getProjectApi().getProject(this.getPathWithNameSpace());
                this.id = project.getId();
            } catch (Exception e) {
                log.error("failed while getting project from " + this.getPathWithNameSpace());
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
