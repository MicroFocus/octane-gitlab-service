package com.microfocus.octane.gitlab.helpers;

import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.model.ConfigStructure;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.ProxyClientConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import static com.microfocus.octane.gitlab.helpers.PasswordEncryption.PREFIX;

@Component
@Scope("singleton")
public class GitLabApiWrapper {

    @Autowired
    private ApplicationSettings applicationSettings;
    private GitLabApi gitLabApi;

    @PostConstruct
    public void initGitlabApiWrapper() throws MalformedURLException {
        ConfigStructure config = applicationSettings.getConfig();
        Map<String, Object> proxyConfig = null;
        String protocol = new URL(config.getGitlabLocation()).getProtocol().toLowerCase();
        String proxyUrl = config.getProxyField(protocol, "proxyUrl");
        if (proxyUrl != null) {
            String proxyPassword = config.getProxyField(protocol, "proxyPassword");
            if (proxyPassword.startsWith(PREFIX)) {
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
        String gitlabPrivateToken = config.getGitlabPrivateToken();
        if (gitlabPrivateToken.startsWith(PREFIX)) {
            try {
                gitlabPrivateToken = PasswordEncryption.decrypt(gitlabPrivateToken.substring(PREFIX.length()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        gitLabApi = new GitLabApi(config.getGitlabLocation(), gitlabPrivateToken, null, proxyConfig);
    }

    public GitLabApi getGitLabApi() {
        return gitLabApi;
    }

}
