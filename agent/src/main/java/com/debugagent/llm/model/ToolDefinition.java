package com.debugagent.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A tool/function definition exposed to the LLM.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolDefinition {

    private String type = "function";
    private FunctionDef function;

    public ToolDefinition() {}

    public ToolDefinition(String name, String description, Object parameters) {
        this.function = new FunctionDef(name, description, parameters);
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public FunctionDef getFunction() { return function; }
    public void setFunction(FunctionDef function) { this.function = function; }

    public static class FunctionDef {
        private String name;
        private String description;
        private Object parameters;

        public FunctionDef() {}

        public FunctionDef(String name, String description, Object parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public Object getParameters() { return parameters; }
        public void setParameters(Object parameters) { this.parameters = parameters; }
    }
}
