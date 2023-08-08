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

package com.microfocus.octane.gitlab.services;

import com.hp.octane.integrations.CIPluginServices;
import com.hp.octane.integrations.OctaneConfiguration;
import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.configuration.CIProxyConfiguration;
import com.hp.octane.integrations.dto.general.*;
import com.hp.octane.integrations.dto.parameters.CIParameter;
import com.hp.octane.integrations.dto.parameters.CIParameters;
import com.hp.octane.integrations.dto.pipelines.PipelineNode;
import com.hp.octane.integrations.dto.scm.Branch;
import com.hp.octane.integrations.dto.snapshots.CIBuildResult;
import com.hp.octane.integrations.dto.snapshots.CIBuildStatus;
import com.hp.octane.integrations.dto.tests.*;
import com.hp.octane.integrations.exceptions.PermissionException;
import com.hp.octane.integrations.executor.TestsToRunConverter;
import com.hp.octane.integrations.executor.TestsToRunConverterResult;
import com.hp.octane.integrations.executor.TestsToRunConvertersFactory;
import com.hp.octane.integrations.executor.TestsToRunFramework;
import com.hp.octane.integrations.services.configurationparameters.EncodeCiJobBase64Parameter;
import com.hp.octane.integrations.services.configurationparameters.factory.ConfigurationParameterFactory;
import com.hp.octane.integrations.utils.SdkConstants;
import com.microfocus.octane.gitlab.app.Application;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.helpers.*;
import com.microfocus.octane.gitlab.model.ConfigStructure;
import com.microfocus.octane.gitlab.testresults.JunitTestResultsProvider;
import com.microfocus.octane.gitlab.helpers.TestResultsHelper;

import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Job;
import org.gitlab4j.api.models.Pipeline;
import org.gitlab4j.api.models.Variable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.xml.transform.TransformerConfigurationException;
import java.io.*;
import java.net.URL;

import java.util.*;
import java.util.stream.Collectors;

import static com.microfocus.octane.gitlab.helpers.PasswordEncryption.PREFIX;

@Component
@Scope("singleton")
public class OctaneServices extends CIPluginServices {
    private static final Logger log = LogManager.getLogger(OctaneServices.class);
    private static final DTOFactory dtoFactory = DTOFactory.getInstance();

    private static GitLabApiWrapper gitLabApiWrapper;
    private static ApplicationSettings applicationSettings;
    private static GitlabServices gitlabServices;

    //    private final Transformer nunitTransformer = TransformerFactory.newInstance().newTransformer(new StreamSource(this.getClass().getClassLoader().getResourceAsStream("hudson/plugins/nunit/" + NUNIT_TO_JUNIT_XSLFILE_STR)));
    private static GitLabApi gitLabApi;
    private final String RUNNING_STATUS = "running";
    private final String PENDING_STATUS = "pending";
    private final String TESTS_TO_RUN_PARAM_NAME = "testsToRun";
    private final String TEST_RUNNER_BRANCH_PARAM_NAME = "testRunnerBranch";
    private final String TEST_RUNNER_FRAMEWORK_PARAM_NAME = "testRunnerFramework";
    private final long NO_SUCH_PIPELINE = -1;

    @Autowired
    public OctaneServices() throws TransformerConfigurationException {
    }

    @Override
    public CIServerInfo getServerInfo() {
        return dtoFactory.newDTO(CIServerInfo.class)
                .setInstanceId(applicationSettings.getConfig().getCiServerIdentity())
                .setSendingTime(System.currentTimeMillis())
                .setType(ApplicationSettings.getCIServerType())
                .setUrl(applicationSettings.getConfig().getGitlabLocation())
                .setVersion(Application.class.getPackage().getImplementationVersion());
    }

    @Override
    public CIPluginInfo getPluginInfo() {
        return dtoFactory.newDTO(CIPluginInfo.class)
                .setVersion(ApplicationSettings.getPluginVersion());
    }

    /* @Override*/
    public OctaneConfiguration getOctaneConfiguration() {
        OctaneConfiguration result = null;
        try {
            ConfigStructure config = applicationSettings.getConfig();
            if (config != null && config.getOctaneLocation() != null && !config.getOctaneLocation().isEmpty()) {
                String octaneApiClientSecret = config.getOctaneApiClientSecret();
                if (octaneApiClientSecret != null && octaneApiClientSecret.startsWith(PREFIX)) {
                    try {
                        octaneApiClientSecret = PasswordEncryption.decrypt(octaneApiClientSecret.substring(PREFIX.length()));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                result = OctaneConfiguration.createWithUiLocation(config.getCiServerIdentity(), config.getOctaneLocation());
                result.setClient(config.getOctaneApiClientID());
                result.setSecret(octaneApiClientSecret);
                ConfigurationParameterFactory.addParameter(result, EncodeCiJobBase64Parameter.KEY, "true");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }

//    @Override
//    public File getAllowedOctaneStorage() {
//        File sdkStorage = new File("sdk_storage");
//        boolean available = sdkStorage.exists();
//        if (!available) {
//            available = sdkStorage.mkdirs();
//        }
//        return available ? sdkStorage : null;
//    }

    @Override
    public CIProxyConfiguration getProxyConfiguration(URL targetUrl) {
        try {
            CIProxyConfiguration result = null;
            if (ProxyHelper.isProxyNeeded(applicationSettings, targetUrl)) {

                log.debug("proxy is required for host " + targetUrl);

                ConfigStructure config = applicationSettings.getConfig();
                String protocol = targetUrl.getProtocol();
                URL proxyUrl = new URL(config.getProxyField(protocol, "proxyUrl"));
                String proxyPassword = config.getProxyField(protocol, "proxyPassword");
                if (proxyPassword != null && proxyPassword.startsWith(PREFIX)) {
                    try {
                        proxyPassword = PasswordEncryption.decrypt(proxyPassword.substring(PREFIX.length()));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
                result = dtoFactory.newDTO(CIProxyConfiguration.class)
                        .setHost(proxyUrl.getHost())
                        .setPort(proxyUrl.getPort())
                        .setUsername(config.getProxyField(protocol, "proxyUser"))
                        .setPassword(proxyPassword);
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to return the proxy configuration, using null as default.", e);
            return null;
        }
    }


    @Override
    public CIJobsList getJobsList(boolean includeParameters, Long workspaceId) {
        try {
            CIJobsList jobList = gitlabServices.getJobList(includeParameters,workspaceId);
            if (jobList.getJobs().length < 1) {
                log.warn("IMPORTANT: The integration user has no project member permissions");
            }
            return jobList;
        } catch (Exception e) {
            log.warn("Failed to return the job list, using null as default.", e);
            return null;
        }
    }

    @Override
    public PipelineNode getPipeline(String rootJobCiId) {
        try {
            boolean isMultiBranch = checkIfMultiBranchParentId(rootJobCiId);
            return gitlabServices.createStructure(rootJobCiId, isMultiBranch);
        } catch (Exception e) {
            log.warn("Failed to return the pipeline, using null as default.", e);
            return null;
        }
    }

    private boolean checkIfMultiBranchParentId(String rootJobCiId) {

        try {
            ParsedPath parsedPath = new ParsedPath(rootJobCiId, gitLabApi, PathType.MULTI_BRUNCH);
            gitLabApi.getProjectApi().getProject(parsedPath.getFullPathOfProject());
        } catch (GitLabApiException e) {
            if (e.getHttpStatus() == HttpStatus.SC_NOT_FOUND) {
                return false;
            }
        }

        //else ?? ??
        return true;
    }

    @Override
    public String getParentJobName(String jobId) {
        return ParsedPath.cutBranchFromPath(jobId);
    }

//    @Override
//    public SnapshotNode getSnapshotLatest(String jobCiId, boolean subTree) {
//        return snapshotsFactory.createSnapshot(jobCiId);
//    }
//
//    //  TODO: implement
//    @Override
//    public SnapshotNode getSnapshotByNumber(String jobCiId, String buildCiId, boolean subTree) {
//        return null;
//    }
//

    @Override
    public void runPipeline(String jobCiId, CIParameters ciParameters) {
        try {
            //check whether this user is permitted to run the pipeline
            boolean canUserRunPipeline = applicationSettings.getConfig().canRunPipeline();
            if (!canUserRunPipeline) {
                log.error("Current user is not permitted to run pipelines");
                throw new PermissionException(HttpStatus.SC_FORBIDDEN);
            }

            // If testsToRun parameter is present this means that the pipeline is a test runner
            List<CIParameter> parameters = ciParameters.getParameters();
            Optional<String> testsToRunParam =
                    parameters.stream().filter(param -> param.getName().equals(TESTS_TO_RUN_PARAM_NAME))
                            .map(this::getStringValueFromParam).findFirst();

            final StringBuilder jobCiIdBuilder = new StringBuilder(jobCiId);
            testsToRunParam.ifPresent(testsToRun -> {
                ciParameters.setParameters(getCiParamsWithTestsToRun(jobCiIdBuilder.toString(), parameters, testsToRun));
                jobCiIdBuilder.append(getCiBranch(jobCiIdBuilder.toString()).map(branch -> "/" + branch).orElse(""));
            });

            jobCiId = jobCiIdBuilder.toString();
            ParsedPath parsedPath = new ParsedPath(jobCiId, gitLabApi, PathType.PIPELINE);

            gitLabApi.getPipelineApi().createPipeline(
                    parsedPath.getPathWithNameSpace(),
                    parsedPath.getCurrentBranchOrDefault(),
                    VariablesHelper.convertParametersToVariables(ciParameters));

        } catch (GitLabApiException e) {
            log.error("Failed to start a pipeline", e);
            throw new RuntimeException(e);
        }
    }

    private List<CIParameter> getCiParamsWithTestsToRun(String jobCiId, List<CIParameter> parameters, String testsToRun) {
        List<CIParameter> resultedParams = new ArrayList<>();

        Optional<String> frameworkParam = getPipeline(jobCiId).getParameters().stream()
                .filter(param -> param.getName().equalsIgnoreCase(TEST_RUNNER_FRAMEWORK_PARAM_NAME))
                .map(this::getStringValueFromParam).findFirst();

        frameworkParam.ifPresentOrElse(framework -> {
            String convertedTestsToRun = getConvertedTestsToRun(testsToRun, framework);

            resultedParams.addAll(parameters.stream().peek(parameter -> {
                if (parameter.getName().equals(TESTS_TO_RUN_PARAM_NAME)) {
                    parameter.setValue(convertedTestsToRun);
                }
            }).collect(Collectors.toList()));
        }, () -> {
            RuntimeException ex = new RuntimeException("Framework parameter not defined for test runner.");
            log.error(ex.getMessage(), ex);
            throw ex;
        });

        return resultedParams;
    }

    private Optional<String> getCiBranch(String jobCiId) {
        return getPipeline(jobCiId).getParameters().stream()
                .filter(param -> param.getName().equalsIgnoreCase(TEST_RUNNER_BRANCH_PARAM_NAME))
                .map(this::getStringValueFromParam).filter(branch -> !branch.isEmpty() && !branch.isBlank())
                .findFirst();
    }

    private String getStringValueFromParam(CIParameter parameter) {
        return parameter.getValue() != null
                ? parameter.getValue().toString()
                : parameter.getDefaultValue().toString();
    }

    private String getConvertedTestsToRun(String testsToRun, String framework) {
        TestsToRunFramework frameworkToUse;
        try {
            frameworkToUse = TestsToRunFramework.fromValue(framework);
        } catch (IllegalArgumentException iae) {
            RuntimeException ex = new RuntimeException(framework + " is not a valid framework.");
            log.error(ex.getMessage(), ex);
            throw ex;
        }

        TestsToRunConverter converter = TestsToRunConvertersFactory.createConverter(frameworkToUse);

        TestsToRunConverterResult result = converter.convert(testsToRun, "", null);
        return result.getConvertedTestsString();
    }

    @Override
    public CIBranchesList getBranchesList(String jobCiId, String filterBranchName) {
        ParsedPath parsedPath = new ParsedPath(jobCiId, gitLabApi, PathType.PIPELINE);

        try {
            List<Branch> result = new ArrayList<>();
            String path = parsedPath.getPathWithNameSpace();

            List<Branch> branches = gitLabApi.getRepositoryApi().getBranches(path, filterBranchName)
                    .stream().map(branch -> dtoFactory.newDTO(Branch.class)
                            .setName(branch.getName())
                            .setInternalId(ParsedPath.convertBranchName(branch.getName())))
                    .collect(Collectors.toList());
            result.addAll(branches);

            List<Branch> tags = gitLabApi.getTagsApi().getTags(path, null, null, filterBranchName)
                    .stream().map(tag -> dtoFactory.newDTO(Branch.class)
                            .setName(tag.getName())
                            .setInternalId(ParsedPath.convertBranchName(tag.getName())))
                    .collect(Collectors.toList());
            result.addAll(tags);

            return dtoFactory.newDTO(CIBranchesList.class)
                    .setBranches(result);
        } catch (GitLabApiException e) {
            log.error("Failed to get list of branches", e);
            throw new RuntimeException(e);
        }
    }


    @Override
    public InputStream getTestsResult(String jobFullName, String buildNumber) {
        TestsResult result = dtoFactory.newDTO(TestsResult.class);
        try {
            ParsedPath project = new ParsedPath(ParsedPath.cutLastPartOfPath(jobFullName), gitLabApi, PathType.PROJECT);
            Job job = gitLabApi.getJobApi().getJob(project.getPathWithNameSpace(), Long.parseLong(buildNumber));

            //report gherkin test results

            File mqmTestResultsFile = TestResultsHelper.getMQMTestResultsFilePath(project.getId(), job.getId(), applicationSettings.getConfig().getTestResultsOutputFolderPath());
            InputStream output = null;


            if (mqmTestResultsFile.exists() && mqmTestResultsFile.length() > 0) {
                log.info(String.format("get Gherkin Tests Result of %s from  %s, file exist=%s",
                        project.getDisplayName(), mqmTestResultsFile.getAbsolutePath(), mqmTestResultsFile.exists()));
                try {
                    output = mqmTestResultsFile.exists() && mqmTestResultsFile.length() > 0 ? new FileInputStream(mqmTestResultsFile.getAbsolutePath()) : null;
                } catch (IOException e) {
                    log.error("failed to get gherkin test results for  " + project.getDisplayName() + " #" + job.getId() + " from " + mqmTestResultsFile.getAbsolutePath());
                }
                return output;
            }

            //if there is no test results for gherkin - report other test results

            BuildContext buildContext = dtoFactory.newDTO(BuildContext.class)
                    .setJobId(project.getFullPathOfProjectWithBranch().toLowerCase())
                    .setJobName(project.getPathWithNameSpace())
                    .setBuildId(job.getId().toString())
                    .setBuildName(job.getId().toString())
                    .setServerId(applicationSettings.getConfig().getCiServerIdentity());
            result = result.setBuildContext(buildContext);

            JunitTestResultsProvider junitTestResultsProvider = JunitTestResultsProvider.getInstance(applicationSettings);
            InputStream artifactFiles = gitLabApi.getJobApi().downloadArtifactsFile(project.getId(), job.getId());

            List<TestRun> tests = junitTestResultsProvider.createAndGetTestList(
                    artifactFiles);

            if (tests != null && !tests.isEmpty()) {
                result.setTestRuns(tests);
            } else {
                log.warn("Unable to extract test results from files defined by the %s pattern. Check the pattern correctness");
            }
        } catch (Exception e) {
            log.warn("Failed to return test results", e);
        }
        return dtoFactory.dtoToXmlStream(result);
    }

    @Autowired
    public void setApplicationSettings(ApplicationSettings applicationSettings) {
        this.applicationSettings = applicationSettings;
    }

    @Autowired
    public void setGitlabServices(GitlabServices gitlabServices) {
        this.gitlabServices = gitlabServices;
    }

    @Autowired
    public void setGitLabApi(GitLabApiWrapper gitLabApiWrapper) {
        this.gitLabApiWrapper = gitLabApiWrapper;
        gitLabApi = gitLabApiWrapper.getGitLabApi();
    }

    public GitLabApiWrapper getGitLabApiWrapper() {
        return gitLabApiWrapper;
    }

    public GitlabServices getGitLabService() {
        return gitlabServices;
    }

    @Override
    public void stopPipelineRun(String jobId, CIParameters ciParameters) {
        try {
            ParsedPath parsedPath = new ParsedPath(jobId, gitLabApi, PathType.PIPELINE);

            List<Pipeline> pipelines = gitLabApi.getPipelineApi()
                    .getPipelines(parsedPath.getPathWithNameSpace());

            CIParameter octaneExecutionId = ciParameters.getParameters().stream()
                    .filter(parameter -> parameter.getName().equals(SdkConstants.JobParameters.OCTANE_AUTO_ACTION_EXECUTION_ID_PARAMETER_NAME))
                    .findAny().orElse(null);

            if (octaneExecutionId != null) {
                long pipelineIdWithParameter = getIdWhereParameter(parsedPath.getPathWithNameSpace(),
                        pipelines, octaneExecutionId);

                gitLabApi.getPipelineApi().cancelPipelineJobs(parsedPath.getPathWithNameSpace(),
                        pipelineIdWithParameter);
            }
        } catch (GitLabApiException e) {
            log.error("Failed to stop the pipeline run", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public CIBuildStatusInfo getJobBuildStatus(String jobCiId, String parameterName, String parameterValue) {
        ParsedPath parsedPath = new ParsedPath(jobCiId, gitLabApi, PathType.PIPELINE);
        try {
            List<Pipeline> pipelines = gitLabApi.getPipelineApi()
                    .getPipelines(parsedPath.getPathWithNameSpace());

            Optional<Pipeline> chosenPipeline = pipelines.stream().map(pipeline -> {
                try {
                    List<Variable> pipelineVariables = gitLabApi.getPipelineApi()
                            .getPipelineVariables(parsedPath.getPathWithNameSpace(), pipeline.getId());

                    for (Variable variable : pipelineVariables) {
                        if (variable.getKey().equals(parameterName) && variable.getValue().equals(parameterValue)) {
                            return pipeline;
                        }
                    }
                    return null;
                } catch (GitLabApiException e) {
                    log.error("Failed to get variables from pipeline", e);
                    throw new RuntimeException(e);
                }
            }).filter(Objects::nonNull).findAny();

            if (chosenPipeline.isPresent()) {
                String status = chosenPipeline.get().getStatus().toValue();
                CIBuildStatus currentCIBuildStatus = getCIBuildStatus(status);
                Optional<CIBuildStatus> buildStatus = Arrays.stream(CIBuildStatus.values())
                        .filter(ciBuildStatus -> Objects.equals(ciBuildStatus, currentCIBuildStatus))
                        .findAny();

                if (!buildStatus.isPresent()) {
                    throw new RuntimeException("Failed to get the correct build status");
                }
                return dtoFactory.newDTO(CIBuildStatusInfo.class)
                        .setJobCiId(ParsedPath.PIPELINE_JOB_CI_ID_PREFIX + parsedPath.getPathWithNameSpace() + "/" + ParsedPath.convertBranchName(chosenPipeline.get().getRef()))
                        .setBuildStatus(buildStatus.get())
                        .setBuildCiId(getBuildCiId(chosenPipeline.get()))
                        .setParamName(parameterName)
                        .setParamValue(parameterValue)
                        .setResult(getCiBuildResult(status));
            }
            throw new RuntimeException("Failed to get information about the pipeline");
        } catch (GitLabApiException e) {
            log.error("Failed to get job build status of the pipeline run", e);
            throw new RuntimeException(e);
        }
    }

    private long getIdWhereParameter(String cleanedPath, List<Pipeline> pipelines, CIParameter executionId) {
        pipelines = pipelines.stream()
                .filter(this::pipelineInQueue)
                .collect(Collectors.toList());

        return pipelines.stream().map(pipeline -> {
                    List<Variable> pipelineVariables = null;
                    try {
                        pipelineVariables = gitLabApi.getPipelineApi()
                                .getPipelineVariables(cleanedPath, pipeline.getId());

                        for (Variable variable : pipelineVariables) {
                            if (variable.getValue().equals(executionId.getValue().toString())) {
                                return pipeline.getId();
                            }
                        }
                    } catch (GitLabApiException e) {
                        log.error("Failed to get parameters of the pipeline", e);
                    }
                    return NO_SUCH_PIPELINE;
                }).filter(integer -> !Objects.equals(integer, NO_SUCH_PIPELINE))
                .findAny().orElse(NO_SUCH_PIPELINE);
    }

    private boolean pipelineInQueue(Pipeline pipeline) {
        return pipeline.getStatus().toString().equals(RUNNING_STATUS)
                || pipeline.getStatus().toString().equals(PENDING_STATUS);
    }

    private CIBuildStatus getCIBuildStatus(String statusStr) {
        if (Arrays.asList(new String[]{"process", "enqueue", "pending", "created"}).contains(statusStr)) {
            return CIBuildStatus.QUEUED;
        } else if (Arrays.asList(new String[]{"success", "failed", "canceled", "skipped"}).contains(statusStr)) {
            return CIBuildStatus.FINISHED;
        } else if (Arrays.asList(new String[]{"running", "manual"}).contains(statusStr)) {
            return CIBuildStatus.RUNNING;
        } else {
            return CIBuildStatus.UNAVAILABLE;
        }
    }

    private CIBuildResult getCiBuildResult(String status) {
        if (status.equals("success")) return CIBuildResult.SUCCESS;
        if (status.equals("failed")) return CIBuildResult.FAILURE;
        if (status.equals("drop") || status.equals("skipped") || status.equals("canceled"))
            return CIBuildResult.ABORTED;
        if (status.equals("unstable")) return CIBuildResult.UNSTABLE;
        return CIBuildResult.UNAVAILABLE;
    }

    private String getBuildCiId(Pipeline pipeline) {
        return String.valueOf(pipeline.getId());
    }

}
