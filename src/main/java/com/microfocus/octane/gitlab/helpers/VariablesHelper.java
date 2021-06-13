package com.microfocus.octane.gitlab.helpers;

import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.parameters.CIParameter;
import com.hp.octane.integrations.dto.parameters.CIParameterType;
import com.hp.octane.integrations.dto.parameters.CIParameters;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class VariablesHelper {
    private static final String KEY = "key";
    private static final String VALUE = "value";
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
}
