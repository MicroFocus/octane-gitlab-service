package com.microfocus.octane.gitlab.api;

import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.helpers.GitLabApiWrapper;
import com.microfocus.octane.gitlab.helpers.PullRequestHelper;
import com.microfocus.octane.gitlab.helpers.VariablesHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Commit;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.Variable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@DependsOn({"gitLabApiWrapper", "applicationSettings", "taskExecutor"})
public class MergeRequestHistoryHandler {

    private static final Logger log = LogManager.getLogger(MergeRequestHistoryHandler.class);
    private final GitLabApi gitLabApi;
    private final ApplicationSettings applicationSettings;
    private final WatchService watchService;
    private final Path watchPath;
    private final TaskExecutor taskExecutor;

    @Autowired
    public MergeRequestHistoryHandler(GitLabApiWrapper gitLabApiWrapper, ApplicationSettings applicationSettings,
                                      TaskExecutor taskExecutor) {

        this.gitLabApi = gitLabApiWrapper.getGitLabApi();
        this.applicationSettings = applicationSettings;
        this.taskExecutor = taskExecutor;
        this.watchService = createWatchService();
        this.watchPath = Paths.get(applicationSettings.getConfig().getMergeRequestHistoryFolderPath());
        registerWatchPath();
    }

    private WatchService createWatchService() {
        try {
            return FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    private void registerWatchPath() {
        try {
            if (!Files.exists(this.watchPath)) {
                Files.createDirectory(this.watchPath);
            }
            this.watchPath.register(this.watchService, StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void executeFirstScan() {
        try {
            List<Project> gitLabProjects = gitLabApi.getProjectApi().getProjects().stream()
                    .filter(project -> {
                        Map<String, String> projectGroupVariables =
                                VariablesHelper.getProjectGroupVariables(gitLabApi, project);

                        Optional<Variable> shouldPublishToOctane =
                                VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                                        applicationSettings.getConfig().getPublishMergeRequestsVariableName());

                        return (shouldPublishToOctane.isPresent() &&
                                Boolean.parseBoolean(shouldPublishToOctane.get().getValue())) ||
                                (projectGroupVariables.containsKey(
                                        applicationSettings.getConfig().getPublishMergeRequestsVariableName()) &&
                                        Boolean.parseBoolean(projectGroupVariables.get(applicationSettings.getConfig()
                                                .getPublishMergeRequestsVariableName())));
                    }).collect(Collectors.toList());

            gitLabProjects.forEach(project -> {
                try {
                    Path pathToFile = Paths.get(watchPath.toString() + "/" + project.getId());
                    if (!Files.exists(pathToFile)) {
                        sendMergeRequestsToOctane(project);
                        Files.createFile(pathToFile);
                    }
                } catch (GitLabApiException | IOException e) {
                    log.warn(e.getMessage(), e);
                }
            });

        } catch (GitLabApiException e) {
            log.error(e.getMessage(), e);
        }
    }

    public void startListening() {
        taskExecutor.execute(() -> {
            WatchKey key;
            try {
                log.info("Listening for disk changes on " + watchPath + " ...");
                while ((key = watchService.take()) != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        log.info("Disk change event occurred: " + event.context().toString() + " was deleted.");
                        String projectId = event.context().toString();
                        try {
                            Project project = gitLabApi.getProjectApi().getProject(projectId);
                            sendMergeRequestsToOctane(project);
                            Path pathToFile = Paths.get(watchPath.toString() + "/" + project.getId());
                            Files.createFile(pathToFile);
                        } catch (GitLabApiException | IOException e) {
                            log.error(e.getMessage(), e);
                        }
                    }
                    key.reset();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    private void sendMergeRequestsToOctane(Project project) throws GitLabApiException {
        log.info("Sending merge request history for project with id " + project.getId() + " to Octane.");
        List<MergeRequest> mergeRequests = gitLabApi.getMergeRequestApi().getMergeRequests(project.getId());
        Map<String, String> projectGroupVariables = VariablesHelper.getProjectGroupVariables(gitLabApi, project);

        Optional<Variable> destinationWSVar =
                VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                        applicationSettings.getConfig().getDestinationWorkspaceVariableName());

        if (destinationWSVar.isEmpty() && !projectGroupVariables.containsKey(
                applicationSettings.getConfig().getDestinationWorkspaceVariableName())) {
            String err = "Variable for destination workspace has not been set for project with id" +
                    project.getId();
            log.error(err);
        } else {
            String destinationWS;

            if (destinationWSVar.isPresent()) {
                destinationWS = destinationWSVar.get().getValue();
            } else {
                destinationWS = projectGroupVariables.get(
                        applicationSettings.getConfig().getDestinationWorkspaceVariableName());
            }

            Optional<Variable> useSSHFormatVar =
                    VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                            applicationSettings.getConfig().getUseSSHFormatVariableName());

            boolean useSSHFormat =
                    useSSHFormatVar.isPresent() && Boolean.parseBoolean(useSSHFormatVar.get().getValue()) ||
                            projectGroupVariables.containsKey(
                                    applicationSettings.getConfig().getUseSSHFormatVariableName()) &&
                                    Boolean.parseBoolean(projectGroupVariables.get(
                                            applicationSettings.getConfig().getUseSSHFormatVariableName()));

            String repoUrl = useSSHFormat ? project.getSshUrlToRepo() : project.getHttpUrlToRepo();

            mergeRequests.forEach(mergeRequest -> {
                List<Commit> mergeRequestCommits = new ArrayList<>();
                Map<String, List<Diff>> mrCommitDiffs = new HashMap<>();
                try {
                    mergeRequestCommits =
                            gitLabApi.getMergeRequestApi().getCommits(project.getId(), mergeRequest.getIid());
                    mergeRequestCommits.forEach(commit -> {
                        try {
                            List<Diff> diffs = gitLabApi.getCommitsApi().getDiff(project.getId(), commit.getId());
                            mrCommitDiffs.put(commit.getId(), diffs);
                        } catch (GitLabApiException e) {
                            log.warn(e.getMessage());
                        }
                    });
                } catch (GitLabApiException e) {
                    log.warn(e.getMessage(), e);
                }

                PullRequestHelper.convertAndSendMergeRequestToOctane(mergeRequest, mergeRequestCommits, mrCommitDiffs,
                        repoUrl, destinationWS);
            });
        }
    }
}