package com.microfocus.octane.gitlab.app;

import com.microfocus.octane.gitlab.model.ConfigStructure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope("singleton")
public class ApplicationSettings {

    private final ConfigStructure configStructure;

    @Autowired
    public ApplicationSettings(ConfigStructure configStructure) {
        this.configStructure = configStructure;
    }

    public static String getCIServerType() {
        return "gitlab";
    }

    public static String getPluginVersion() {
        String version = Application.class.getPackage().getImplementationVersion();
        return version != null ? version : "0.0.0.0";
    }

    public ConfigStructure getConfig() {
        return configStructure;
    }
}
