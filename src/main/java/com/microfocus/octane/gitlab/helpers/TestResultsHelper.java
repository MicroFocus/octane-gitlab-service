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

package com.microfocus.octane.gitlab.helpers;

import com.hp.octane.integrations.OctaneSDK;
import com.hp.octane.integrations.utils.SdkConstants;
import com.hp.octane.integrations.utils.SdkStringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.util.IOUtils;
import org.gitlab4j.api.models.Job;
import org.gitlab4j.api.models.Project;

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class TestResultsHelper {

    static final Logger log = LogManager.getLogger(TestResultsHelper.class);
    static final String testResultFolder = "ALM_Octane_Test_Results";

    public static File getTestResultFolderFullPath(String rootFolder){
        return Paths.get(rootFolder, testResultFolder).toFile();
    }

    public static File getMQMTestResultsFilePath(long projectId, long jobId,String rootFolderPath) {

        if(rootFolderPath == null ){
            rootFolderPath ="";
        }
        File targetDirectory = Paths.get(rootFolderPath, testResultFolder,
                "Project_"+ projectId + "Build_"+ jobId).toFile();
        targetDirectory.mkdirs();

        File mqmTestResultsFile = new File(targetDirectory, SdkConstants.General.MQM_TESTS_FILE_NAME);

        return mqmTestResultsFile;
    }

    public static boolean isFilePatternExist(String filePattern){
        return SdkStringUtils.isNotEmpty(filePattern);
    }

    public static void pushTestResultsKey(Project project,Job job) {

        OctaneSDK.getClients().forEach(client ->
                client.getTestsService().enqueuePushTestsResult(project.getPathWithNamespace().toLowerCase() + "/" + job.getName(), job.getId().toString(), null));

    }

    public static List<File> extractArtifactsToFiles(InputStream inputStream, String testResultsFilePattern) {
        PathMatcher matcher = FileSystems.getDefault()
                .getPathMatcher(testResultsFilePattern); //applicationSettings.getConfig().getGitlabTestResultsFilePattern());
        File tempFile = null;
        try {
            tempFile = File.createTempFile("gitlab-artifact", ".zip");

            try (OutputStream os = new FileOutputStream(tempFile)) {
                StreamHelper.copyStream(inputStream, os);
            }

            inputStream.close();

            ZipFile zipFile = new ZipFile(tempFile);

            List<File> result = new LinkedList<>();
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();

                if (matcher.matches(Paths.get(entry.getName()))) {
                    ByteArrayOutputStream entryStream = new ByteArrayOutputStream();

                    try (InputStream zipEntryStream = zipFile.getInputStream(entry)) {
                        StreamHelper.copyStream(zipEntryStream, entryStream);
                    }

                    File tempResultFile = File.createTempFile(entry.getName(),".xml");
                    FileOutputStream f = null;//new FileOutputStream(entry.getName());
                    IOUtils.copy(new ByteArrayInputStream(entryStream.toByteArray()), tempResultFile);

                    result.add(tempResultFile);
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

    public static List<Map.Entry<String, ByteArrayInputStream>> extractArtifacts(InputStream inputStream, String testResultsFilePattern) {
        PathMatcher matcher = FileSystems.getDefault()
                .getPathMatcher(testResultsFilePattern);
        File tempFile = null;
        try {
            tempFile = File.createTempFile("gitlab-artifact-" + UUID.randomUUID(), ".zip");

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

}
