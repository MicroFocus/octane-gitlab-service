package com.microfocus.octane.gitlab.helpers;

import com.hp.octane.integrations.utils.CIPluginSDKUtils;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.model.ConfigStructure;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.ProxyClientConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.naming.ConfigurationException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

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
            log.error("GitLab API failed to perform basic operations. Please validate GitLab properties - location, personalAccessToken(including token permissions/scopes in GitLab server)" +
                    " if one of the end points doesnt required proxy, please put it on the non proxy hosts.");
            throw e;
        }
    }

    public GitLabApi getGitLabApi() {
        return gitLabApi;
    }

}
