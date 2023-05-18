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
