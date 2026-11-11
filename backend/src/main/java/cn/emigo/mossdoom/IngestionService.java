package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class IngestionService {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final CatalogService catalog;private final ObjectStore store;private final AuditService audit;private final MeterRegistry meters;
    private final ConcurrentHashMap<UUID,Window> rate=new ConcurrentHashMap<>();
    IngestionService(JdbcTemplate db,@Qualifier("ingestionTx") TransactionTemplate tx,CatalogService c,ObjectStore s,AuditService a,MeterRegistry meters){this.db=db;this.tx=tx;catalog=c;store=s;audit=a;this.meters=meters;}
    Map<String,Object> receive(ClientTokenFilter.CollectorIdentity client,String key,byte[] bytes){
        return receive(client,key,bytes,false);
    }
    Map<String,Object> receiveCollected(ClientTokenFilter.CollectorIdentity client,String key,byte[] bytes){
        return receive(client,key,bytes,true);
    }
    Map<String,Object> receiveCollectedPinned(ClientTokenFilter.CollectorIdentity client,String key,byte[] bytes,Integer mapping){
        return receive(client,key,bytes,true,true,mapping);
    }
    private Map<String,Object> receive(ClientTokenFilter.CollectorIdentity client,String key,byte[] bytes,boolean collected){
        return receive(client,key,bytes,collected,false,null);
    }
    private Map<String,Object> receive(ClientTokenFilter.CollectorIdentity client,String key,byte[] bytes,boolean collected,boolean pinned,Integer mappingExpected){
        if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,128}"))throw DomainSupport.invalid("Idempotency-Key必填，最多128个英文、数字或_.:-字符");
        if(!DomainSupport.TENANT.equals(client.tenant()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"采集身份所属范围无效");
        if(bytes.length>5*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"单批不能超过5MiB");
        JsonNode body;try{body=DomainSupport.parse(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString());}catch(java.nio.charset.CharacterCodingException e){throw DomainSupport.invalid("接收协议只允许UTF-8 JSON");}
        String hash=DomainSupport.sha(DomainSupport.canonical(body)),objectHash=DomainSupport.sha(bytes);
        var previous=existing(client,key);if(previous!=null)return same(previous,hash);
        long minute=System.currentTimeMillis()/60000;Window window=rate.compute(client.id(),(id,old)->old==null||old.minute!=minute?new Window(minute,1):new Window(minute,old.count+1));if(window.count>120)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"测试环境每个采集身份每分钟最多提交120批");
        if(!body.path("protocol_version").asText().equals("1.0")||!body.path("source_instance_id").asText().equals(client.source().toString())||!body.path("dataset_id").asText().equals(client.dataset().toString()))throw DomainSupport.invalid("协议版本或来源、数据集与采集身份不匹配");
        if(body.has("tenant_id")&&!body.path("tenant_id").asText().equals(client.tenant()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"租户只能由采集身份确定");
        Map<String,Object> source=catalog.find("source_instance",client.source()),dataset=catalog.find("dataset",client.dataset());
        if(pinned&&!Objects.equals(dataset.get("current_mapping"),mappingExpected))throw DomainSupport.conflict("任务绑定的映射版本已经改变");
        if(!source.get("state").equals("ACTIVE")||!source.get("mode").equals(collected?"PULL":"PUSH")||!source.get("environment").equals("test")||!dataset.get("state").equals("ACTIVE"))throw DomainSupport.conflict("连接或数据集未启用，或不属于已授权的test接入方向");
        int schema=body.path("schema_version").asInt(-1);if(!Objects.equals(dataset.get("active_schema_version"),schema))throw DomainSupport.conflict("schema_version与当前发布版本不一致，请重新获取结构定义");
        catalog.versionDocument(client.dataset(),"schemas",schema);Integer mapping=(Integer)dataset.get("current_mapping");
        JsonNode events=body.path("events");if(!events.isArray()||events.isEmpty()||events.size()>1000)throw DomainSupport.invalid("每批需包含1至1000条事件");
        for(JsonNode event:events)checkEnvelope(client.source(),event);
        // A content-addressed immutable raw object is durable before the receipt transaction.
        String objectKey="raw/test/"+client.tenant()+"/"+client.id()+"/"+objectHash+".json";
        try{store.put(objectKey,bytes);}catch(ObjectStore.StoreUnavailable ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"原料存储暂不可用，本次尚未接收；请使用相同幂等键稍后重试");}
        UUID batch=UUID.randomUUID();OffsetDateTime received=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        try{
            tx.executeWithoutResult(status->{db.update("INSERT INTO ingest_batch(id,tenant_id,producer_id,source_id,dataset_id,idempotency_key,request_hash,object_key,object_sha256,schema_version,mapping_version,event_count,request_id,received_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",batch,client.tenant(),client.id(),client.source(),client.dataset(),key,hash,objectKey,objectHash,schema,mapping,events.size(),RequestContext.requestId(),received);
                int ordinal=0;for(JsonNode event:events){db.update("INSERT INTO raw_event(id,tenant_id,batch_id,received_at,ordinal,source_id,event_id,event_hash,entity_key) VALUES (?,?,?,?,?,?,?,?,?::jsonb)",UUID.randomUUID(),client.tenant(),batch,received,ordinal++,client.source(),event.path("id").asText(),DomainSupport.sha(DomainSupport.canonical(event)),DomainSupport.json(event.path("entity_key")));}
                db.update("INSERT INTO outbox_job(id,tenant_id,batch_id) VALUES (?,?,?)",UUID.randomUUID(),client.tenant(),batch);db.update("UPDATE api_client SET last_used_at=now() WHERE id=? AND (last_used_at IS NULL OR last_used_at<now()-interval '1 minute')",client.id());audit.recordAs(client.getName(),"BATCH_RECEIVED","batch",batch,Map.of("events",events.size(),"schemaVersion",schema));});
        }catch(DuplicateKeyException e){var existing=existing(client,key);if(existing==null)throw e;return same(existing,hash);}
        meters.counter("moss.ingestion.batches","result","received").increment();return receipt(catalog.find("ingest_batch",batch));
    }
    static void checkEnvelope(UUID source,JsonNode event){
        DomainSupport.required(event,"id",200);
        if(!event.path("specversion").asText().equals("1.0")||!event.path("source").asText().equals("urn:mossdoom:source:"+source))throw DomainSupport.invalid("事件specversion或source不符合已授权来源");
        DomainSupport.required(event,"type",200);try{OffsetDateTime.parse(DomainSupport.required(event,"time",80));}catch(Exception e){throw DomainSupport.invalid("事件time需为带时区的ISO时间");}
        if(!event.path("data").isObject())throw DomainSupport.invalid("事件data需为JSON对象");
        String op=event.path("operation").asText("upsert");if(!Set.of("upsert","delete").contains(op))throw DomainSupport.invalid("首版只支持完整快照upsert或delete墓碑，暂不支持patch");
        JsonNode entity=event.path("entity_key");if(!entity.isObject()||entity.isEmpty()||entity.size()>10)throw DomainSupport.invalid("entity_key需为非空对象，最多10个标识字段");
        entity.fields().forEachRemaining(e->{if(e.getKey().length()>64||(!e.getValue().isTextual()&&!e.getValue().isIntegralNumber())||e.getValue().asText().isBlank()||e.getValue().asText().length()>240)throw DomainSupport.invalid("entity_key只接受有限长度的字符串或整数标识");});
        if(event.path("scope").has("environment")&&!event.path("scope").path("environment").asText().equals("test"))throw DomainSupport.invalid("事件scope.environment必须为test");
    }
    private Map<String,Object> existing(ClientTokenFilter.CollectorIdentity c,String key){var rows=db.queryForList("SELECT * FROM ingest_batch WHERE tenant_id=? AND producer_id=? AND idempotency_key=?",c.tenant(),c.id(),key);return rows.isEmpty()?null:rows.getFirst();}
    private Map<String,Object> same(Map<String,Object> batch,String hash){if(!Objects.equals(batch.get("request_hash"),hash))throw DomainSupport.conflict("同一个幂等键已经接收过不同内容，请检查生产方重试逻辑");return receipt(batch);}
    Map<String,Object> receipt(Map<String,Object> batch){return Map.of("batch_id",batch.get("id").toString(),"receipt_state","DURABLY_RECEIVED","processing_state",batch.get("status"),"received_count",batch.get("event_count"),"status_url","/ingestion/v1/batches/"+batch.get("id"),"request_id",batch.get("request_id").toString());}
    Object status(ClientTokenFilter.CollectorIdentity client,UUID id){Map<String,Object> b=catalog.find("ingest_batch",id);if(!b.get("producer_id").equals(client.id())||!b.get("source_id").equals(client.source())||!b.get("dataset_id").equals(client.dataset()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"批次不存在");Map<String,Object> out=new LinkedHashMap<>(receipt(b));for(String key:List.of("applied_count","duplicate_count","rejected_count","received_at","updated_at","error_code"))out.put(key,DomainSupport.row(b).get(key));return out;}
    record Window(long minute,int count){}
}
