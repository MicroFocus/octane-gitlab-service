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
import com.microfocus.octane.gitlab.helpers.TestResultsHelper;
import com.microfocus.octane.gitlab.helpers.VariablesHelper;
import com.microfocus.octane.gitlab.model.ConfigStructure;
import com.microfocus.octane.gitlab.model.MergeRequestEventType;
import com.microfocus.octane.gitlab.services.OctaneServices;
import com.microfocus.octane.gitlab.testresults.GherkinTestResultsProvider;
import com.microfocus.octane.gitlab.testresults.JunitTestResultsProvider;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
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
import javax.xml.transform.TransformerConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
@Path("/events")
public class EventListener {
    public static final String LISTENING = "Listening to GitLab events!!!";
    private static final Logger log = LogManager.getLogger(EventListener.class);
    private static final DTOFactory dtoFactory = DTOFactory.getInstance();
    private final GitLabApi gitLabApi;
    private final ApplicationSettings applicationSettings;
    private final Map<Long, JSONArray> pipelineVariables = new HashMap<>();
    private final List<Long> sentRoots = new ArrayList<>();
    private final Map<Long, List<Pair<CIEvent, JSONObject>>> noRootEvents = new HashMap<>();

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

            List<String> warnings = new ArrayList<>();
            CIEventType eventType = getEventType(event);
            if (eventType == CIEventType.UNDEFINED || eventType == CIEventType.QUEUED) {
                return Response.ok().build();
            }

            CIEvent ciEvent = getCIEvent(event);

            if (ciEvent.getResult() == null) {
                ciEvent.setResult(CIBuildResult.UNAVAILABLE);
            }

            try {
                log.trace(new ObjectMapper().writeValueAsString(ciEvent));
            } catch (Exception e) {
                log.debug("Failed to trace an incoming event", e);
            }

            if (eventType == CIEventType.FINISHED || eventType == CIEventType.STARTED) {
                if (ciEvent.getProject().endsWith("/build")) {
                    ciEvent.setSkipValidation(true);
                }

                if (ciEvent.getProject().contains(ParsedPath.PIPELINE_JOB_CI_ID_PREFIX)) {
                    ParsedPath parsedPath = new ParsedPath(ciEvent.getProject(), gitLabApi, PathType.PIPELINE);

                    String projectDisplayName = parsedPath.getNameWithNameSpaceForDisplayName() != null ?
                            parsedPath.getNameWithNameSpaceForDisplayName() : parsedPath.getFullPathOfProjectWithBranch();

                    ciEvent.setProjectDisplayName(projectDisplayName + "/" + parsedPath.getCurrentBranch());
                    ciEvent.setParentCiId(parsedPath.getJobCiId(true)).setMultiBranchType(MultiBranchType.MULTI_BRANCH_CHILD).setSkipValidation(true);
                }

                long pipelineId = getPipelineId(event);

                if (isPipelineEvent(event)) {
                    pipelineVariables.put(pipelineId, VariablesHelper.getVariablesListFromPipelineEvent(event));
                }

                if (isPipelineEvent(event) || (isBuildEvent(event) && eventType == CIEventType.STARTED)) {
                    List<CIParameter> parametersList = new ArrayList<>();
                    if (pipelineVariables.get(pipelineId) != null)
                        pipelineVariables.get(pipelineId).forEach(var -> parametersList.add(VariablesHelper.convertVariableToParameter(var)));

                    if (parametersList.size() > 0) {
                        ciEvent.setParameters(parametersList);
                    }
                }

                if (isPipelineEvent(event) && eventType == CIEventType.STARTED) {
                    OctaneSDK.getClients().forEach(client -> client.getEventsService().publishEvent(ciEvent));

                    CIEvent scmEvent = getScmEvent(event);
                    if (scmEvent != null)
                        OctaneSDK.getClients().forEach(client -> client.getEventsService().publishEvent(scmEvent));

                    sentRoots.add(pipelineId);
                } else {
                    if (!sentRoots.contains(pipelineId)) {
                        List<Pair<CIEvent, JSONObject>> pipelineEvents = noRootEvents.containsKey(pipelineId) ? noRootEvents.get(pipelineId) : new ArrayList<>();
                        pipelineEvents.add(new ImmutablePair<>(ciEvent, event));

                        noRootEvents.put(pipelineId, pipelineEvents);
                    } else {

                        if (noRootEvents.containsKey(pipelineId)) {
                            noRootEvents.get(pipelineId).forEach(noRootEvent -> {
                                        OctaneSDK.getClients().forEach(client ->
                                                client.getEventsService().publishEvent(noRootEvent.getKey()));
                                        try {
                                            if (getEventType(noRootEvent.getValue()) == CIEventType.FINISHED)
                                                warnings.add(checkForCoverage(noRootEvent.getValue()));
                                        } catch (Exception e) {
                                            log.warn("An error occurred while handling GitLab event", e);
                                        }
                                    }
                            );
                            noRootEvents.remove(pipelineId);
                        }

                        OctaneSDK.getClients().forEach(client -> client.getEventsService().publishEvent(ciEvent));

                        if (eventType == CIEventType.FINISHED) {
                            warnings.add(checkForCoverage(event));
                            if (isPipelineEvent(event))
                                sentRoots.remove(pipelineId);
                        }
                    }
                }
            }

            warnings.removeAll(Collections.singletonList(""));
            if (!warnings.isEmpty())
                return Response.ok().entity(warnings).build();
        } catch (Exception e) {
            log.warn("An error occurred while handling GitLab event", e);
        }
        log.traceExit();
        return Response.ok().build();
    }


    private String checkForCoverage(JSONObject event) throws GitLabApiException, IOException, TransformerConfigurationException {

        if (isPipelineEvent(event)) {
            pipelineVariables.remove(getPipelineId(event));
        }

        if (!isPipelineEvent(event)) {
            long projectId = event.getLong("project_id");
            Project project = gitLabApi.getProjectApi().getProject(projectId);
            long jobId = getEventTargetObjectId(event);
            Job job = gitLabApi.getJobApi().getJob(projectId, jobId);

            if (job.getArtifactsFile() != null) {

                sendCodeCoverage(projectId, project, job);

                GherkinTestResultsProvider gherkinTestResultsProvider = GherkinTestResultsProvider.getInstance(applicationSettings);
                boolean isGherkinTestsExist =
                        gherkinTestResultsProvider.createTestList(project, job, gitLabApi.getJobApi().downloadArtifactsFile(projectId, job.getId()));

                //looking for Regular tests
                if (!isGherkinTestsExist) {
                    JunitTestResultsProvider testResultsProduce = JunitTestResultsProvider.getInstance(applicationSettings);
                    boolean testResultsExist = testResultsProduce.createTestList(project, job,
                            gitLabApi.getJobApi().downloadArtifactsFile(projectId, job.getId()));

                    if (!testResultsExist) {
                        String warning = String.format("No test results found by using the %s pattern",
                                applicationSettings.getConfig().getGitlabTestResultsFilePattern());
                        log.warn(warning);
                        return warning;
                    }
                }
            }
        }
        return "";
    }

    private long getPipelineId(JSONObject event) {
        if (isBuildEvent(event)) {
            return event.getLong("pipeline_id");
        } else if (isPipelineEvent(event)) {
            return event.getJSONObject("object_attributes").getLong("id");
        }
        throw new RuntimeException("The pipeline id can only be extracted from pipeline and build events.");
    }

    private void sendCodeCoverage(long projectId, Project project, Job job) throws GitLabApiException, IOException {
        Optional<Variable> coverageReportFilePathVar = VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                applicationSettings.getConfig().getGeneratedCoverageReportFilePathVariableName());

        Map<String, String> projectGroupVariables = VariablesHelper.getProjectGroupVariables(gitLabApi, project);

        if (coverageReportFilePathVar.isEmpty() && !projectGroupVariables.containsKey(
                applicationSettings.getConfig().getGeneratedCoverageReportFilePathVariableName())) {
            log.info("Variable for JaCoCo coverage report path not set. No coverage injection for this pipeline.");
        } else {
            String coverageReportFilePattern = coverageReportFilePathVar.isPresent()
                    ? coverageReportFilePathVar.get().getValue()
                    : projectGroupVariables.get(
                    applicationSettings.getConfig().getGeneratedCoverageReportFilePathVariableName());

            String octaneJobId = project.getPathWithNamespace().toLowerCase() + "/" + job.getName();
            String octaneBuildId = job.getId().toString();

            try (InputStream artifactsStream = gitLabApi.getJobApi()
                    .downloadArtifactsFile(projectId, job.getId())) {
                List<Map.Entry<String, ByteArrayInputStream>> coverageResultFiles =
                        TestResultsHelper.extractArtifacts(artifactsStream, "glob:" + coverageReportFilePattern);

                if (Objects.nonNull(coverageResultFiles) && coverageResultFiles.size() > 0) {
                    coverageResultFiles.forEach(
                            coverageFile -> OctaneSDK.getClients().forEach(client -> client.getCoverageService()
                                    .pushCoverage(octaneJobId, octaneBuildId, CoverageReportType.JACOCOXML,
                                            coverageFile.getValue())));
                }
            } catch (GitLabApiException | IOException exception) {
                log.error(exception.getMessage());
            }
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

        Project project = gitLabApi.getProjectApi().getProject(event.getJSONObject("project").getLong("id"));
        Map<String, String> projectGroupVariables = VariablesHelper.getProjectGroupVariables(gitLabApi, project);

        Optional<Variable> publishMergeRequests = VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                config.getPublishMergeRequestsVariableName());
        if (((publishMergeRequests.isEmpty() || !Boolean.parseBoolean(publishMergeRequests.get().getValue())) &&
                (!projectGroupVariables.containsKey(config.getPublishMergeRequestsVariableName()) ||
                        !Boolean.parseBoolean(
                                projectGroupVariables.get(config.getPublishMergeRequestsVariableName()))))) {
            return Response.ok().build();
        }

        Optional<Variable> destinationWSVar = VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                config.getDestinationWorkspaceVariableName());
        String destinationWS;

        if (destinationWSVar.isEmpty() && !projectGroupVariables.containsKey(config.getDestinationWorkspaceVariableName())) {
            String err = "Variable for destination workspace has not been set for project with id" + project.getId();
            log.error(err);
            return Response.ok().entity(err).build();
        } else if (destinationWSVar.isPresent()) {
            destinationWS = destinationWSVar.get().getValue();
        } else {
            destinationWS = projectGroupVariables.get(config.getDestinationWorkspaceVariableName());
        }

        Optional<Variable> useSSHFormatVar = VariablesHelper.getProjectVariable(gitLabApi, project.getId(),
                config.getUseSSHFormatVariableName());
        boolean useSSHFormat = useSSHFormatVar.isPresent() && Boolean.parseBoolean(useSSHFormatVar.get().getValue()) ||
                projectGroupVariables.containsKey(config.getUseSSHFormatVariableName()) &&
                        Boolean.parseBoolean(projectGroupVariables.get(config.getUseSSHFormatVariableName()));

        String repoUrl = useSSHFormat ? project.getSshUrlToRepo() : project.getHttpUrlToRepo();

        long mergeRequestId = getEventTargetObjectId(event);
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
                destinationWS);

        return Response.ok().build();
    }

    private CIEvent getScmEvent(JSONObject event) {
        long buildCiId = getEventTargetObjectId(event);
        SCMData scmData = getScmData(event);
        boolean isScmNull = scmData == null;

        return !isScmNull ? dtoFactory.newDTO(CIEvent.class)
                .setProjectDisplayName(getCiDisplayName(event))
                .setEventType(CIEventType.SCM)
                .setBuildCiId(Long.toString(buildCiId))
                .setNumber(null)
                .setProject(getCiFullName(event))
                .setResult(null)
                .setStartTime(null)
                .setEstimatedDuration(null)
                .setDuration(null)
                .setScmData(scmData)
                .setCauses(getCauses(event, false))
                .setPhaseType(null) :
                null;
    }


    private CIEvent getCIEvent(JSONObject event) {
        CIEventType eventType = getEventType(event);
        long buildCiId = getEventTargetObjectId(event);

        Object duration = getDuration(event);
        Long startTime = getStartTime(event, duration);

        boolean isScmNull = true;
        if (isPipelineEvent(event)) {
            if (eventType != CIEventType.STARTED) {
                String GITLAB_BLANK_SHA = "0000000000000000000000000000000000000000";
                isScmNull = event.getJSONObject("object_attributes").getString("before_sha").equals(GITLAB_BLANK_SHA);
            }
        }

        return dtoFactory.newDTO(CIEvent.class)
                .setProjectDisplayName(getCiDisplayName(event))
                .setEventType(eventType)
                .setBuildCiId(Long.toString(buildCiId))
                .setNumber(Long.toString(buildCiId))
                .setProject(getCiFullName(event))
                .setResult(eventType == CIEventType.STARTED || eventType == CIEventType.DELETED ? null : convertCiBuildResult(getStatus(event)))
                .setStartTime(startTime)
                .setEstimatedDuration(null)
                .setDuration(calculateDuration(eventType, duration))
                .setScmData(null)
                .setCauses(getCauses(event, isScmNull))
                .setPhaseType(isPipelineEvent(event) ? PhaseType.POST : PhaseType.INTERNAL);
    }

    private Long calculateDuration(CIEventType eventType, Object duration) {
        if (eventType == CIEventType.STARTED || Objects.equals(duration, null)) return 0L;

        if (duration instanceof Double) return Math.round(1000 * (Double) duration);
        if (duration instanceof BigDecimal) return Long.valueOf(Math.round(1000 * ((BigDecimal) duration).intValue()));

        return Long.valueOf(Math.round(1000 * (Integer) duration));

        // return Math.round(duration instanceof Double ? 1000* (Double) duration : 1000 * (Integer) duration);

    }

    private Long getStartTime(JSONObject event, Object duration) {
        Long startTime = getTime(event, "started_at");
        if (startTime == null) {
            try {
                startTime = getTime(event, "finished_at") - Long.parseLong(duration.toString());
            } catch (Exception e) {
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
            cause.setProject(getProjectCiId(event)); ///
            cause.setBuildCiId(Long.toString(getRootId(event)));
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
        if (isMergeRequestEvent(event)) {
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

    private String getCiDisplayName(JSONObject event) {
        if (isPipelineEvent(event)) {
            return getBranchName(event);
        } else if (isDeleteBranchEvent(event)) {
            return getBranchName(event);
        } else {
            return event.getString("build_name");
        }
    }

    private String getProjectCiId(JSONObject event) {
        return ParsedPath.PIPELINE_JOB_CI_ID_PREFIX + getProjectFullPath(event) + "/" + getConvertedBranchName(event);
    }

    private String getCiFullName(JSONObject event) {

        if (isPipelineEvent(event) || isDeleteBranchEvent(event)) {
            return getProjectCiId(event);
        }

        return getProjectFullPath(event) + "/" + event.getString("build_name");
    }

    private String getConvertedBranchName(JSONObject event) {
        return ParsedPath.convertBranchName(getBranchName(event));
    }

    private String getBranchName(JSONObject event) {
        if (isPipelineEvent(event)) {
            return event.getJSONObject("object_attributes").getString("ref");
        } else if (isDeleteBranchEvent(event)) {
            String ref = event.getString("ref");
            return ref.substring("refs/heads/".length());
        }
        return event.getString("ref");
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

    private long getRootId(JSONObject event) {
        return isPipelineEvent(event) ? event.getJSONObject("object_attributes").getLong("id") : event.getJSONObject("commit").getLong("id");
    }

    private SCMData getScmData(JSONObject event) {
        try {
            long projectId = event.getJSONObject("project").getLong("id");
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
            repo.setBranch(getBranchName(event));
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

    private long getEventTargetObjectId(JSONObject event) {
        if (isMergeRequestEvent(event)) {
            return event.getJSONObject("object_attributes").getLong("iid");
        } else if (isPipelineEvent(event)) {
            return event.getJSONObject("object_attributes").getLong("id");
        } else if (isDeleteBranchEvent(event)) {
            return event.getLong("project_id");
        } else {
            return event.getLong("build_id");
        }
    }

    private MergeRequestEventType getMREventType(JSONObject event) {
        String mergeRequestEventStatus = getStatus(event);
        switch (mergeRequestEventStatus) {
            case "open":
                return MergeRequestEventType.OPEN;
            case "update":
                return MergeRequestEventType.UPDATE;
            case "close":
                return MergeRequestEventType.CLOSE;
            case "reopen":
                return MergeRequestEventType.REOPEN;
            case "merge":
                return MergeRequestEventType.MERGE;
            default:
                return MergeRequestEventType.UNKNOWN;
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

    private boolean isBuildEvent(JSONObject event) {
        return event.getString("object_kind").equals("build");
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
