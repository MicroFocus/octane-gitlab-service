package com.microfocus.octane.gitlab.model;

import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Optional;

@Component
public class ConfigStructure {

    @Value("${ciserver.identity:#{null}}")
    private String ciServerIdentity;

    @Value("${octane.location:#{null}}")
    private String octaneLocation;

    @Value("${octane.sharedspace:#{null}}")
    private String octaneSharedspace;

    @Value("${octane.username:#{null}}")
    private String octaneUsername;

    @Value("${octane.password:#{null}}")
    private String octanePassword;

    @Value("${gitlab.location:#{null}}")
    private String gitlabLocation;

    @Value("${gitlab.privateToken:#{null}}")
    private String gitlabPrivateToken;

    @Value("${server.baseUrl:#{null}}")
    private String serverBaseUrl;

    @Value("${http.proxyHost:#{null}}")
    private String httpProxyHost;

    @Value("${http.proxyPort:#{null}}")
    private String httpProxyPort;

    @Value("${http.proxyUser:#{null}}")
    private String httpProxyUser;

    @Value("${http.proxyPassword:#{null}}")
    private String httpProxyPassword;

    @Value("${http.nonProxyHosts:#{null}}")
    private String httpNonProxyHosts;

    @Value("${https.proxyHost:#{null}}")
    private String httpsProxyHost;

    @Value("${https.proxyPort:#{null}}")
    private String httpsProxyPort;

    @Value("${https.proxyUser:#{null}}")
    private String httpsProxyUser;

    @Value("${https.proxyPassword:#{null}}")
    private String httpsProxyPassword;

    @Value("${https.nonProxyHosts:#{null}}")
    private String httpsNonProxyHosts;

    public String getServerBaseUrl() {
        if (serverBaseUrl == null) {
            throw new IllegalArgumentException("serverBaseUrl property must not be null");
        }
        return serverBaseUrl;
    }

    public String getCiServerIdentity() {
        String val = ciServerIdentity != null ? ciServerIdentity : Hex.encodeHexString(DigestUtils.md5Digest(serverBaseUrl.getBytes()));
        return val.substring(0, Math.min(255, val.length()));
    }

    public String getOctaneLocation() {
        return octaneLocation;
    }

    public String getOctaneSharedspace() {
        return octaneSharedspace;
    }

    public String getOctaneUsername() {
        return octaneUsername;
    }

    public String getOctanePassword() {
        return octanePassword;
    }

    public String getGitlabLocation() {
        return gitlabLocation;
    }

    public String getGitlabPrivateToken() {
        return gitlabPrivateToken;
    }

    public String getProxyField(String protocol, String fieldName) {
        Optional<Field> field = Arrays.stream(this.getClass().getDeclaredFields()).filter(f -> f.getName().toLowerCase().equals(protocol.concat(fieldName).toLowerCase())).findFirst();
        if (!field.isPresent()) {
            throw new IllegalArgumentException(protocol + '.' + fieldName);
        }
        try {
            Object value = field.get().get(this);
            return value != null ? value.toString() : null;
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(String.format("$s.$s field in not accessible", protocol, fieldName));
        }
    }
}
