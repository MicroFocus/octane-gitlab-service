package com.microfocus.octane.gitlab.services;

import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.events.MultiBranchType;
import com.hp.octane.integrations.dto.general.CIJobsList;
import com.hp.octane.integrations.dto.parameters.CIParameter;
import com.hp.octane.integrations.dto.parameters.CIParameterType;
import com.hp.octane.integrations.dto.pipelines.PipelineNode;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.helpers.GitLabApiWrapper;
import com.microfocus.octane.gitlab.helpers.ParsedPath;
import com.microfocus.octane.gitlab.helpers.PathType;
import com.microfocus.octane.gitlab.helpers.VariablesHelper;
import com.microfocus.octane.gitlab.testresults.TestResultsCleanUpRunnable;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.AccessLevel;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.ProjectFilter;
import org.gitlab4j.api.models.ProjectHook;
import org.gitlab4j.api.models.User;
import org.gitlab4j.api.models.Variable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Scope;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Scope("singleton")
public class GitlabServices {
    private static final Logger log = LogManager.getLogger(GitlabServices.class);
    private static final DTOFactory dtoFactory = DTOFactory.getInstance();
    private final ApplicationSettings applicationSettings;
    private final GitLabApiWrapper gitLabApiWrapper;
    private GitLabApi gitLabApi;
    private boolean cleanupOnly =false;
    private  ScheduledExecutorService executor;
    private URL webhookURL;

    @Autowired
    public GitlabServices(ApplicationSettings applicationSettings, GitLabApiWrapper gitLabApiWrapper,ApplicationArguments applicationArguments) {
        this.applicationSettings = applicationSettings;
        this.gitLabApiWrapper = gitLabApiWrapper;
        if(applicationArguments.containsOption("cleanupOnly") && (applicationArguments.getOptionValues("cleanupOnly").size()>0)){
            cleanupOnly = Boolean.parseBoolean(applicationArguments.getOptionValues("cleanupOnly").get(0));
        }
    }

    @PostConstruct
    private void init() throws MalformedURLException {
        //Adding webHooks
        initWebHookListenerURL();

        gitLabApi = gitLabApiWrapper.getGitLabApi();
        try {
            List<Project> projects = isCurrentUserAdmin() ? gitLabApi.getProjectApi().getProjects() : gitLabApi.getProjectApi().getMemberProjects();
            User currentUser = gitLabApi.getUserApi().getCurrentUser();

            if(cleanupOnly){
                log.info("start with cleanup process");
                for (Project project : projects) {
                    if (gitLabApiWrapper.isUserHasPermissionForProject(project, currentUser)) {
                            deleteWebHooks(project.getId());
                    }
                }
            }else {

                for (Project project : projects) {
                    if (gitLabApiWrapper.isUserHasPermissionForProject(project, currentUser)) {
                        addWebHookToProject(project.getId(),true);
                    }
                }
            }
        } catch (GitLabApiException e) {
            log.warn("Failed to create GitLab web hooks", e);
            throw new RuntimeException(e);
        }

        //start cleanUp thread
        executor =
                Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(new TestResultsCleanUpRunnable(applicationSettings.getConfig().getTestResultsOutputFolderPath()),  TestResultsCleanUpRunnable.INTERVAL, TestResultsCleanUpRunnable.INTERVAL, TimeUnit.MINUTES);
    }

    private Boolean addWebHookToProject(Object projectId, boolean deleteOldWebHook) throws GitLabApiException {

        try {
            if(deleteOldWebHook){
                deleteWebHooks(projectId);
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

    private static String generateNewToken() {
        final SecureRandom secureRandom = new SecureRandom();
        final Base64.Encoder base64Encoder = Base64.getUrlEncoder();
        byte[] randomBytes = new byte[24];
        secureRandom.nextBytes(randomBytes);
        return base64Encoder.encodeToString(randomBytes);
    }


    private void deleteWebHooks(Object projectIdOrPath) throws GitLabApiException {
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

    private void initWebHookListenerURL() throws MalformedURLException {
        URL serverBaseUrl = (applicationSettings.getConfig().getServerWebhookRouteUrl() != null && !applicationSettings.getConfig().getServerWebhookRouteUrl().isEmpty())?
                new URL(applicationSettings.getConfig().getServerWebhookRouteUrl()) :
                new URL(applicationSettings.getConfig().getServerBaseUrl());

        webhookURL= new URL(serverBaseUrl, "events");
    }

    @PreDestroy
    private void stop() {
        try {
            log.info("Destroying GitLab webhooks ...");

            List<Project> projects = isCurrentUserAdmin() ? gitLabApi.getProjectApi().getProjects() : gitLabApi.getProjectApi().getMemberProjects();
            User currentUser = gitLabApi.getUserApi().getCurrentUser();
            for (Project project : projects) {
                if (gitLabApiWrapper.isUserHasPermissionForProject(project,currentUser)) {
                    deleteWebHooks(project.getId());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to destroy GitLab webhooks", e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    private void validateEventsAPIAvailability() throws MalformedURLException {
        URL serverBaseUrl = new URL(applicationSettings.getConfig().getServerBaseUrl());
        URL webhookListenerUrl = new URL(serverBaseUrl, "events");
        String warning = String.format("Error while accessing the '%s' endpoint. Note that this endpoint must be accessible by GitLab.", webhookListenerUrl.toString());

        if(applicationSettings.getConfig().getServerWebhookRouteUrl()!=null){
            URL webhookRouteUrl = new URL(new URL(applicationSettings.getConfig().getServerWebhookRouteUrl()), "events");
            warning = String.format("Error while accessing the '%s' endpoint. Note that you should route '%s' to this endpoint, and this endpoint must be accessible",
                    webhookListenerUrl.toString(),webhookRouteUrl.toString());
        }

        try {
            CloseableHttpClient httpclient = HttpClients.createSystem();
            HttpGet httpGet = new HttpGet(webhookListenerUrl.toString());
            CloseableHttpResponse response = httpclient.execute(httpGet);
            if (response.getStatusLine().getStatusCode() == 200) {
                String responseStr = EntityUtils.toString(response.getEntity());
                if (responseStr.equals(com.microfocus.octane.gitlab.api.EventListener.LISTENING)) {
                    final String success = String.format("Success while accessing the '%s' endpoint.", webhookListenerUrl.toString());
                    log.info(success);
                } else {
                    log.error(warning);
                }
            } else {
                log.error(warning);
            }
            response.close();
            httpclient.close();
        } catch (Exception e) {
            log.error(warning, e);
        }
    }

    CIJobsList getJobList() {
        CIJobsList ciJobsList = dtoFactory.newDTO(CIJobsList.class);
        List<PipelineNode> list = new ArrayList<>();
        String projectNames ="";
        try {
            ProjectFilter filter = new ProjectFilter();
            filter.withMinAccessLevel(AccessLevel.MAINTAINER);
            List<Project> projectsFilters = gitLabApi.getProjectApi().getProjects(filter);
            log.info("There are only "+ projectsFilters.size() +" projects with access level => MAINTAINER for the integrated user");


            for (Project project : projectsFilters) {
                    try {
                        ParsedPath parseProject = new ParsedPath(project, gitLabApi);
                        PipelineNode buildConf;
                        if (parseProject.isMultiBranch()) {
                            buildConf = dtoFactory.newDTO(PipelineNode.class)
                                    .setJobCiId(parseProject.getFullPathOfPipeline().toLowerCase())
                                    .setName(parseProject.getFullPathOfProject())
                                    .setMultiBranchType(MultiBranchType.MULTI_BRANCH_PARENT);
                        } else {
                            buildConf = dtoFactory.newDTO(PipelineNode.class)
                                    .setJobCiId(parseProject.getFullPathOfPipelineWithBranch().toLowerCase())
                                    .setName(parseProject.getFullPathOfProject());
                        }

                        projectNames = projectNames + buildConf.getName()+",";
                        list.add(buildConf);
                    } catch (Exception e) {
                        log.warn("Failed to add some tags to the job list", e);
                    }
                }

        } catch (Exception e) {
            log.warn("Failed to add some jobs to the job list", e);
        }

        log.info("getJobList results:"+projectNames);
        ciJobsList.setJobs(list.toArray(new PipelineNode[list.size()]));
        return ciJobsList;
    }

    private Boolean isCurrentUserAdmin() throws GitLabApiException {
        return gitLabApi.getUserApi().getCurrentUser().getIsAdmin() != null && gitLabApi.getUserApi().getCurrentUser().getIsAdmin();
    }

    PipelineNode createStructure(String buildId) {
        ParsedPath project = new ParsedPath(buildId, gitLabApi, PathType.MULTI_BRUNCH);

        //add a webhook to new Octane pipeline (gitlab project) in Octane
        try{
            if (project.isMultiBranch()) {
                addWebHookToProject(project.getFullPathOfProject(),true);
                 return dtoFactory.newDTO(PipelineNode.class)
                        .setJobCiId(project.getFullPathOfPipeline().toLowerCase())
                        .setMultiBranchType(MultiBranchType.MULTI_BRANCH_PARENT)
                        .setParameters(getParameters(project));
            }
            project = new ParsedPath(buildId, gitLabApi, PathType.PIPELINE);
            addWebHookToProject(project.getId(),true);
            return   dtoFactory.newDTO(PipelineNode.class)
                    .setJobCiId(project.getFullPathOfPipelineWithBranch().toLowerCase())
                    .setName(project.getCurrentBranchOrDefault())
                    .setParameters(getParameters(project));
        } catch (GitLabApiException e){
            log.error("unable to update webhook when create a pipeline in Octane for project:"+ project.getDisplayName(),e);
            return null;
        }
    }

    public List<CIParameter> getParameters(ParsedPath project) {
        List<CIParameter> parametersList = new ArrayList<>();
        List<Variable> projectVariables = VariablesHelper.getVariables(project,gitLabApi,applicationSettings.getConfig());

        projectVariables.forEach(var -> {
            CIParameter param = dtoFactory.newDTO(CIParameter.class);
            param.setType(CIParameterType.STRING);
            param.setName(var.getKey());
            param.setDefaultValue(var.getValue());
            parametersList.add(param);
        });

        return parametersList;
    }

    public boolean isCleanUpOnly() {
        return cleanupOnly;
    }

}
