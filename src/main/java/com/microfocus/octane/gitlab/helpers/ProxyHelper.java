package com.microfocus.octane.gitlab.helpers;

import com.hp.octane.integrations.utils.CIPluginSDKUtils;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.model.ConfigStructure;

import java.net.URL;

public class ProxyHelper {

    public static boolean isProxyNeeded(ApplicationSettings applicationSettings, URL targetHost) {
        if (targetHost == null) return false;
        boolean result = false;
        ConfigStructure config = applicationSettings.getConfig();
        if (config.getProxyField(targetHost.getProtocol(), "proxyUrl") != null) {
            String nonProxyHostsStr = config.getProxyField(targetHost.getProtocol(), "nonProxyHosts");
            if (!CIPluginSDKUtils.isNonProxyHost(targetHost.getHost(), nonProxyHostsStr)) {
                result = true;
            }
        }
        return result;
    }

}
