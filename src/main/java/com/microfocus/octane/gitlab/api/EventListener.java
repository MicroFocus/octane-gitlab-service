package com.microfocus.octane.gitlab.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hp.octane.integrations.OctaneSDK;
import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.causes.CIEventCause;
import com.hp.octane.integrations.dto.causes.CIEventCauseType;
import com.hp.octane.integrations.dto.coverage.CoverageReportType;
import com.hp.octane.integrations.dto.events.CIEvent;
import com.hp.octane.integrations.dto.events.CIEventType;
import com.hp.octane.integrations.dto.events.MultiBranchType;
import com.hp.octane.integrations.dto.events.PhaseType;
import com.hp.octane.integrations.dto.parameters.CIParameter;
import com.hp.octane.integrations.dto.scm.SCMChange;
import com.hp.octane.integrations.dto.scm.SCMCommit;
import com.hp.octane.integrations.dto.scm.SCMData;
import com.hp.octane.integrations.dto.scm.SCMRepository;
import com.hp.octane.integrations.dto.scm.SCMType;
import com.hp.octane.integrations.dto.snapshots.CIBuildResult;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.helpers.GitLabApiWrapper;
import com.microfocus.octane.gitlab.helpers.ParsedPath;
import com.microfocus.octane.gitlab.helpers.PathType;
import com.microfocus.octane.gitlab.helpers.PullRequestHelper;
import com.microfocus.octane.gitlab.helpers.VariablesHelper;
import com.microfocus.octane.gitlab.model.ConfigStructure;
import com.microfocus.octane.gitlab.model.MergeRequestEventType;
import com.microfocus.octane.gitlab.services.OctaneServices;
import com.microfocus.octane.gitlab.testresults.GherkinTestResultsProvider;
import com.microfocus.octane.gitlab.testresults.JunitTestResultsProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Commit;
import org.gitlab4j.api.models.CompareResults;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.Job;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.Variable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
@Path("/events")
public class EventListener {
    public static final String LISTENING = "Listening to GitLab events!!!";
    private static final Logger log = LogManager.getLogger(EventListener.class);
    private static final DTOFactory dtoFactory = DTOFactory.getInstance();
    private final GitLabApi gitLabApi;
    private final ApplicationSettings applicationSettings;

    @Autowired
    public EventListener(ApplicationSettings applicationSettings, GitLabApiWrapper gitLabApiWrapper, OctaneServices octaneServices) {
        this.applicationSettings = applicationSettings;
        this.gitLabApi = gitLabApiWrapper.getGitLabApi();
    }

    @POST
    @Produces("application/json")
    @Consumes("application/json")
    public Response index(String msg) {
        JSONObject event = new JSONObject(msg);
        return handleEvent(event);
    }

    @GET
    @Produces("application/json")
    public Response validate() {
        return Response.ok().entity(LISTENING).build();
    }

    private Response handleEvent(JSONObject event) {
        log.traceEntry();
        try {
            if (isMergeRequestEvent(event)) {
                return handleMergeRequestEvent(event);
            }

            CIEventType eventType = getEventType(event);
            if (eventType == CIEventType.UNDEFINED || eventType == CIEventType.QUEUED) return Response.ok().build();

            List<CIEvent> eventList = getCIEvents(event);
            eventList.forEach(ciEvent -> {
                if (ciEvent.getResult() == null) {
                    ciEvent.setResult(CIBuildResult.UNAVAILABLE);
                }
                try {
                    log.trace(new ObjectMapper().writeValueAsString(ciEvent));
                } catch (Exception e) {
                    log.debug("Failed to trace an incoming event", e);
                }
                if (eventType == CIEventType.FINISHED || eventType == CIEventType.STARTED) {
                    ParsedPath parsedPath =null;
                    if (ciEvent.getProject().endsWith("/build")) {
                        parsedPath = new ParsedPath(ciEvent.getProject().substring(0, ciEvent.getProject().length() - 6), gitLabApi, PathType.PROJECT);
                        if (parsedPath.isMultiBranch()) {
                            ciEvent.setSkipValidation(true);
                        }
                    }
                    if (ciEvent.getProject().contains("pipeline:")) {
                        parsedPath = new ParsedPath(ciEvent.getProject(), gitLabApi, PathType.PIPELINE);
                        if (parsedPath.isMultiBranch()) {
                            ciEvent.setProjectDisplayName(parsedPath.getFullPathOfProjectWithBranch());
                            ciEvent.setParentCiId(parsedPath.getFullPathOfPipeline()).setMultiBranchType(MultiBranchType.MULTI_BRANCH_CHILD).setSkipValidation(true);
                        }
                    }

                    if(isPipelineEvent(event)){
                        List<CIParameter> parametersList = new ArrayList<>();
                        JSONArray variablesList = VariablesHelper.getVariablesListFromPipelineEvent(event);
                        //check if this parameter is in job level:
                        List<Variable> allVariables = VariablesHelper.getVariables(parsedPath,gitLabApi,applicationSettings.getConfig());

                        variablesList.forEach(var -> {
                            boolean shouldReport = allVariables.stream()
                                    .filter(o -> (o.getKey().equals(((JSONObject)var).get("key")))).findFirst().isPresent();
                            if(shouldReport){
                                parametersList.add(VariablesHelper.convertVariableToParameter(var));
                            }
                        });

                        if(parametersList.size() >0) {
                            ciEvent.setParameters(parametersList);
                        }
                    }
                }
                OctaneSDK.getClients().forEach(client -> client.getEventsService().publishEvent(ciEvent));
            });
            if (eventType == CIEventType.FINISHED) {
                Integer projectId = isPipelineEvent(event) ? event.getJSONObject("project").getInt("id") : event.getInt("project_id");
                if (!isPipelineEvent(event)) {
                    Project project = gitLabApi.getProjectApi().getProject(projectId);
                    Integer jobId = getEventTargetObjectId(event);
                    Job job = gitLabApi.getJobApi().getJob(projectId, jobId);

                    if(job.getArtifactsFile() != null) {

                        sendCodeCoverage(projectId, project, job);

                        GherkinTestResultsProvider gherkinTestResultsProvider = GherkinTestResultsProvider.getInstance(applicationSettings);
                        boolean isGherkinTestsExist =
                                gherkinTestResultsProvider.createTestList(project,job,gitLabApi.getJobApi().downloadArtifactsFile(projectId, job.getId()));

                        //looking for Regular tests
                        if(!isGherkinTestsExist) {
                            JunitTestResultsProvider testResultsProduce =  JunitTestResultsProvider.getInstance(applicationSettings);
                            boolean testResultsExist = testResultsProduce.createTestList(project,job,
                                    gitLabApi.getJobApi().downloadArtifactsFile(projectId, job.getId()));

                            if(!testResultsExist) {
                                String warning = String.format("No test results found by using the %s pattern",
                                        applicationSettings.getConfig().getGitlabTestResultsFilePattern());
                                log.warn(warning);
                                return Response.ok().entity(warning).build();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("An error occurred while handling GitLab event", e);
        }
        log.traceExit();
        return Response.ok().build();
    }

    private void sendCodeCoverage(Integer projectId, Project project, Job job) throws GitLabApiException, IOException {
        Optional<Variable> coverageReportFilePath = VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                applicationSettings.getConfig().getGeneratedCoverageReportFilePathVariableName());

        if (coverageReportFilePath.isEmpty()) {
            log.info("Variable for JaCoCo coverage report path not set. No coverage injection for this pipeline.");
        } else {
            String octaneJobId = project.getPathWithNamespace().toLowerCase() + "/" + job.getName();
            String octaneBuildId = job.getId().toString();

            OctaneSDK.getClients().forEach(client -> {
                try (InputStream codeCoverageReportFile = gitLabApi.getJobApi()
                        .downloadSingleArtifactsFile(projectId, job.getId(),
                                Paths.get(coverageReportFilePath.get().getValue()))) {
                    client.getCoverageService()
                            .pushCoverage(octaneJobId, octaneBuildId, CoverageReportType.JACOCOXML,
                                    codeCoverageReportFile);
                } catch (GitLabApiException | IOException exception) {
                    log.error(exception.getMessage());
                }
            });
        }
    }

    private Response handleMergeRequestEvent(JSONObject event) throws GitLabApiException {
        log.info("Merge Request event occurred.");
        ConfigStructure config = applicationSettings.getConfig();

        if (getMREventType(event).equals(MergeRequestEventType.UNKNOWN)) {
            String warning = "Unknown event on merge request has taken place!";
            log.warn(warning);
            return Response.ok().entity(warning).build();
        }

        Project project = gitLabApi.getProjectApi().getProject(event.getJSONObject("project").getInt("id"));

        Optional<Variable> publishMergeRequests = VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                config.getPublishMergeRequestsVariableName());
        if (publishMergeRequests.isEmpty() || !Boolean.parseBoolean(publishMergeRequests.get().getValue())) {
            return Response.ok().build();
        }

        Optional<Variable> destinationWSVar = VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                config.getDestinationWorkspaceVariableName());
        if (destinationWSVar.isEmpty()) {
            String err = "Variable for destination workspace has not been set for project with id" + project.getId();
            log.error(err);
            return Response.ok().entity(err).build();
        }

        Optional<Variable> useSSHFormatVar = VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                config.getUseSSHFormatVariableName());
        boolean useSSHFormat = useSSHFormatVar.isPresent() && Boolean.parseBoolean(useSSHFormatVar.get().getValue());

        String repoUrl = useSSHFormat ? project.getSshUrlToRepo() : project.getHttpUrlToRepo();

        int mergeRequestId = getEventTargetObjectId(event);
        MergeRequest mergeRequest = gitLabApi.getMergeRequestApi().getMergeRequest(project.getId(), mergeRequestId);

        List<Commit> mergeRequestCommits = gitLabApi.getMergeRequestApi().getCommits(project.getId(), mergeRequest.getIid());
        Map<String, List<Diff>> mrCommitDiffs = new HashMap<>();

        mergeRequestCommits.forEach(commit -> {
            try {
                List<Diff> diffs = gitLabApi.getCommitsApi().getDiff(project.getId(), commit.getId());
                mrCommitDiffs.put(commit.getId(), diffs);
            } catch (GitLabApiException e) {
                log.warn(e.getMessage());
            }
        });

        PullRequestHelper.convertAndSendMergeRequestToOctane(mergeRequest, mergeRequestCommits, mrCommitDiffs, repoUrl,
                destinationWSVar.get().getValue());

        return Response.ok().build();
    }

    private List<CIEvent> getCIEvents(JSONObject event) {
        List<CIEvent> events = new ArrayList<>();
        CIEventType eventType = getEventType(event);
        Integer buildCiId = getEventTargetObjectId(event);

        Object duration = getDuration(event);
        Long startTime = getStartTime(event,duration);

        SCMData scmData = null;
        boolean isScmNull = true;
        if (isPipelineEvent(event)) {
            if (eventType == CIEventType.STARTED) {
                scmData = getScmData(event);
                isScmNull = scmData == null;
            } else {
                String GITLAB_BLANK_SHA = "0000000000000000000000000000000000000000";
                isScmNull = event.getJSONObject("object_attributes").getString("before_sha").equals(GITLAB_BLANK_SHA);
            }
        }

        events.add(dtoFactory.newDTO(CIEvent.class)
                .setProjectDisplayName(getCiName(event))
                .setEventType(eventType)
                .setBuildCiId(buildCiId.toString())
                .setNumber(buildCiId.toString())
                .setProject(getCiFullName(event))
                .setResult(eventType == CIEventType.STARTED || eventType == CIEventType.DELETED ? null : convertCiBuildResult(getStatus(event)))
                .setStartTime(startTime)
                .setEstimatedDuration(null)
                .setDuration(calculateDuration(eventType,duration))
                .setScmData(null)
                .setCauses(getCauses(event, isScmNull))
                .setPhaseType(isPipelineEvent(event) ? PhaseType.POST : PhaseType.INTERNAL)
        );

        if (scmData != null) {
            events.add(dtoFactory.newDTO(CIEvent.class)
                    .setProjectDisplayName(getCiName(event))
                    .setEventType(CIEventType.SCM)
                    .setBuildCiId(buildCiId.toString())
                    .setNumber(null)
                    .setProject(getCiFullName(event))
                    .setResult(null)
                    .setStartTime(null)
                    .setEstimatedDuration(null)
                    .setDuration(null)
                    .setScmData(scmData)
                    .setCauses(getCauses(event, false))
                    .setPhaseType(null)
            );
        }

        return events;
    }

    private Long calculateDuration(CIEventType eventType, Object duration) {
        if(eventType == CIEventType.STARTED || duration == null) return null;

        if(duration instanceof Double) return Math.round(1000* (Double) duration);
        if(duration instanceof BigDecimal) return Long.valueOf(Math.round(1000* ((BigDecimal) duration).intValue()));

        return Long.valueOf(Math.round(1000 * (Integer) duration));

       // return Math.round(duration instanceof Double ? 1000* (Double) duration : 1000 * (Integer) duration);

    }

    private Long getStartTime(JSONObject event, Object duration) {
        Long startTime = getTime(event, "started_at");
        if (startTime == null){
           try {
               startTime = getTime(event, "finished_at") - Long.parseLong(duration.toString());
           }catch (Exception e){
               startTime = getTime(event, "created_at");
           }
        }
        return startTime;
    }

    private List<CIEventCause> getCauses(JSONObject event, boolean isScmNull) {
        List<CIEventCause> causes = new ArrayList<>();
        CIEventCauseType type = convertCiEventCauseType(event, isScmNull);
        CIEventCause rootCause = dtoFactory.newDTO(CIEventCause.class);
        rootCause.setType(type);
        rootCause.setUser(type == CIEventCauseType.USER ? getUser(event) : null);
        if (isDeleteBranchEvent(event) || isPipelineEvent(event)) {
            causes.add(rootCause);
        } else {
            CIEventCause cause = dtoFactory.newDTO(CIEventCause.class);
            cause.setType(CIEventCauseType.UPSTREAM);
            cause.setProject(getRootFullName(event)); ///
            cause.setBuildCiId(getRootId(event).toString());
            cause.getCauses().add(rootCause);
            causes.add(cause);
        }
        return causes;
    }

    private String getUser(JSONObject event) {
        return isDeleteBranchEvent(event) ? event.getString("user_name") : event.getJSONObject("user").getString("name");
    }

    private CIEventCauseType convertCiEventCauseType(JSONObject event, boolean isScmNull) {
        if (isDeleteBranchEvent(event)) {
            return CIEventCauseType.USER;
        }
        if (!isScmNull) {
            return CIEventCauseType.SCM;
        }
        String pipelineSchedule = null;
        try {
            pipelineSchedule = isPipelineEvent(event) ? event.getJSONObject("object_attributes").getString("pipeline_schedule") : null;
        } catch (Exception e) {
            log.warn("Failed to infer event cause type, using 'USER' as default");
        }
        if (pipelineSchedule != null && pipelineSchedule.equals("true")) return CIEventCauseType.TIMER;
        return CIEventCauseType.USER;
    }

    private CIBuildResult convertCiBuildResult(String status) {
        if (status.equals("success")) return CIBuildResult.SUCCESS;
        if (status.equals("failed")) return CIBuildResult.FAILURE;
        if (status.equals("drop") || status.equals("skipped") || status.equals("canceled"))
            return CIBuildResult.ABORTED;
        if (status.equals("unstable")) return CIBuildResult.UNSTABLE;
        return CIBuildResult.UNAVAILABLE;
    }

    private String getStatus(JSONObject event) {
        if  (isMergeRequestEvent(event)) {
            return event.getJSONObject("object_attributes").getString("action");
        } else if (isPipelineEvent(event)) {
            return event.getJSONObject("object_attributes").getString("status");
        } else if (isDeleteBranchEvent(event)) {
            return "delete";
        } else if (event.getString("object_kind").equals("push")) {//push that not delete branch
            return "undefined";
        } else {
            return event.getString("build_status");
        }
    }

    private String getCiName(JSONObject event) {
        if (isPipelineEvent(event)) {
            return event.getJSONObject("object_attributes").getString("ref");
        } else if (isDeleteBranchEvent(event)) {
            return ParsedPath.getLastPartOfPath(event.getString("ref"));
        } else {
            return event.getString("build_name");
        }
    }

    private String getCiFullName(JSONObject event) {
        String fullName = getProjectFullPath(event) + "/" + getCiName(event);
        if (isPipelineEvent(event) || isDeleteBranchEvent(event)) fullName = "pipeline:" + fullName;
        return fullName;
    }

    private String getRootName(JSONObject event) {
        return isPipelineEvent(event) ? event.getJSONObject("object_attributes").getString("ref") : event.getString("ref");
    }

    private String getRootFullName(JSONObject event) {
        return "pipeline:" + getProjectFullPath(event) + "/" + getRootName(event);
    }

    private String getProjectFullPath(JSONObject event) {
        try {
            if (isPipelineEvent(event)) {
                return new URL(event.getJSONObject("project").getString("web_url")).getPath().substring(1).toLowerCase();
            }

            // I couldn't find any other suitable property rather then repository.homepage.
            // But this one may potentially cause a defect with external repos.
            return new URL(event.getJSONObject("repository").getString("homepage")).getPath().substring(1).toLowerCase();
        } catch (MalformedURLException e) {
            log.warn("Failed to return the project full path, using an empty string as default", e);
            return "";
        }
    }

    private Integer getRootId(JSONObject event) {
        return isPipelineEvent(event) ? event.getJSONObject("object_attributes").getInt("id") : event.getJSONObject("commit").getInt("id");
    }

    private SCMData getScmData(JSONObject event) {
        try {
            Integer projectId = event.getJSONObject("project").getInt("id");
            String sha = event.getJSONObject("object_attributes").getString("sha");
            String beforeSha = event.getJSONObject("object_attributes").getString("before_sha");
            CompareResults results = gitLabApi.getRepositoryApi().compare(projectId, beforeSha, sha);
            List<SCMCommit> commits = new ArrayList<>();
            results.getCommits().forEach(c -> {
                SCMCommit commit = dtoFactory.newDTO(SCMCommit.class);
                commit.setTime(c.getTimestamp() != null ? c.getTimestamp().getTime() : new Date().getTime());
                commit.setUser(c.getCommitterName());
                commit.setUserEmail(c.getCommitterEmail());
                commit.setRevId(c.getId());
                commit.setParentRevId(sha);
                commit.setComment(c.getMessage());
                try {
                    List<Diff> diffs = gitLabApi.getCommitsApi().getDiff(projectId, c.getId());
                    List<SCMChange> changes = new ArrayList<>();
                    diffs.forEach(d -> {
                        SCMChange change = dtoFactory.newDTO(SCMChange.class);
                        change.setFile(d.getNewPath());
                        change.setType(d.getNewFile() ? "add" : d.getDeletedFile() ? "delete" : "edit");
                        changes.add(change);
                    });
                    commit.setChanges(changes);
                } catch (GitLabApiException e) {
                    log.warn("Failed to add a commit to the SCM data", e);
                }
                commits.add(commit);
            });

            SCMRepository repo = dtoFactory.newDTO(SCMRepository.class);
            repo.setType(SCMType.GIT);
            repo.setUrl(event.getJSONObject("project").getString("git_http_url"));
            repo.setBranch(event.getJSONObject("object_attributes").getString("ref"));
            SCMData data = dtoFactory.newDTO(SCMData.class);
            data.setRepository(repo);
            data.setBuiltRevId(sha);
            data.setCommits(commits);
            return data;
        } catch (GitLabApiException e) {
            log.warn("Failed to return the SCM data. Returning null.");
            return null;
        }
    }

    private Object getDuration(JSONObject event) {
        try {
            if (isPipelineEvent(event)) {
                return event.getJSONObject("object_attributes").get("duration");
            } else if (isDeleteBranchEvent(event)) {
                return null;
            } else {
                return event.get("build_duration");
            }
        } catch (Exception e) {
            log.warn("Failed to return the duration, using null as default.", e);
            return null;
        }
    }

    private Long getTime(JSONObject event, String attrName) {
        try {
            String time = isPipelineEvent(event) ? event.getJSONObject("object_attributes").getString(attrName) : event.getString("build_" + attrName);
            return time == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.ENGLISH).parse(time).getTime();
        } catch (Exception e) {
            String message = "Failed to return the '" + attrName + "' of the job, using null as default.";
            if (log.isDebugEnabled()) {
                log.debug(message, e);
            } else {
                log.warn(message);
            }
            return null;
        }
    }

    private Integer getEventTargetObjectId(JSONObject event) {
        if (isMergeRequestEvent(event)) {
            return event.getJSONObject("object_attributes").getInt("iid");
        } else if (isPipelineEvent(event)) {
            return event.getJSONObject("object_attributes").getInt("id");
        } else if (isDeleteBranchEvent(event)) {
            return event.getInt("project_id");
        } else {
            return event.getInt("build_id");
        }
    }

    private MergeRequestEventType getMREventType(JSONObject event) {
        String mergeRequestEventStatus = getStatus(event);
        switch (mergeRequestEventStatus) {
            case "open": return MergeRequestEventType.OPEN;
            case "update": return MergeRequestEventType.UPDATE;
            case "close": return MergeRequestEventType.CLOSE;
            case "reopen": return MergeRequestEventType.REOPEN;
            case "merge": return MergeRequestEventType.MERGE;
            default: return MergeRequestEventType.UNKNOWN;
        }
    }

    private CIEventType getEventType(JSONObject event) {
        String statusStr = getStatus(event);
        if (isPipelineEvent(event)) {
            if (statusStr.equals("pending")) return CIEventType.STARTED;
            if (statusStr.equals("running")) return CIEventType.UNDEFINED;
        }
        if (Arrays.asList(new String[]{"process", "enqueue", "pending", "created"}).contains(statusStr)) {
            return CIEventType.QUEUED;
        } else if (Arrays.asList(new String[]{"success", "failed", "canceled", "skipped"}).contains(statusStr)) {
            return CIEventType.FINISHED;
        } else if (Arrays.asList(new String[]{"running", "manual"}).contains(statusStr)) {
            return CIEventType.STARTED;
        } else if (statusStr.equals("delete")) {
            return CIEventType.DELETED;
        } else {
            return CIEventType.UNDEFINED;
        }
    }

    private boolean isPipelineEvent(JSONObject event) {
        return event.getString("object_kind").equals("pipeline");
    }

    private boolean isMergeRequestEvent(JSONObject event) {
        return event.getString("object_kind").equals("merge_request");
    }

    private boolean isDeleteBranchEvent(JSONObject event) {
        return (event.getString("object_kind").equals("push") &&
                event.getString("after").contains("00000000000000000") &&
                event.isNull("checkout_sha"));

    }
}
