package com.microfocus.octane.gitlab.app;

import com.hp.octane.integrations.OctaneSDK;
import com.microfocus.octane.gitlab.helpers.PasswordEncryption;
import com.microfocus.octane.gitlab.services.OctaneServices;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;

import java.io.IOException;
import java.security.GeneralSecurityException;

import static com.microfocus.octane.gitlab.helpers.PasswordEncryption.encrypt;

@SpringBootApplication
@ComponentScan("com.microfocus.octane.gitlab")
public class Application {

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
            OctaneSDK.addClient(octaneServices.getOctaneConfiguration(), OctaneServices.class);
        } catch (Throwable r) {
            int t = 5;
        }
    }
}