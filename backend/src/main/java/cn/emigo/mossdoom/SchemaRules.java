package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
class SchemaRules {
    private final JsonSchemaFactory factory=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final Set<String> BLOCKED=Set.of("$ref","$dynamicRef","$recursiveRef","pattern","patternProperties","allOf","anyOf","oneOf","not","if","then","else");
    void checkSchema(JsonNode document){
        if(!document.isObject()||!document.path("type").asText().equals("object"))throw DomainSupport.invalid("首版Schema根类型必须为object");
        if(DomainSupport.canonical(document).length>65536)throw DomainSupport.invalid("Schema不能超过64KiB");
        scan(document,0,new int[]{0});
        try{factory.getSchema(document);}catch(Exception e){throw DomainSupport.invalid("Schema定义无法编译");}
    }
    private void scan(JsonNode node,int depth,int[] nodes){
        if(depth>16||++nodes[0]>2000)throw DomainSupport.invalid("Schema嵌套或字段数量超过首版限制");
        if(node.isObject()){node.fields().forEachRemaining(e->{if(BLOCKED.contains(e.getKey()))throw DomainSupport.invalid("首版不支持引用、正则或组合关键词："+e.getKey());if(e.getKey().equals("$schema")&&!e.getValue().asText().equals("https://json-schema.org/draft/2020-12/schema"))throw DomainSupport.invalid("仅支持内置2020-12元结构，禁止远程解析");if(e.getKey().equals("properties")&&e.getValue().isObject())e.getValue().elements().forEachRemaining(v->scan(v,depth+1,nodes));else if(!Set.of("const","enum").contains(e.getKey()))scan(e.getValue(),depth+1,nodes);});}
        else if(node.isArray())node.forEach(v->scan(v,depth+1,nodes));
    }
    List<Map<String,String>> validate(JsonNode schema,JsonNode payload){
        JsonSchema compiled=factory.getSchema(schema);
        return compiled.validate(payload).stream().limit(20).map(e->Map.of("rule",e.getType(),"path",e.getInstanceLocation().toString())).toList();
    }
    void checkMapping(JsonNode mapping){
        if(!mapping.isObject()||!mapping.path("fields").isObject()||mapping.path("fields").size()>100)throw DomainSupport.invalid("映射需包含fields对象，最多100个字段");
        mapping.path("fields").fields().forEachRemaining(e->{if(!e.getKey().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")||!e.getValue().isTextual()||!e.getValue().asText().startsWith("/")||e.getValue().asText().length()>240)throw DomainSupport.invalid("映射目标需为字段名，来源使用JSON Pointer，例如 /sku");});
        if(mapping.has("types")){if(!mapping.get("types").isObject())throw DomainSupport.invalid("types必须为对象");mapping.get("types").fields().forEachRemaining(e->{if(!Set.of("string","decimal","integer","boolean").contains(e.getValue().asText())||!mapping.path("fields").has(e.getKey()))throw DomainSupport.invalid("映射类型仅支持string/decimal/integer/boolean，且需对应fields字段");});}
        mapping.fieldNames().forEachRemaining(k->{if(!Set.of("fields","types","includeUnmapped").contains(k))throw DomainSupport.invalid("不支持的映射属性："+k);});
    }
    JsonNode project(JsonNode data,JsonNode mapping){
        if(mapping==null)return data.deepCopy();
        ObjectNode out=mapping.path("includeUnmapped").asBoolean(false)?((ObjectNode)data).deepCopy():DomainSupport.JSON.createObjectNode();
        mapping.path("fields").fields().forEachRemaining(e->{JsonNode value=data.at(e.getValue().asText());if(value.isMissingNode())return;String type=mapping.path("types").path(e.getKey()).asText("");
            if(value.isNull()||type.isEmpty()){out.set(e.getKey(),value);return;}
            try{switch(type){case "string"->out.put(e.getKey(),value.asText());case "decimal"->out.put(e.getKey(),decimal(value));case "integer"->out.put(e.getKey(),decimal(value).toBigIntegerExact());case "boolean"->{if(!Set.of("true","false").contains(value.asText()))throw new IllegalArgumentException();out.put(e.getKey(),Boolean.parseBoolean(value.asText()));}default->throw new IllegalArgumentException();}}
            catch(Exception ex){throw DomainSupport.invalid("映射字段类型转换失败："+e.getKey());}
        });return out;
    }
    private BigDecimal decimal(JsonNode value){if(value.asText().length()>128)throw new IllegalArgumentException();BigDecimal number=new BigDecimal(value.asText());if(number.precision()>38||Math.abs(number.scale())>12)throw new IllegalArgumentException();return number;}
}
