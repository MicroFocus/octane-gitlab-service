package com.microfocus.octane.gitlab.services;

import com.hp.octane.integrations.CIPluginServices;
import com.hp.octane.integrations.OctaneConfiguration;
import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.configuration.CIProxyConfiguration;
import com.hp.octane.integrations.dto.general.CIJobsList;
import com.hp.octane.integrations.dto.general.CIPluginInfo;
import com.hp.octane.integrations.dto.general.CIServerInfo;
import com.hp.octane.integrations.dto.parameters.CIParameters;
import com.hp.octane.integrations.dto.pipelines.PipelineNode;
import com.hp.octane.integrations.dto.tests.*;
import com.hp.octane.integrations.services.configurationparameters.EncodeCiJobBase64Parameter;
import com.hp.octane.integrations.services.configurationparameters.factory.ConfigurationParameterFactory;
import com.microfocus.octane.gitlab.app.Application;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.helpers.*;
import com.microfocus.octane.gitlab.model.ConfigStructure;
import com.microfocus.octane.gitlab.model.junit5.Testcase;
import com.microfocus.octane.gitlab.model.junit5.Testsuite;
import com.microfocus.octane.gitlab.model.junit5.Testsuites;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.microfocus.octane.gitlab.helpers.PasswordEncryption.PREFIX;
import static hudson.plugins.nunit.NUnitReportTransformer.NUNIT_TO_JUNIT_XSLFILE_STR;

@Component
@Scope("singleton")
public class OctaneServices extends CIPluginServices {
    private static final Logger log = LogManager.getLogger(OctaneServices.class);
    private static final DTOFactory dtoFactory = DTOFactory.getInstance();

    private static GitLabApiWrapper gitLabApiWrapper;
    private static ApplicationSettings applicationSettings;
    private static GitlabServices gitlabServices;

    private final Transformer nunitTransformer = TransformerFactory.newInstance().newTransformer(new StreamSource(this.getClass().getClassLoader().getResourceAsStream("hudson/plugins/nunit/" + NUNIT_TO_JUNIT_XSLFILE_STR)));
    private static GitLabApi gitLabApi;

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
            CIJobsList jobList = gitlabServices.getJobList();
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
            return gitlabServices.createStructure(rootJobCiId);
        } catch (Exception e) {
            log.warn("Failed to return the pipeline, using null as default.", e);
            return null;
        }
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

    @Override
    public InputStream getTestsResult(String jobFullName, String buildNumber) {
        TestsResult result = dtoFactory.newDTO(TestsResult.class);
        try {
            ParsedPath project = new ParsedPath(ParsedPath.cutLastPartOfPath(jobFullName), gitLabApi, PathType.PROJECT);
            Job job = gitLabApi.getJobApi().getJob(project.getPathWithNameSpace(), Integer.parseInt(buildNumber));
            BuildContext buildContext = dtoFactory.newDTO(BuildContext.class)
                    .setJobId(project.getFullPathOfProjectWithBranch().toLowerCase())
                    .setJobName(project.getPathWithNameSpace())
                    .setBuildId(job.getId().toString())
                    .setBuildName(job.getId().toString())
                    .setServerId(applicationSettings.getConfig().getCiServerIdentity());
            result = result.setBuildContext(buildContext);
            List<TestRun> tests = createTestList(project.getId(), job);
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

    public List<TestRun> createTestList(Integer projectId, Job job) {
        List<TestRun> result = new ArrayList<>();
        try {
            if (job.getArtifactsFile() != null) {
                List<Map.Entry<String, ByteArrayInputStream>> artifacts = extractArtifacts(gitLabApi.getJobApi().downloadArtifactsFile(projectId, job.getId()));
                JAXBContext jaxbContext = JAXBContext.newInstance(Testsuites.class);
                for (Map.Entry<String, ByteArrayInputStream> artifact : artifacts) {
                    try {
                        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                        DocumentBuilder db = dbf.newDocumentBuilder();
                        Document doc = db.parse(new InputSource(artifact.getValue()));
                        String rootTagName = doc.getDocumentElement().getTagName().toLowerCase();
                        artifact.getValue().reset();
                        switch (rootTagName) {
                            case "testsuites":
                            case "testsuite":
                                unmarshallAndAddToResults(result, jaxbContext, artifact.getValue());
                                break;
                            case "test-run":
                            case "test-results":
                                ByteArrayOutputStream os = new ByteArrayOutputStream();
                                nunitTransformer.transform(new StreamSource(artifact.getValue()), new StreamResult(os));
                                unmarshallAndAddToResults(result, jaxbContext, new ByteArrayInputStream(os.toByteArray()));
                                break;
                            default:
                                log.error(String.format("Artifact %s: unknown test result format that starts with the <%s> tag", artifact.getKey(), rootTagName));
                                break;
                        }
                    } catch (Exception e) {
                        log.error("Failed to create a test result list based on the job artifact: " + artifact.getKey(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to create a test list based on the job artifacts", e);
        }
        return result;
    }

    private void unmarshallAndAddToResults(List<TestRun> result, JAXBContext jaxbContext, ByteArrayInputStream artifact) throws JAXBException {
        Object ots = jaxbContext.createUnmarshaller().unmarshal(artifact);
        if (ots instanceof Testsuites) {
            ((Testsuites) ots).getTestsuite().forEach(ts -> ts.getTestcase().forEach(tc -> addTestCase(result, ts, tc)));
        } else if (ots instanceof Testsuite) {
            ((Testsuite) ots).getTestcase().forEach(tc -> addTestCase(result, (Testsuite) ots, tc));
        }
    }

    private List<Map.Entry<String, ByteArrayInputStream>> extractArtifacts(InputStream inputStream) {
        PathMatcher matcher = FileSystems.getDefault()
                .getPathMatcher(applicationSettings.getConfig().getGitlabTestResultsFilePattern());
        File tempFile = null;
        try {
            tempFile = File.createTempFile("gitlab-artifact", ".zip");

            try (OutputStream os = new FileOutputStream(tempFile)) {
                StreamHelper.copyStream(inputStream, os);
            }

            inputStream.close();

            ZipFile zipFile = new ZipFile(tempFile);

            List<Map.Entry<String, ByteArrayInputStream>> result = new LinkedList<>();
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();

                if (matcher.matches(Paths.get(entry.getName()))) {
                    ByteArrayOutputStream entryStream = new ByteArrayOutputStream();

                    try (InputStream zipEntryStream = zipFile.getInputStream(entry)) {
                        StreamHelper.copyStream(zipEntryStream, entryStream);
                    }
                    result.add(Pair.of(entry.getName(), new ByteArrayInputStream(entryStream.toByteArray())));
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("Failed to extract the real artifacts, using null as default.", e);
            return null;
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    private void addTestCase(List<TestRun> result, Testsuite ts, Testcase tc) {
        TestRunResult testResultStatus;
        if (tc.getSkipped() != null && tc.getSkipped().trim().length() > 0) {
            testResultStatus = TestRunResult.SKIPPED;
        } else if (tc.getFailure().size() > 0) {
            testResultStatus = TestRunResult.FAILED;
        } else {
            testResultStatus = TestRunResult.PASSED;
        }

        TestRun tr = dtoFactory.newDTO(TestRun.class)
                .setModuleName("")
                .setPackageName(ts.getPackage())
                .setClassName(tc.getClassname())
                .setTestName(tc.getName())
                .setResult(testResultStatus)
                .setDuration(tc.getTime() != null ? Double.valueOf(tc.getTime()).longValue() * 1000 : 1);
        if (tc.getError() != null && tc.getError().size() > 0) {
            TestRunError error = dtoFactory.newDTO(TestRunError.class);
            error.setErrorMessage(tc.getError().get(0).getMessage());
            error.setErrorType(tc.getError().get(0).getType());
            error.setStackTrace(tc.getError().get(0).getContent());
            tr.setError(error);
        } else if (tc.getFailure() != null && tc.getFailure().size() > 0) {
            TestRunError error = dtoFactory.newDTO(TestRunError.class);
            error.setErrorMessage(tc.getFailure().get(0).getMessage());
            error.setErrorType(tc.getFailure().get(0).getType());
            error.setStackTrace(tc.getFailure().get(0).getContent());
            tr.setError(error);
        }
        result.add(tr);
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
}
