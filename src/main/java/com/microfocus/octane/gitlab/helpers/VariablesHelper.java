package com.microfocus.octane.gitlab.helpers;

import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.parameters.CIParameter;
import com.hp.octane.integrations.dto.parameters.CIParameterType;
import com.hp.octane.integrations.dto.parameters.CIParameters;
import com.microfocus.octane.gitlab.model.ConfigStructure;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.Variable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public static List<Variable> getVariables(ParsedPath project, GitLabApi gitLabApi, ConfigStructure appConfig){
        List<Variable> variables = new ArrayList<>();
        if(project == null || project.getFullPathOfProject() == null){
            return variables;
        }
        try{
            List<String> variablesUsage = appConfig.getGitlabVariablesPipelineUsage();
            log.info("getting all defined variables from levels: "+variablesUsage.toString() + ", on project: "+ project.getPathWithNameSpace()) ;

            if(variablesUsage.isEmpty() || variablesUsage.contains(VARS_ON_PROJECT) ){
                List<Variable> variablesOnProject = gitLabApi.getProjectApi().getVariables(project.getPathWithNameSpace());
                variables.addAll(variablesOnProject);
            }

            if(variablesUsage.contains(VARS_ON_GROUPS)){
                List<String> groupsFullPath = ParsedPath.getGroupFullPathFromProject(project.getPathWithNameSpace());
                for(String group :groupsFullPath){
                    try {
                        List<Variable> variablesOnGroup = gitLabApi.getGroupApi().getVariables(group);
                        variables.addAll(variablesOnGroup);
                    }catch (GitLabApiException e){
                        if("root".equalsIgnoreCase(group)){
                            log.warn("can not find variables for the group:" + group);
                        }else {
                            log.error("can not find variables for the group:" + group);
                        }
                    }
                }

            }

            if(variablesUsage.contains(VARS_ON_INSTANCE)){//supported only from gitlab 13
                GitLabAPiClientImpl apiClient = new GitLabAPiClientImpl(appConfig.getGitlabLocation(), appConfig.getGitlabPersonalAccessToken());

                List<Variable> variablesOnInstance =  apiClient.getInstanceVariables();
                variables.addAll(variablesOnInstance);
            }

        } catch (GitLabApiException e){
            log.error("can not find variables for the project:"+project.getDisplayName());
        } finally {
            return variables;
        }
    }

    public static List<Variable> convertJSONArrayToVariables(JSONArray jsonVariablesList) {
        List<Variable> variableList = new ArrayList<>();
        jsonVariablesList.forEach(variable -> {
            Variable var = new Variable();
            var.setKey(((JSONObject) variable).getString("key"));
            var.setValue(((JSONObject) variable).getString("value"));
            //     var.setProtected(((JSONObject) variable).getString("protected"));
            variableList.add(var);
        });
        return variableList;
    }

    public static Optional<Variable> getProjectVariable(GitLabApi gitLabApi, int projectId, String variableName) {
        Variable variable = null;
        try {
            variable = gitLabApi.getProjectApi().getVariable(projectId, variableName);
        } catch (GitLabApiException apiException) {
            log.warn("Variable " + variableName + " could not be obtained for project with id " + projectId + ". " +
                    apiException.getMessage());
        }

        return Optional.ofNullable(variable);
    }

    public static Map<String, String> getProjectGroupVariables(GitLabApi gitLabApi, Project project) {
        Map<String, String> variablesKeyValuePairs = new HashMap<>();

        List<String> groupsFullPath = ParsedPath.getGroupFullPathFromProject(project.getPathWithNamespace());
        groupsFullPath.forEach(group -> {
            try {
                List<Variable> variablesOnGroup = gitLabApi.getGroupApi().getVariables(group);
                variablesOnGroup.forEach(variable -> variablesKeyValuePairs.put(variable.getKey(), variable.getValue()));
            } catch (GitLabApiException e) {
                log.error("can not find variables for the group:" + group);
            }
        });

        return variablesKeyValuePairs;
    }
}
