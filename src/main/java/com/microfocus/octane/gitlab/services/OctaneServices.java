package com.microfocus.octane.gitlab.services;

import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.configuration.CIProxyConfiguration;
import com.hp.octane.integrations.dto.configuration.OctaneConfiguration;
import com.hp.octane.integrations.dto.general.CIJobsList;
import com.hp.octane.integrations.dto.general.CIPluginInfo;
import com.hp.octane.integrations.dto.general.CIServerInfo;
import com.hp.octane.integrations.dto.pipelines.PipelineNode;
import com.hp.octane.integrations.dto.tests.*;
import com.hp.octane.integrations.exceptions.PermissionException;
import com.hp.octane.integrations.spi.CIPluginServicesBase;
import com.hp.octane.integrations.util.CIPluginSDKUtils;
import com.microfocus.octane.gitlab.app.Application;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.helpers.GitLabApiWrapper;
import com.microfocus.octane.gitlab.helpers.PasswordEncryption;
import com.microfocus.octane.gitlab.model.ConfigStructure;
import com.microfocus.octane.gitlab.model.junit5.Testcase;
import com.microfocus.octane.gitlab.model.junit5.Testsuite;
import com.microfocus.octane.gitlab.model.junit5.Testsuites;
import javafx.util.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Job;
import org.gitlab4j.api.models.Project;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.annotation.PostConstruct;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.microfocus.octane.gitlab.helpers.PasswordEncryption.PREFIX;
import static hudson.plugins.nunit.NUnitReportTransformer.NUNIT_TO_JUNIT_XSLFILE_STR;

@Component
@Scope("singleton")
public class OctaneServices extends CIPluginServicesBase {
    private static final Logger log = LogManager.getLogger(OctaneServices.class);
    private static final DTOFactory dtoFactory = DTOFactory.getInstance();

    private final GitLabApiWrapper gitLabApiWrapper;
    private final ApplicationSettings applicationSettings;
    private final GitlabServices gitlabServices;
    private final Transformer nunitTransformer = TransformerFactory.newInstance().newTransformer(new StreamSource(this.getClass().getClassLoader().getResourceAsStream("hudson/plugins/nunit/" + NUNIT_TO_JUNIT_XSLFILE_STR)));
    private GitLabApi gitLabApi;

    @Autowired
    public OctaneServices(GitLabApiWrapper gitLabApiWrapper, ApplicationSettings applicationSettings, GitlabServices gitlabServices) throws TransformerConfigurationException {
        this.gitLabApiWrapper = gitLabApiWrapper;
        this.applicationSettings = applicationSettings;
        this.gitlabServices = gitlabServices;
    }

    @PostConstruct
    public void initGitlabPluginServices() {
        gitLabApi = gitLabApiWrapper.getGitLabApi();
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

    @Override
    public OctaneConfiguration getOctaneConfiguration() {
        OctaneConfiguration result = null;
        try {
            ConfigStructure config = applicationSettings.getConfig();
            if (config != null && config.getOctaneLocation() != null && !config.getOctaneLocation().isEmpty() && config.getOctaneSharedspace() != null) {
                String octaneApiClientSecret = config.getOctaneApiClientSecret();
                if (octaneApiClientSecret != null && octaneApiClientSecret.startsWith(PREFIX)) {
                    try {
                        octaneApiClientSecret = PasswordEncryption.decrypt(octaneApiClientSecret.substring(PREFIX.length()));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
                result = dtoFactory.newDTO(OctaneConfiguration.class)
                        .setUrl(config.getOctaneLocation())
                        .setSharedSpace(config.getOctaneSharedspace())
                        .setApiKey(config.getOctaneApiClientID())
                        .setSecret(octaneApiClientSecret);
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
            if (isProxyNeeded(targetUrl)) {
                ConfigStructure config = applicationSettings.getConfig();
                log.info("proxy is required for host " + targetUrl);
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
    public CIJobsList getJobsList(boolean includeParameters) {
        try {
            CIJobsList jobList = gitlabServices.getJobList();
            if(jobList.getJobs().length < 1) {
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
    public void runPipeline(String jobCiId, String originalBody) {
        try {
            String[] buildIdParts = jobCiId.split(Pattern.quote("/"));
            Project project = gitLabApi.getProjectApi().getProject(buildIdParts[0].split(":")[1], buildIdParts[1]);
            gitLabApi.getPipelineApi().createPipeline(project.getId(), buildIdParts[2]);
        } catch (GitLabApiException e) {
            log.error("Failed to start a pipeline", e);
            throw new PermissionException(403);
        }
    }

    @Override
    public TestsResult getTestsResult(String projectId, String buildNumber) {
        TestsResult result = dtoFactory.newDTO(TestsResult.class);
        try {
            Project project = gitLabApi.getProjectApi().getProject(Integer.parseInt(projectId));
            Job job = gitLabApi.getJobApi().getJob(Integer.parseInt(projectId), Integer.parseInt(buildNumber));
            String jobFullName = project.getNamespace().getName() + "/" + project.getName() + "/" + job.getName();
            BuildContext buildContext = dtoFactory.newDTO(BuildContext.class)
                    .setJobId(jobFullName)
                    .setJobName(jobFullName)
                    .setBuildId(job.getId().toString())
                    .setBuildName(job.getId().toString())
                    .setServerId(applicationSettings.getConfig().getCiServerIdentity());
            result = result.setBuildContext(buildContext);
            List<TestRun> tests = createTestList(Integer.parseInt(projectId), job);
            if (tests != null && !tests.isEmpty()) {
                result.setTestRuns(tests);
            } else {
                log.warn(String.format("Unable to extract test results from files defined by the %s pattern. Check the pattern correctness"));
            }
        } catch (Exception e) {
            log.warn("Failed to return test results", e);
        }

        return result;
    }

    public List<TestRun> createTestList(Integer projectId, Job job) {
        List<TestRun> result = new ArrayList<>();
        try {
            if (job.getArtifactsFile() != null) {
                List<Pair<String, ByteArrayInputStream>> artifacts = extractArtifacts(gitLabApi.getJobApi().downloadArtifactsFile(projectId, job.getId()));
                JAXBContext jaxbContext = JAXBContext.newInstance(Testsuites.class);
                for (Pair<String, ByteArrayInputStream> artifact : artifacts) {
                    try {
                        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
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

    private List<Pair<String, ByteArrayInputStream>> extractArtifacts(InputStream inputStream) {
        PathMatcher matcher = FileSystems.getDefault()
                .getPathMatcher(applicationSettings.getConfig().getGitlabTestResultsFilePattern());
        try {
            ZipInputStream zis = new ZipInputStream(inputStream);
            List<Pair<String, ByteArrayInputStream>> result = new LinkedList<>();
            for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
                if (matcher.matches(Paths.get(entry.getName()))) {
                    while (zis.available() > 0) {
                        ByteArrayOutputStream entryStream = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = zis.read(buffer)) != -1) {
                            entryStream.write(buffer, 0, length);
                        }
                        result.add(new Pair<>(entry.getName(), new ByteArrayInputStream(entryStream.toByteArray())));
                    }
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("Failed to extract the real artifacts, using null as default.", e);
            return null;
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
                .setDuration(Double.valueOf(tc.getTime()).longValue() * 1000);
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

    private boolean isProxyNeeded(URL targetHost) {
        if (targetHost == null) return false;
        boolean result = false;
        ConfigStructure config = applicationSettings.getConfig();
        if (config.getProxyField(targetHost.getProtocol(), "proxyUrl") != null) {
            String nonProxyHostsStr = config.getProxyField(targetHost.getProtocol(), "nonProxyHosts");
            if (!CIPluginSDKUtils.isNonProxyHost(targetHost.getHost(), nonProxyHostsStr)) {
                result = true;
            }
        }
        return result;
    }
}
