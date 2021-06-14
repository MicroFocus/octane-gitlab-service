package com.microfocus.octane.gitlab.helpers;

import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.parameters.CIParameter;
import com.hp.octane.integrations.dto.parameters.CIParameterType;
import com.hp.octane.integrations.dto.parameters.CIParameters;
import com.microfocus.octane.gitlab.app.ApplicationSettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Variable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VariablesHelper {

    private static final Logger log = LogManager.getLogger(VariablesHelper.class);
    private static final String KEY = "key";
    private static final String VALUE = "value";
    private static final String VARS_ON_PROJECT = "project";
    private static final String VARS_ON_GROUPS = "groups";
    private static final String VARS_ON_INSTANCE = "instance";


    private static final DTOFactory dtoFactory = DTOFactory.getInstance();

    public static CIParameter convertVariableToParameter(Object var) {

        String key = (String) ((JSONObject) var).get(KEY);
        String value = (String) ((JSONObject) var).get(VALUE);

        CIParameter param = dtoFactory.newDTO(CIParameter.class);
        param.setType(CIParameterType.STRING);
        param.setName(key);
        param.setValue(value);
        return param;
    }

    public static JSONArray getVariablesListFromPipelineEvent(JSONObject obj) {
        return obj.getJSONObject("object_attributes").getJSONArray("variables");

    }

    public static Map<String, String> convertParametersToVariables(CIParameters ciParameters) {
        Map<String, String> variables = new HashMap<>();

        for(CIParameter parameter : ciParameters.getParameters()){
            variables.put(parameter.getName(),parameter.getValue().toString());
        }
        return variables;
    }

    public static List<Variable> getVariables(ParsedPath project, GitLabApi gitLabApi, ApplicationSettings applicationSettings){
        List<Variable> variables = new ArrayList<>();
        if(project == null || project.getFullPathOfProject() == null){
            return variables;
        }
        try{
            List<String> variablesUsage = applicationSettings.getConfig().getGitlabVariablesPipelineUsage();
            log.info("getting all defined variables on "+variablesUsage.toString());

            if(variablesUsage.isEmpty() || variablesUsage.contains(VARS_ON_PROJECT) ){
                List<Variable> variablesOnProject = gitLabApi.getProjectApi().getVariables(project.getPathWithNameSpace());
                variables.addAll(variablesOnProject);
            }

            if(variablesUsage.contains(VARS_ON_GROUPS)){
                List<String> groupsFullPath = ParsedPath.getGroupFullPathFromProject(project.getPathWithNameSpace());
                for(String group :groupsFullPath){
                    List<Variable> variablesOnGroup = gitLabApi.getGroupApi().getVariables(group);
                    variables.addAll(variablesOnGroup);
                }

            }

//            if(variablesUsage.contains("instance")){
//                List<Variable> variablesOnInstance = gitLabApi.getGroupApi().getVariables(project.getFullPathOfProject());
//                variables.addAll(variablesOnInstance);
//            }

        } catch (GitLabApiException e){
            log.error("can not find any variables for the project:"+project.getDisplayName());
        } finally {
            return variables;
        }
    }
}
