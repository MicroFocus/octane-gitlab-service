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

package com.microfocus.octane.gitlab.helpers;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Branch;
import org.gitlab4j.api.models.Project;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

public class ParsedPath {

    public static final String BRANCH_WITH_SLASH_SEPARATOR = "/~~/";
    public static final String PIPELINE_JOB_CI_ID_PREFIX = "pipeline:";

    private Project project;
    private String groups;
    private String displayName;
    private String pathWithNameSpace;
    private List<Branch> branches;
    private String currentBranch;
    private              long      id;
    private final        GitLabApi gitlabApi;
    private static final Logger    log = LogManager.getLogger(ParsedPath.class);
    private String nameWithNameSpaceForDisplayName;

    public static List<String> getGroupFullPathFromProject(String fullPathOfProject) {
        List<String> groupFullPath = new ArrayList<>();

        while (!fullPathOfProject.isEmpty()){
            try{
                int index = fullPathOfProject.lastIndexOf("/");
                if(index >=0){
                    fullPathOfProject = fullPathOfProject.substring(0,fullPathOfProject.lastIndexOf("/"));
                    groupFullPath.add(fullPathOfProject);
                } else{
                    break;
                }
            } catch (StringIndexOutOfBoundsException e){
                break;
            }

        }
        return groupFullPath;
    }

    private void init(String path, PathType pathType) {

        String branchSuffix = "";
        if(path.contains(BRANCH_WITH_SLASH_SEPARATOR)){
            branchSuffix = path.substring(path.lastIndexOf(BRANCH_WITH_SLASH_SEPARATOR)+(BRANCH_WITH_SLASH_SEPARATOR.length()-1));
            path = path.substring(0,path.lastIndexOf(BRANCH_WITH_SLASH_SEPARATOR));
        }

        int count = (int) path.chars().filter(p -> p == '/').count();
        switch (pathType) {
            case PIPELINE:
                if(count > 1) {
                    currentBranch = getLastPartOfPath(path) +branchSuffix;
                    path = cutLastPartOfPath(path);
                }
            case MULTI_BRUNCH:
                path = cutPipelinePrefix(path);
            case PROJECT:
                displayName = getLastPartOfPath(path);
                path = cutLastPartOfPath(path);
                groups = path;
                if(count == 1) {
                    currentBranch = getDefaultBranch();
                }
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
        this.nameWithNameSpaceForDisplayName = project.getNameWithNamespace();
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

    public static String cutPipelinePrefix(String jobCiId) {
        return jobCiId.substring(jobCiId.indexOf(':') + 1);
    }

    public static String getLastPartOfPath(String s) {
        return s.substring(s.lastIndexOf("/") + 1);
    }

    public static String cutLastPartOfPath(String s) {
        return s.substring(0, s.lastIndexOf("/"));
    }

    public static String cutBranchFromPath(String s) {
        if(s.contains(BRANCH_WITH_SLASH_SEPARATOR)){
            s= s.substring(0,s.lastIndexOf(BRANCH_WITH_SLASH_SEPARATOR));
        }
        return s.substring(0, s.lastIndexOf("/"));
    }

    public static String convertBranchName(String branchName){

        if(branchName!=null && branchName.contains("/")){
            branchName = branchName.replaceFirst("/", ParsedPath.BRANCH_WITH_SLASH_SEPARATOR);
        }
        return branchName;
    }

    public String getJobCiId(boolean isMultiBranchRoot){
        String jobCiId = (PIPELINE_JOB_CI_ID_PREFIX + getPathWithNameSpace()).toLowerCase();
        if(isMultiBranchRoot){
            return jobCiId;
        } else{
            return jobCiId+"/" + getCurrentBranchOrDefault();
        }
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
                if (log.isDebugEnabled()) {
                    log.debug("failed while getting branches from " + this.getPathWithNameSpace(), e);
                } else {
                    log.warn("failed while getting branches from " + this.getPathWithNameSpace());
                }
            }
        }
        return branches;
    }

    public long getId() {
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
    public String getNameWithNameSpaceForDisplayName(){
        if (project == null) {
            try {
                this.project = gitlabApi.getProjectApi().getProject(this.getPathWithNameSpace());
            } catch (Exception e) {
                log.warn("failed while getting project from " + this.getPathWithNameSpace());
                return null;
            }
        }
        this.nameWithNameSpaceForDisplayName = project.getNameWithNamespace();
        return this.nameWithNameSpaceForDisplayName;
    }

    public String getCurrentBranchOrDefault() {
        if(currentBranch!= null)
            return currentBranch;

        if(!getBranches().isEmpty()){
            return getDefaultBranch();
        }else{
             throw new ArrayIndexOutOfBoundsException ("there is not branches for this project, the project is empty:"+this.displayName);
        }

    }

    public String getCurrentBranch() {
        return currentBranch;
    }

    @NotNull
    public String getDefaultBranch() {
        List<Branch> branches = getBranches();
        return branches.stream()
                .filter(Branch::getDefault)
                .findAny().map(Branch::getName)
                .orElse(null);
    }

}
