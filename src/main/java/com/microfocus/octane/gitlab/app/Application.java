package com.microfocus.octane.gitlab.app;

import com.hp.octane.integrations.OctaneConfiguration;
import com.hp.octane.integrations.OctaneSDK;
import com.hp.octane.integrations.exceptions.OctaneConnectivityException;
import com.microfocus.octane.gitlab.helpers.PasswordEncryption;
import com.microfocus.octane.gitlab.services.OctaneServices;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.security.GeneralSecurityException;

import static com.microfocus.octane.gitlab.helpers.PasswordEncryption.encrypt;

@SpringBootApplication
@ComponentScan("com.microfocus.octane.gitlab")
public class Application {
    private static final Logger log = LogManager.getLogger(Application.class);

    @Bean
    public TaskExecutor taskExecutor() {
        return new SimpleAsyncTaskExecutor();
    }

    public static void main(String[] args) throws GeneralSecurityException, IOException {
        if (args.length > 0 && args[0].equals("encrypt")) {
            if (args.length == 1) {
                System.out.println("Usage: java -jar octane-gitlab-service-<version>.jar encrypt <password>");
                return;
            } else {
                String tokenToEncrypt = args[1];
                String encryptedToken = encrypt(tokenToEncrypt);
                System.out.println("Encrypted token: " + PasswordEncryption.PREFIX + encryptedToken);
                return;
            }
        }
        ConfigurableApplicationContext context = SpringApplication.run(Application.class, args);
        OctaneServices octaneServices = context.getBean("octaneServices", OctaneServices.class);
        try {
            if(octaneServices.getGitLabService().isCleanUpOnly()){
                System.out.println("clean-up webhooks from Gitlab server process is finished. Stopping the service");
                context.close();
                return;
            }

            tryToConnectToOctane(octaneServices);
            OctaneSDK.addClient(octaneServices.getOctaneConfiguration(), OctaneServices.class);
            System.out.println("Connection to Octane was successful. gitlab application is ready...");


        } catch (IllegalArgumentException | OctaneConnectivityException r) {
            log.warn("Connection to Octane failed: " + r.getMessage());
            System.out.println("Connection to Octane failed: " + r.getMessage());
        }
    }

    private static void tryToConnectToOctane(OctaneServices octaneServices) throws OctaneConnectivityException {
        OctaneConfiguration octaneConfiguration = octaneServices.getOctaneConfiguration();
        if (StringUtils.isEmpty(octaneConfiguration.getUrl())) {
            throw new IllegalArgumentException("Location URL is missing");
        }
        if (StringUtils.isEmpty(octaneConfiguration.getClient())) {
            throw new IllegalArgumentException("Client ID is missing");
        }
        if (StringUtils.isEmpty(octaneConfiguration.getSecret())) {
            throw new IllegalArgumentException("Client Secret is missing");
        }
        octaneServices.getGitLabApiWrapper().getGitLabApi().getSecretToken();
        try {
            OctaneSDK.testOctaneConfigurationAndFetchAvailableWorkspaces(octaneConfiguration.getUrl(),
                    octaneConfiguration.getSharedSpace(),
                    octaneConfiguration.getClient(),
                    octaneConfiguration.getSecret(),
                    OctaneServices.class);
        } catch (OctaneConnectivityException e) {
            throw new IllegalArgumentException(e.getErrorMessageVal());
        } catch (Exception e) {
            throw new IllegalArgumentException("Unexpected exception :" + e.getMessage());
        }
    }
}