package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class DomainSupport {
    static final String TENANT="mossdoom";
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    static { JSON.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(40).maxStringLength(5*1024*1024).maxDocumentLength(6*1024*1024).build()); }
    static ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    static String required(JsonNode node,String field,int max){String s=node.path(field).asText("").trim(); if(s.isEmpty()||s.length()>max)throw invalid("字段 "+field+" 必填且长度不能超过 "+max);return s;}
    static UUID id(String value){try{return UUID.fromString(value);}catch(Exception e){throw invalid("无效的记录编号");}}
    static JsonNode parse(String value){try{return JSON.readTree(value);}catch(Exception e){throw invalid("JSON格式不正确");}}
    static String json(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Cannot serialize document");}}
    static byte[] canonical(JsonNode node){return json(sort(node)).getBytes(StandardCharsets.UTF_8);}
    static JsonNode sort(JsonNode node){
        if(node.isObject()){ObjectNode result=JSON.createObjectNode();List<String> keys=new ArrayList<>();node.fieldNames().forEachRemaining(keys::add);Collections.sort(keys);for(String key:keys)result.set(key,sort(node.get(key)));return result;}
        if(node.isArray()){ArrayNode result=JSON.createArrayNode();node.forEach(v->result.add(sort(v)));return result;}return node;
    }
    static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    static Map<String,Object> row(Map<String,Object> input){Map<String,Object> out=new LinkedHashMap<>();input.forEach((key,value)->{
        if(value instanceof UUID)out.put(key,value.toString());
        else if(value instanceof java.sql.Timestamp t)out.put(key,t.toInstant().toString());
        else if(value instanceof OffsetDateTime t)out.put(key,t.toInstant().toString());
        else if(value!=null&&value.getClass().getName().equals("org.postgresql.util.PGobject"))out.put(key,parse(value.toString()));
        else out.put(key,value);
    });return out;}
    static Map<String,Object> page(List<Map<String,Object>> rows,int limit,String timeColumn){
        boolean more=rows.size()>limit;List<Map<String,Object>> selected=rows.subList(0,Math.min(limit,rows.size()));String cursor=null;
        if(more&&!selected.isEmpty()){Map<String,Object> last=row(selected.getLast());cursor=Base64.getUrlEncoder().withoutPadding().encodeToString((last.get(timeColumn)+"|"+last.get("id")).getBytes(StandardCharsets.UTF_8));}
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",selected.stream().map(DomainSupport::row).toList());result.put("nextCursor",cursor);return result;
    }
    static Object[] cursor(String cursor){try{String[] values=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",2);return new Object[]{OffsetDateTime.parse(values[0].replace(' ','T')),UUID.fromString(values[1])};}catch(Exception e){throw invalid("分页游标无效，请刷新列表");}}
}
