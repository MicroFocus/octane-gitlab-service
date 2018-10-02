package com.microfocus.octane.gitlab.api;

import org.springframework.stereotype.Component;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

@Component
@Path("/api")
public class ConfigurationResource {

    @GET
    @Produces("application/json")
    public String index() {
        return "Greetings from Spring Boot!";
    }

}