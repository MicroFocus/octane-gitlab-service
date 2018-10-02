package com.microfocus.octane.gitlab.app;

import com.hp.octane.integrations.OctaneSDK;
import com.hp.octane.integrations.spi.CIPluginServices;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan("com.microfocus.octane.gitlab")
public class Application {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(Application.class, args);
        CIPluginServices pluginServices = context.getBean("octaneServices", CIPluginServices.class);
        OctaneSDK.init(pluginServices);
    }

}