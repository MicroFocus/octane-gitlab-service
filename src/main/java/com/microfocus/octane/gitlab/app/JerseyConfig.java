package com.microfocus.octane.gitlab.app;

import com.microfocus.octane.gitlab.api.ConfigurationResource;
import com.microfocus.octane.gitlab.api.EventListener;
import com.microfocus.octane.gitlab.api.StatusRestResource;
import com.microfocus.octane.gitlab.ui.HomeController;
import org.glassfish.jersey.server.ResourceConfig;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

@Configuration
public class JerseyConfig extends ResourceConfig {
    public JerseyConfig() {
    }

    @PostConstruct
    public void setUp() {
        register(ConfigurationResource.class);
        register(EventListener.class);
        register(StatusRestResource.class);
        register(HomeController.class);
    }


}