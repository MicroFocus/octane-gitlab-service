/*
 *     Copyright 2017 EntIT Software LLC, a Micro Focus company, L.P.
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.microfocus.octane.gitlab.api;

import com.hp.octane.integrations.OctaneSDK;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import com.microfocus.octane.gitlab.helpers.GitLabApiWrapper;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component

@Path("/status") //{server.baseUrl}/status
public class StatusRestResource {

    @Context
    HttpServletRequest request;

    private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static GitLabApi gitLabApi;

    @Autowired
    public void setGitLabApi(GitLabApiWrapper gitLabApiWrapper) {
        gitLabApi = gitLabApiWrapper.getGitLabApi();
    }


    @GET
    @Produces({MediaType.APPLICATION_JSON})
    public Response getStatus() {
        return Response.ok(getStatusResult()).build();
    }


    private Map<String, Object> getStatusResult() {

        Map<String, Object> result = new HashMap<>();

        //prepare server info
        String pluginVersion = ApplicationSettings.getPluginVersion();
        String gitlabVersion;
        try {
            gitlabVersion = gitLabApi.getVersion().getVersion();
        } catch (GitLabApiException e) {
            gitlabVersion = "";
        }
        Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("sdkVersion", OctaneSDK.SDK_VERSION);
        serverInfo.put("pluginVersion", pluginVersion);
        serverInfo.put("gitlabVersion", gitlabVersion);
        serverInfo.put("currentTime", format.format(new Date()));
        result.put("server", serverInfo);


        //prepare metrics
        Map<String, Object> allMetrics = new HashMap<>();
        OctaneSDK.getClients().forEach(

                client -> {
                    Map<String, Object> clientMetrics = new HashMap<>();
                    clientMetrics.put("client", format(client.getMetrics()));
                    clientMetrics.put("taskPollingService", format(client.getBridgeService().getMetrics()));
                    clientMetrics.put("eventsService", format(client.getEventsService().getMetrics()));
                    clientMetrics.put("testsService", format(client.getTestsService().getMetrics()));
                    clientMetrics.put("restClient", format(client.getRestService().obtainOctaneRestClient().getMetrics()));

                    allMetrics.put(client.getConfigurationService().getConfiguration().geLocationForLog(), clientMetrics);
                }
        );

        //fill results
        result.put("server", serverInfo);
        result.put("metrics", allMetrics);

        return result;
    }

    private Map<String, Object> format(Map<String, Object> map) {
        map.keySet().forEach(key -> {
            if (map.get(key) instanceof Date) {
                String value = format.format(map.get(key));
                map.put(key, value);

            }
        });
        return map;
    }

}