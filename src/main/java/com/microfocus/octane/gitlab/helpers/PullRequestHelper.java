package com.microfocus.octane.gitlab.helpers;

import com.hp.octane.integrations.OctaneSDK;
import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.scm.PullRequest;
import com.hp.octane.integrations.dto.scm.SCMCommit;
import com.hp.octane.integrations.dto.scm.SCMRepository;
import com.hp.octane.integrations.dto.scm.SCMType;
import com.hp.octane.integrations.services.pullrequestsandbranches.factory.PullRequestFetchParameters;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.models.Commit;
import org.gitlab4j.api.models.MergeRequest;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
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
                .setId(Integer.toString(mergeRequest.getIid()))
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

    public static void convertAndSendMergeRequestToOctane(MergeRequest mergeRequest, List<Commit> mrCommits,
                                                          String repoUrl, String destinationWS) {
        SCMRepository sourceScmRepository =
                PullRequestHelper.createGitScmRepository(repoUrl, mergeRequest.getSourceBranch());
        SCMRepository targetScmRepository =
                PullRequestHelper.createGitScmRepository(repoUrl, mergeRequest.getTargetBranch());

        List<SCMCommit> pullRequestCommits = convertMergeRequestCommits(mrCommits);

        PullRequest pullRequest = PullRequestHelper.createPullRequest(mergeRequest, sourceScmRepository,
                targetScmRepository, pullRequestCommits);

        PullRequestHelper.sendPullRequestToOctane(repoUrl, pullRequest, destinationWS);
    }

    public static List<SCMCommit> convertMergeRequestCommits(List<Commit> commits) {
        return commits.stream()
                .map(commit -> DTOFactory.getInstance().newDTO(SCMCommit.class)
                        .setRevId(commit.getId())
                        .setComment(commit.getMessage())
                        .setUser(commit.getCommitterName())
                        .setUserEmail(commit.getCommitterEmail())
                        .setTime(Objects.isNull(commit.getTimestamp()) ? null : commit.getTimestamp().getTime())
                        .setParentRevId(commit.getParentIds().isEmpty() ? null : commit.getParentIds().get(0)))
                .collect(Collectors.toList());
    }

}
