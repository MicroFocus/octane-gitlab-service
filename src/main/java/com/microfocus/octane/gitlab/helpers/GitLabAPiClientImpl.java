/*******************************************************************************
 * Copyright 2017-2023 Open Text.
 *
 * The only warranties for products and services of Open Text and
 * its affiliates and licensors (“Open Text”) are as may be set forth
 * in the express warranty statements accompanying such products and services.
 * Nothing herein should be construed as constituting an additional warranty.
 * Open Text shall not be liable for technical or editorial errors or
 * omissions contained herein. The information contained herein is subject
 * to change without notice.
 *
 * Except as specifically indicated otherwise, this document contains
 * confidential information and a valid license is required for possession,
 * use or copying. If this work is provided to the U.S. Government,
 * consistent with FAR 12.211 and 12.212, Commercial Computer Software,
 * Computer Software Documentation, and Technical Data for Commercial Items are
 * licensed to the U.S. Government under vendor's standard commercial license.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *   http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

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
