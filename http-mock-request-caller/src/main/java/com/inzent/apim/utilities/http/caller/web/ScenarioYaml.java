package com.inzent.apim.utilities.http.caller.web;

import com.fasterxml.jackson.databind.*;
import com.inzent.apim.utilities.http.caller.scenario.Scenario;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import java.io.StringReader;
import java.util.*;

/** Portable configuration only; parsing never constructs arbitrary Java objects. */
final class ScenarioYaml {
    static final int MAX_LENGTH=1048576;
    private ScenarioYaml(){}
    static String export(Scenario scenario,ObjectMapper json){
        Map<String,Object> values=json.convertValue(scenario,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        values.remove("id");values.remove("version");
        DumperOptions options=new DumperOptions();options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);options.setPrettyFlow(true);options.setSplitLines(false);
        String text=new Yaml(options).dump(values);
        if(text.length()>MAX_LENGTH)throw new IllegalArgumentException("YAML exceeds 1 MiB text limit");
        return text;
    }
    static Scenario parse(String text,ObjectMapper json){
        if(text==null||text.isBlank()||text.length()>MAX_LENGTH)throw new IllegalArgumentException("YAML must contain 1..1048576 characters");
        try{
            LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setNestingDepthLimit(50);options.setCodePointLimit(MAX_LENGTH);
            Yaml yaml=new Yaml(new SafeConstructor(options));
            for(var event:yaml.parse(new StringReader(text)))if(event instanceof AliasEvent)throw new IllegalArgumentException("YAML aliases are not supported");
            Object value=yaml.load(text);if(!(value instanceof Map<?,?>))throw new IllegalArgumentException("YAML scenario mapping required");
            Scenario scenario=json.readerFor(Scenario.class).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(json.writeValueAsString(value));
            scenario.id=null;scenario.version=0;return scenario;
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Invalid scenario YAML: "+e.getMessage(),e);}
    }
}
