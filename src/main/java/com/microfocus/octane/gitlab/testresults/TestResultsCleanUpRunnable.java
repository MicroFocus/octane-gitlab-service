package com.microfocus.octane.gitlab.testresults;

import com.microfocus.octane.gitlab.helpers.TestResultsHelper;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;


public class TestResultsCleanUpRunnable implements Runnable {

    //each 5 minutes delete all old files.
    static public final int INTERVAL = 60;
    static final long TIME = TimeUnit.MINUTES.toMillis(INTERVAL);
    private String testResultsFolderPath="";
    static final Logger log = LogManager.getLogger(TestResultsCleanUpRunnable.class);

    public TestResultsCleanUpRunnable(String folderPath){
        if(folderPath!=null && !folderPath.isEmpty()){
            testResultsFolderPath = folderPath;
        }
    }

    public void deleteFiles() {
        File folder = TestResultsHelper.getTestResultFolderFullPath(testResultsFolderPath);
        for (final File fileEntry : folder.listFiles()) {
            if (System.currentTimeMillis() - fileEntry.lastModified() > TIME) {
                try {
                    FileUtils.deleteDirectory(fileEntry);
                    log.info("delete test results old file:"+ fileEntry.getName());
                } catch (IOException e) {
                   log.warn("enable to delete tests results directory:"+fileEntry.getName()+"," +e.getMessage());
                }

            } else {
                System.out.println(fileEntry.getName());
            }
        }
    }

    @Override
    public void run() {
        deleteFiles();
    }
}
