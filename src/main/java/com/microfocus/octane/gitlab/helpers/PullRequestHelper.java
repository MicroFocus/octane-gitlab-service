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

import com.hp.octane.integrations.OctaneSDK;
import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.scm.PullRequest;
import com.hp.octane.integrations.dto.scm.SCMChange;
import com.hp.octane.integrations.dto.scm.SCMCommit;
import com.hp.octane.integrations.dto.scm.SCMRepository;
import com.hp.octane.integrations.dto.scm.SCMType;
import com.hp.octane.integrations.services.entities.QueryHelper;
import com.hp.octane.integrations.services.pullrequestsandbranches.bitbucketserver.pojo.EntityCollection;
import com.hp.octane.integrations.services.pullrequestsandbranches.factory.PullRequestFetchParameters;
import com.hp.octane.integrations.uft.items.OctaneStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Commit;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class PullRequestHelper {

    private static final Logger log = LogManager.getLogger(PullRequestHelper.class);
    private static final DTOFactory dtoFactory = DTOFactory.getInstance();

    public static SCMRepository createGitScmRepository(String repoUrl, String branch) {
        return dtoFactory.newDTO(SCMRepository.class)
                .setUrl(repoUrl)
                .setBranch(branch)
                .setType(SCMType.GIT);
    }

    public static PullRequest createPullRequest(MergeRequest mergeRequest, SCMRepository sourceScmRepository,
                                                SCMRepository targetScmRepository,
                                                List<SCMCommit> mergeRequestCommits) {
        return DTOFactory.getInstance().newDTO(PullRequest.class)
                .setId(Long.toString(mergeRequest.getIid()))
                .setTitle(mergeRequest.getTitle())
                .setDescription(mergeRequest.getDescription())
                .setState(mergeRequest.getState())
                .setCreatedTime(
                        Objects.isNull(mergeRequest.getCreatedAt()) ? null : mergeRequest.getCreatedAt().getTime())
                .setUpdatedTime(
                        Objects.isNull(mergeRequest.getUpdatedAt()) ? null : mergeRequest.getUpdatedAt().getTime())
                .setMergedTime(Objects.isNull(mergeRequest.getMergedAt()) ? null : mergeRequest.getMergedAt().getTime())
                .setIsMerged(Objects.nonNull(mergeRequest.getMergedAt()))
                .setAuthorName(Objects.isNull(mergeRequest.getAuthor()) ? null : mergeRequest.getAuthor().getName())
                .setAuthorEmail(Objects.isNull(mergeRequest.getAuthor()) ? null : mergeRequest.getAuthor().getEmail())
                .setClosedTime(Objects.isNull(mergeRequest.getClosedAt()) ? null : mergeRequest.getClosedAt().getTime())
                .setSelfUrl(mergeRequest.getWebUrl())
                .setSourceRepository(sourceScmRepository)
                .setTargetRepository(targetScmRepository)
                .setCommits(mergeRequestCommits);
    }

    public static void sendPullRequestToOctane(String repoUrl, PullRequest pullRequest, String destinationWorkspace) {
        PullRequestFetchParameters pullRequestFetchParameters = new PullRequestFetchParameters()
                .setRepoUrl(repoUrl);

        OctaneSDK.getClients().forEach(client -> {
            try {
                client.getPullRequestAndBranchService()
                        .sendPullRequests(Collections.singletonList(pullRequest), destinationWorkspace,
                                pullRequestFetchParameters, log::info);
            } catch (IOException e) {
                log.error(e.getMessage(), e);
            }
        });
    }

    public static void convertAndSendMergeRequestToOctane(MergeRequest mergeRequest, List<Commit> mrCommits, Map<String,
            List<Diff>> mrCommitDiffs, String repoUrl, String destinationWS) {
        SCMRepository sourceScmRepository =
                PullRequestHelper.createGitScmRepository(repoUrl, mergeRequest.getSourceBranch());
        SCMRepository targetScmRepository =
                PullRequestHelper.createGitScmRepository(repoUrl, mergeRequest.getTargetBranch());

        List<SCMCommit> pullRequestCommits = convertMergeRequestCommits(mrCommits, mrCommitDiffs);

        PullRequest pullRequest = PullRequestHelper.createPullRequest(mergeRequest, sourceScmRepository,
                targetScmRepository, pullRequestCommits);

        sendPullRequestToOctane(repoUrl, pullRequest, destinationWS);
    }

    public static List<SCMCommit> convertMergeRequestCommits(List<Commit> commits, Map<String, List<Diff>> commitDiffs) {
        return commits.stream()
                .map(commit -> {
                    SCMCommit cm = dtoFactory.newDTO(SCMCommit.class);
                    cm.setTime(commit.getTimestamp() != null ? commit.getTimestamp().getTime() : new Date().getTime());
                    cm.setUser(commit.getCommitterName());
                    cm.setUserEmail(commit.getCommitterEmail());
                    cm.setRevId(commit.getId());
                    cm.setParentRevId(Objects.isNull(commit.getParentIds())
                            ? null
                            : (commit.getParentIds().isEmpty() ? null : commit.getParentIds().get(0)));
                    cm.setComment(commit.getMessage());

                    List<Diff> diffs = commitDiffs.get(commit.getId()) != null
                            ? commitDiffs.get(commit.getId())
                            : new ArrayList<>();

                    List<SCMChange> changes = new ArrayList<>();
                    diffs.forEach(d -> {
                        SCMChange change = dtoFactory.newDTO(SCMChange.class);
                        change.setFile(d.getNewPath());
                        change.setType(d.getNewFile() ? "add" : d.getDeletedFile() ? "delete" : "edit");
                        changes.add(change);
                    });

                    cm.setChanges(changes);
                    return cm;
                })
//                DTOFactory.getInstance().newDTO(SCMCommit.class)
//                        .setRevId(commit.getId())
//                        .setComment(commit.getMessage())
//                        .setUser(commit.getCommitterName())
//                        .setUserEmail(commit.getCommitterEmail())
//                        .setTime(Objects.isNull(commit.getTimestamp()) ? null : commit.getTimestamp().getTime())
//                        .setParentRevId(commit.getParentIds().isEmpty() ? null : commit.getParentIds().get(0)))
                .collect(Collectors.toList());
    }

}
