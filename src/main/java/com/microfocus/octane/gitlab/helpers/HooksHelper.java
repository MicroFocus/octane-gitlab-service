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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.ProjectHook;
import org.gitlab4j.api.models.User;

import java.net.URL;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;


public final class HooksHelper {

    static final Logger log = LogManager.getLogger(HooksHelper.class);

    public static Boolean addWebHookToProject(GitLabApi gitLabApi,URL webhookURL, Object projectId, boolean deleteOldWebHook) throws GitLabApiException {

        try {
            if(deleteOldWebHook){
                deleteWebHooks(gitLabApi,webhookURL,projectId);
            }
            ProjectHook hook = new ProjectHook();
            hook.setJobEvents(true);
            hook.setPipelineEvents(true);
            hook.setMergeRequestsEvents(true);

            gitLabApi.getProjectApi().addHook(projectId, webhookURL.toString(), hook, false, generateNewToken());
        } catch (GitLabApiException e){
            log.warn("Failed to add web hooks to project: "+projectId, e);
            throw e;
        }

            return true;
    }


    public static void deleteWebHooks(List<Project> projects,URL webhookURL,GitLabApiWrapper gitLabApiWrapper,GitLabApi gitLabApi, User currentUser) throws GitLabApiException {
        for (Project project : projects) {
            if (gitLabApiWrapper.isUserHasPermissionForProject(project, currentUser)) {
                HooksHelper.deleteWebHooks(gitLabApi,webhookURL,project.getId());
            }
        }
    }
    public static void deleteWebHooks(GitLabApi gitLabApi,URL webhookURL, Object projectIdOrPath) throws GitLabApiException {
        for (ProjectHook hook : gitLabApi.getProjectApi().getHooks(projectIdOrPath)) {
            if (hook.getUrl().equals(webhookURL.toString())) {
                try {
                    gitLabApi.getProjectApi().deleteHook(projectIdOrPath, hook.getId());
                } catch (GitLabApiException e) {
                    log.warn("Failed to delete a GitLab web hook", e);
                }
            }
        }
    }

    private static String generateNewToken() {
        final SecureRandom secureRandom = new SecureRandom();
        final Base64.Encoder base64Encoder = Base64.getUrlEncoder();
        byte[] randomBytes = new byte[24];
        secureRandom.nextBytes(randomBytes);
        return base64Encoder.encodeToString(randomBytes);
    }
}
