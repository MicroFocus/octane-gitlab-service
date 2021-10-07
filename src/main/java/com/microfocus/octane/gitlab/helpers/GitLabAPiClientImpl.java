package com.microfocus.octane.gitlab.helpers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApiClient;
import org.gitlab4j.api.models.Variable;
import org.json.JSONArray;

import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public final class GitLabAPiClientImpl extends GitLabApiClient {

    private static final String INSTANCE_VARIABLES_PATH = "admin/ci/variables";
    private static final Logger log = LogManager.getLogger(GitLabAPiClientImpl.class);

    public GitLabAPiClientImpl(String hostUrl, String gitlabPersonalAccessToken) {
        super(hostUrl, gitlabPersonalAccessToken);
    }

    public List<Variable> getInstanceVariables() {

        List<Variable> variableList = new ArrayList<>();
        try {
            URL url = this.getApiUrl(INSTANCE_VARIABLES_PATH);
            Response r =this.get(null,url);
            if(r.getStatus() == Response.Status.OK.getStatusCode()) {
                return VariablesHelper.convertJSONArrayToVariables(new JSONArray(r.readEntity(String.class)));
            } else{
                log.info("no variables are found, status code is"+ r.getStatus());
            }

        } catch (IOException e) {
            log.error("getting error when trying to get instance variables", e);
        }

        return variableList;

    }

}
