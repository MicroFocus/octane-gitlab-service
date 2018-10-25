package com.microfocus.octane.gitlab.helpers;

import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.model.ConfigStructure;
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

    @Autowired
    public GitLabApiWrapper(ApplicationSettings applicationSettings) {
        this.applicationSettings = applicationSettings;
    }

    @PostConstruct
    public void initGitlabApiWrapper() throws MalformedURLException, GitLabApiException, ConfigurationException {
        ConfigStructure config = applicationSettings.getConfig();
        Map<String, Object> proxyConfig = null;
        String protocol = new URL(config.getGitlabLocation()).getProtocol().toLowerCase();
        String proxyUrl = config.getProxyField(protocol, "proxyUrl");
        if (proxyUrl != null) {
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
            gitLabApi.getProjectApi().getProjects();
        } catch(GitLabApiException e) {
            ConfigurationException w = new ConfigurationException("GitLab API failed to perform basic operations. Please validate GitLab properties - location, personalAccessToken(including token permissions/scopes in GitLab server)");
            throw w;
        }
    }

    public GitLabApi getGitLabApi() {
        return gitLabApi;
    }

}
