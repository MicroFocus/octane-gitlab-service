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

package com.microfocus.octane.gitlab.testresults;

import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.tests.TestRun;
import com.hp.octane.integrations.dto.tests.TestRunError;
import com.hp.octane.integrations.dto.tests.TestRunResult;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.helpers.TestResultsHelper;
import com.microfocus.octane.gitlab.model.junit5.Testcase;
import com.microfocus.octane.gitlab.model.junit5.Testsuite;
import com.microfocus.octane.gitlab.model.junit5.Testsuites;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.models.Job;
import org.gitlab4j.api.models.Project;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static hudson.plugins.nunit.NUnitReportTransformer.NUNIT_TO_JUNIT_XSLFILE_STR;

public class JunitTestResultsProvider {

    static final Logger log = LogManager.getLogger(JunitTestResultsProvider.class);
    private final Transformer nunitTransformer;
    private final DTOFactory dtoFactory = DTOFactory.getInstance();
    private String testResultsRootFolder ="";
    private static JunitTestResultsProvider junitTestResultsProviderInstance;
    String testResultsFilePattern =null;

    public static JunitTestResultsProvider getInstance(ApplicationSettings applicationSettings) throws TransformerConfigurationException {
        if (junitTestResultsProviderInstance == null)
            junitTestResultsProviderInstance = new JunitTestResultsProvider(applicationSettings);

        return junitTestResultsProviderInstance;
    }


    private JunitTestResultsProvider(ApplicationSettings applicationSettings) throws TransformerConfigurationException {
        String rootFolderPath = applicationSettings.getConfig().getTestResultsOutputFolderPath();
        if(rootFolderPath != null && !rootFolderPath.isEmpty()){
            testResultsRootFolder  = rootFolderPath;
        }

        if(TestResultsHelper.isFilePatternExist(applicationSettings.getConfig().getGitlabTestResultsFilePattern())){
            testResultsFilePattern = applicationSettings.getConfig().getGitlabTestResultsFilePattern();
        }
        nunitTransformer = TransformerFactory.newInstance().newTransformer(new StreamSource(this.getClass().getClassLoader().getResourceAsStream("hudson/plugins/nunit/" + NUNIT_TO_JUNIT_XSLFILE_STR)));

    }

    public  List<TestRun> createAndGetTestList(InputStream artifactFiles){
        List<TestRun> result = new ArrayList<>();
        if(TestResultsHelper.isFilePatternExist(testResultsFilePattern)){

            try {
                List<Map.Entry<String, ByteArrayInputStream>> artifacts = TestResultsHelper.extractArtifacts(artifactFiles,testResultsFilePattern);
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
                        log.warn("Failed to create a test result list based on the job artifact: " + artifact.getKey(), e);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to create a test list based on the job artifacts", e);
            }
        }
        return result;
    }

    public boolean createTestList(Project project, Job job, InputStream artifactFiles){

        if(TestResultsHelper.isFilePatternExist(testResultsFilePattern)){

            try {
                List<Map.Entry<String, ByteArrayInputStream>> artifacts = TestResultsHelper.extractArtifacts(artifactFiles,testResultsFilePattern);
                if(artifacts!=null && !artifacts.isEmpty()){
                    TestResultsHelper.pushTestResultsKey(project,job);
                    return true;
                }

            } catch (Exception e) {
                log.error("Failed to create a test list based on the job artifacts", e);
            }
        }

        return false;
    }

    private void unmarshallAndAddToResults(List<TestRun> result, JAXBContext jaxbContext, ByteArrayInputStream artifact) throws JAXBException {
        Object ots = jaxbContext.createUnmarshaller().unmarshal(artifact);
        if (ots instanceof Testsuites) {
            ((Testsuites) ots).getTestsuite().forEach(ts -> ts.getTestcase().forEach(tc -> addTestCase(result, ts, tc)));
        } else if (ots instanceof Testsuite) {
            ((Testsuite) ots).getTestcase().forEach(tc -> addTestCase(result, (Testsuite) ots, tc));
        }
    }

    private void addTestCase(List<TestRun> result, Testsuite ts, Testcase tc) {
        TestRunResult testResultStatus;
        if (tc.getSkipped() != null && tc.getSkipped().trim().length() > 0) {
            testResultStatus = TestRunResult.SKIPPED;
        } else if (tc.getFailure().size() > 0 || tc.getError().size() > 0) {
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

}
