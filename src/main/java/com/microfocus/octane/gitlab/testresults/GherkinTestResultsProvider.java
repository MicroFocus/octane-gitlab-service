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

import com.hp.octane.integrations.testresults.GherkinUtils;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.helpers.TestResultsHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.models.Job;
import org.gitlab4j.api.models.Project;

import java.io.File;
import java.io.InputStream;
import java.util.List;

public class GherkinTestResultsProvider {


    static final Logger log = LogManager.getLogger(GherkinTestResultsProvider.class);

    private String testResultsRootFolder ="";
    private String testResultsFilePattern = null;
    private static GherkinTestResultsProvider gherkinTestResultsProviderInstance = null;

    // Static method
    // Static method to create instance of Singleton class
    public static GherkinTestResultsProvider getInstance(ApplicationSettings applicationSettings)
    {
        if (gherkinTestResultsProviderInstance == null)
            gherkinTestResultsProviderInstance = new GherkinTestResultsProvider(applicationSettings);

        return gherkinTestResultsProviderInstance;
    }

    private GherkinTestResultsProvider(ApplicationSettings applicationSettings){
        String rootFolderPath = applicationSettings.getConfig().getTestResultsOutputFolderPath();
        if(rootFolderPath != null && !rootFolderPath.isEmpty()){
            testResultsRootFolder  = rootFolderPath;
        }
        if(TestResultsHelper.isFilePatternExist(applicationSettings.getConfig().getGitlabGherkinTestResultsFilePattern())){
            testResultsFilePattern = applicationSettings.getConfig().getGitlabGherkinTestResultsFilePattern();
        }
    }

    protected String getTestResultRootFolder(){
        return testResultsRootFolder;
    }

    public boolean createTestList(Project project, Job job,InputStream artifactFiles){

        if(TestResultsHelper.isFilePatternExist(testResultsFilePattern)){

            List<File> artifacts = TestResultsHelper.extractArtifactsToFiles(artifactFiles,testResultsFilePattern);
            File mqmTestResultsFile = TestResultsHelper.getMQMTestResultsFilePath(project.getId(),job.getId(),getTestResultRootFolder());

            try {
                GherkinUtils.aggregateGherkinFilesToMqmResultFile(artifacts, mqmTestResultsFile, job.getName(), Long.toString(job.getId()),null);
                TestResultsHelper.pushTestResultsKey(project,job);
                log.info("Gherkin test results for: [project"+ project.getName()+", id:"+project.getId()+",job:"+ job.getName() + "] were saved to file successfully ");

                return true;
            }catch (Exception e) {
                String msg = "unable to create Gherkin results"+project.getName()+"_"+job.getName()+":"+e.getMessage();
                if(log.isDebugEnabled()){
                    log.debug(msg,e.getStackTrace());
                }else {
                    log.warn(msg);
                }

            }
        }

        return false;
    }

}
