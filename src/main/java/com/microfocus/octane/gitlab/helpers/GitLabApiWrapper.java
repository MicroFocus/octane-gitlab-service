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

import com.hp.octane.integrations.utils.CIPluginSDKUtils;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.model.ConfigStructure;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.ProxyClientConfig;
import org.gitlab4j.api.models.AccessLevel;
import org.gitlab4j.api.models.Member;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.naming.ConfigurationException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Optional;

import static com.microfocus.octane.gitlab.helpers.PasswordEncryption.PREFIX;

@Component
@Scope("singleton")
public class GitLabApiWrapper {

    private final ApplicationSettings applicationSettings;
    private GitLabApi gitLabApi;
    private static final Logger log = LogManager.getLogger(GitLabApiWrapper.class);

    @Autowired
    public GitLabApiWrapper(ApplicationSettings applicationSettings) {
        this.applicationSettings = applicationSettings;
    }

    @PostConstruct
    public void initGitlabApiWrapper() throws MalformedURLException, GitLabApiException, ConfigurationException {
        ConfigStructure config = applicationSettings.getConfig();
        Map<String, Object> proxyConfig = null;
        URL targetUrl = CIPluginSDKUtils.parseURL(config.getGitlabLocation());

        if (ProxyHelper.isProxyNeeded(applicationSettings, targetUrl)) {
            String protocol = targetUrl.getProtocol().toLowerCase();
            String proxyUrl = config.getProxyField(protocol, "proxyUrl");
            String proxyPassword = config.getProxyField(protocol, "proxyPassword");
            if (proxyPassword != null && proxyPassword.startsWith(PREFIX)) {
                try {
                    proxyPassword = PasswordEncryption.decrypt(proxyPassword.substring(PREFIX.length()));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            proxyConfig = ProxyClientConfig.createProxyClientConfig(
                    proxyUrl,
                    config.getProxyField(protocol, "proxyUser"),
                    proxyPassword);
        }
        String gitlabPersonalAccessToken = config.getGitlabPersonalAccessToken();
        if (gitlabPersonalAccessToken != null && gitlabPersonalAccessToken.startsWith(PREFIX)) {
            try {
                gitlabPersonalAccessToken = PasswordEncryption.decrypt(gitlabPersonalAccessToken.substring(PREFIX.length()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        gitLabApi = new GitLabApi(config.getGitlabLocation(), gitlabPersonalAccessToken, null, proxyConfig);
        try {
            gitLabApi.getProjectApi().getOwnedProjects();
        } catch (GitLabApiException e) {
            String message = "GitLab API failed to perform basic operations. Please validate GitLab properties - location, personalAccessToken(including token permissions/scopes in GitLab server)" +
                    " if one of the end points doesnt required proxy, please put it on the non proxy hosts.";
            log.error(message);
            System.out.println(message);
            throw e;
        }
    }

    public GitLabApi getGitLabApi() {
        return gitLabApi;
    }

    public boolean isUserHasPermissionForProject(Project project,User currentUser) {
        try {
            Optional<Member> currentMember = gitLabApi.getProjectApi().getAllMembers(project.getId())
                    .stream().filter(member -> member.getId().equals(currentUser.getId())).findFirst();

            if (currentMember.isPresent() && currentMember.get().getAccessLevel().value >= AccessLevel.MAINTAINER.value) {
                return true;
            } else {
                log.info(currentUser.getName() + " doesnt have enough permission for project " + project.getPath());
                return false;
            }
        } catch (GitLabApiException e) {
            log.error("failed to get user permissions" + e.getMessage());
        }
        return false;
    }

}
