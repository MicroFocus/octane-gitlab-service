package com.microfocus.octane.gitlab.ui;

import com.hp.octane.integrations.OctaneSDK;
import org.springframework.stereotype.Component;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Component
@Path("/")
public class HomeController {
    @GET
    @Produces(MediaType.TEXT_HTML)
    public String index() {
        return "Spring MVC: Up and Running!";
    }
}
