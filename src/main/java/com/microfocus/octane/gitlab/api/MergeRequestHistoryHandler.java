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
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.Variable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
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
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class MergeRequestHistoryHandler {

    private static final Logger log = LogManager.getLogger(MergeRequestHistoryHandler.class);
    private final GitLabApi gitLabApi;
    private final ApplicationSettings applicationSettings;
    private final WatchService watchService;
    private final Path watchPath;
    private final TaskExecutor taskExecutor;

    @Bean
    public TaskExecutor taskExecutor() {
        return new SimpleAsyncTaskExecutor();
    }

    @Autowired
    public MergeRequestHistoryHandler(GitLabApiWrapper gitLabApiWrapper, ApplicationSettings applicationSettings,
                                      TaskExecutor taskExecutor) {

        this.gitLabApi = gitLabApiWrapper.getGitLabApi();
        this.applicationSettings = applicationSettings;
        this.taskExecutor = taskExecutor;
        this.watchService = createWatchService();
        this.watchPath = Paths.get(applicationSettings.getConfig().getMergeRequestHistoryFolderPath());
        registerWatchPath();
        executeFirstScan();
        startListening();
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
            this.watchPath.register(this.watchService, StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void executeFirstScan() {
        try {
            List<Project> gitLabProjects = gitLabApi.getProjectApi().getProjects().stream()
                    .filter(project -> {
                        Optional<Variable> shouldPublishToOctane =
                                VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                                        applicationSettings.getConfig().getPublishMergeRequestsVariableName());

                        return shouldPublishToOctane.isPresent()
                                && Boolean.parseBoolean(shouldPublishToOctane.get().getValue());
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

    private void startListening() {
        taskExecutor.execute(() -> {
            WatchKey key;
            try {
                while ((key = watchService.take()) != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
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
        List<MergeRequest> mergeRequests = gitLabApi.getMergeRequestApi().getMergeRequests(project.getId());
        mergeRequests.forEach(mergeRequest -> {
            Optional<Variable> destinationWSVar =
                    VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                            applicationSettings.getConfig().getDestinationWorkspaceVariableName());

            if (destinationWSVar.isEmpty()) {
                String warning = "Variable for destination workspace has not been set for project with id" +
                        project.getId();
                log.warn(warning);
            } else {
                Optional<Variable> useSSHFormatVar =
                        VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                                applicationSettings.getConfig().getUseSSHFormatVariableName());
                boolean useSSHFormat =
                        useSSHFormatVar.isPresent() && Boolean.parseBoolean(useSSHFormatVar.get().getValue());
                String repoUrl = useSSHFormat ? project.getSshUrlToRepo() : project.getHttpUrlToRepo();

                List<Commit> mergeRequestCommits = new ArrayList<>();

                try {
                    mergeRequestCommits =
                            gitLabApi.getMergeRequestApi().getCommits(project.getId(), mergeRequest.getIid());
                } catch (GitLabApiException e) {
                    log.warn(e.getMessage(), e);
                }

                PullRequestHelper.convertAndSendMergeRequestToOctane(mergeRequest, mergeRequestCommits, repoUrl,
                        destinationWSVar.get().getValue());
            }
        });
    }
}