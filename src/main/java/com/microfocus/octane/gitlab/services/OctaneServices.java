package com.microfocus.octane.gitlab.services;

import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.configuration.CIProxyConfiguration;
import com.hp.octane.integrations.dto.configuration.OctaneConfiguration;
import com.hp.octane.integrations.dto.general.CIJobsList;
import com.hp.octane.integrations.dto.general.CIPluginInfo;
import com.hp.octane.integrations.dto.general.CIServerInfo;
import com.hp.octane.integrations.dto.pipelines.PipelineNode;
import com.hp.octane.integrations.dto.tests.BuildContext;
import com.hp.octane.integrations.dto.tests.TestRun;
import com.hp.octane.integrations.dto.tests.TestRunResult;
import com.hp.octane.integrations.dto.tests.TestsResult;
import com.hp.octane.integrations.spi.CIPluginServicesBase;
import com.hp.octane.integrations.util.CIPluginSDKUtils;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.helpers.GitLabApiWrapper;
import com.microfocus.octane.gitlab.model.ConfigStructure;
import com.microfocus.octane.gitlab.model.junit5.Testcase;
import com.microfocus.octane.gitlab.model.junit5.Testsuite;
import com.microfocus.octane.gitlab.model.junit5.Testsuites;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Job;
import org.gitlab4j.api.models.Project;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.xml.bind.JAXBContext;
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

@Component
@Scope("singleton")
public class OctaneServices extends CIPluginServicesBase {
    private static final Logger log = LogManager.getLogger(OctaneServices.class);
    private static final DTOFactory dtoFactory = DTOFactory.getInstance();
    private static final String pluginVersion = "9.1.5";

    private final GitLabApiWrapper gitLabApiWrapper;
    private GitLabApi gitLabApi;
    private final ApplicationSettings applicationSettings;
    private final GitlabServices gitlabServices;

    @Autowired
    public OctaneServices(GitLabApiWrapper gitLabApiWrapper, ApplicationSettings applicationSettings, GitlabServices gitlabServices) {
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
                .setVersion(pluginVersion);
    }

    @Override
    public CIPluginInfo getPluginInfo() {
        return dtoFactory.newDTO(CIPluginInfo.class)
                .setVersion(ApplicationSettings.getPluginVersion());
    }

    @Override
    public OctaneConfiguration getOctaneConfiguration() {
        OctaneConfiguration result = null;
        ConfigStructure config = applicationSettings.getConfig();
        if (config != null && config.getOctaneLocation() != null && !config.getOctaneLocation().isEmpty() && config.getOctaneSharedspace() != null) {
            result = dtoFactory.newDTO(OctaneConfiguration.class)
                    .setUrl(config.getOctaneLocation())
                    .setSharedSpace(config.getOctaneSharedspace())
                    .setApiKey(config.getOctaneUsername())
                    .setSecret(config.getOctanePassword());
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
        log.info("get proxy configuration");
        CIProxyConfiguration result = null;
        if (isProxyNeeded(targetUrl)) {
            ConfigStructure config = applicationSettings.getConfig();
            log.info("proxy is required for host " + targetUrl);
            String protocol = targetUrl.getProtocol();
            result = dtoFactory.newDTO(CIProxyConfiguration.class)
                    .setHost(config.getProxyField(protocol, "proxyHost"))
                    .setPort(Integer.parseInt(config.getProxyField(protocol, "proxyPort")))
                    .setUsername(config.getProxyField(protocol, "proxyUser"))
                    .setPassword(config.getProxyField(protocol, "proxyPassword"));
        }
        return result;
    }


    @Override
    public CIJobsList getJobsList(boolean includeParameters) {
        try {
            return gitlabServices.gotJobList();
        } catch (Exception e) {
            log.catching(e);
            return null;
        }
    }

    @Override
    public PipelineNode getPipeline(String rootJobCiId) {
        try {
            return gitlabServices.createStructure(rootJobCiId);
        } catch (Exception e) {
            log.catching(e);
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
            log.catching(e);
        }
    }

    @Override
    public TestsResult getTestsResult(String projectId, String buildNumber) {
        TestsResult result = dtoFactory.newDTO(TestsResult.class);
        try {
            Project project = gitLabApi.getProjectApi().getProject(Integer.parseInt(projectId));
            Job job = gitLabApi.getJobApi().getJob(Integer.parseInt(projectId), Integer.parseInt(buildNumber));
            List<TestRun> tests = createTestList(Integer.parseInt(projectId), job);
            if (tests != null && !tests.isEmpty()) {
                String jobFullName = project.getNamespace().getName() + "/" + project.getName() + "/" + job.getName();
                BuildContext buildContext = dtoFactory.newDTO(BuildContext.class)
                        .setJobId(jobFullName)
                        .setJobName(jobFullName)
                        .setBuildId(job.getId().toString())
                        .setBuildName(job.getId().toString())
                        .setServerId(applicationSettings.getConfig().getCiServerIdentity());
                result.setBuildContext(buildContext)
                        .setTestRuns(tests);
            }
        } catch (GitLabApiException e) {
            log.catching(e);
        }

        return result;
    }

    private List<TestRun> createTestList(Integer projectId, Job job) {
        List<TestRun> result = new ArrayList<>();
        try {
            if (job.getArtifactsFile() != null) {
                List<ByteArrayInputStream> artifacts = extractArtifacts(gitLabApi.getJobApi().downloadArtifactsFile(projectId, job.getId()));
                JAXBContext jaxbContext = JAXBContext.newInstance(Testsuites.class);
                for (ByteArrayInputStream artifact : artifacts) {
                    Object ots = jaxbContext.createUnmarshaller().unmarshal(artifact);
                    if (ots instanceof Testsuites) {
                        ((Testsuites) ots).getTestsuite().forEach(ts -> ts.getTestcase().forEach(tc -> addTestCase(result, ts, tc)));
                    } else if (ots instanceof Testsuite) {
                        ((Testsuite) ots).getTestcase().forEach(tc -> addTestCase(result, (Testsuite) ots, tc));
                    }
                }
            }
        } catch (Exception e) {
            log.catching(e);
        }
        return result;
    }

    private List<ByteArrayInputStream> extractArtifacts(InputStream inputStream) {
        PathMatcher matcher = FileSystems.getDefault()
                .getPathMatcher("glob:" + applicationSettings.getConfig().getGitlabArtifactPattern());
        try {
            ZipInputStream zis = new ZipInputStream(inputStream);
            List<ByteArrayInputStream> result = new LinkedList<>();
            for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
                System.out.println("entry: " + entry.getName() + ", " + entry.getSize());
                // consume all the data from this entry
                if (matcher.matches(Paths.get(entry.getName()))) {
                    while (zis.available() > 0) {
                        ByteArrayOutputStream entryStream = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = zis.read(buffer)) != -1) {
                            entryStream.write(buffer, 0, length);
                        }
                        result.add(new ByteArrayInputStream(entryStream.toByteArray()));
                    }
                }
            }
            return result;
        } catch (IOException e) {
            log.catching(e);
            return null;
        }
    }

    private void addTestCase(List<TestRun> result, Testsuite ts, Testcase tc) {
        TestRunResult testResultStatus = null;
        if (tc.getSkipped() != null && tc.getSkipped().trim().length() > 0) {
            testResultStatus = TestRunResult.SKIPPED;
        } else if (tc.getFailure().size() > 0) {
            testResultStatus = TestRunResult.FAILED;
        } else {
            testResultStatus = TestRunResult.PASSED;
        }

        TestRun tr = null;
        tr = dtoFactory.newDTO(TestRun.class)
                .setModuleName("")
                .setPackageName(ts.getPackage())
                .setClassName(tc.getClassname())
                .setTestName(tc.getName())
                .setResult(testResultStatus)
                .setDuration(Double.valueOf(tc.getTime()).longValue() * 1000);
        result.add(tr);
    }

    private boolean isProxyNeeded(URL targetHost) {
        boolean result = false;
        ConfigStructure config = applicationSettings.getConfig();
        if (config.getProxyField(targetHost.getProtocol(), "proxyHost") != null) {
            String nonProxyHostsStr = config.getProxyField(targetHost.getProtocol(), "nonProxyHosts");
            if (targetHost != null && !CIPluginSDKUtils.isNonProxyHost(targetHost.getHost(), nonProxyHostsStr)) {
                result = true;
            }
        }
        return result;
    }
}
