package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable outbox + business inbox. No external source connector is executed here. */
@Component @DependsOn({"ingestionFlyway","businessFlyway"})
class ProjectionWorker {
    private final JdbcTemplate db,business;private final TransactionTemplate tx,bizTx;private final CatalogService catalog;private final SchemaRules rules;private final ObjectStore store;private final MeterRegistry meters;private final boolean enabled;
    ProjectionWorker(JdbcTemplate db,@Qualifier("businessJdbc") JdbcTemplate business,@Qualifier("ingestionTx") TransactionTemplate tx,@Qualifier("businessTx") TransactionTemplate bizTx,CatalogService catalog,SchemaRules rules,ObjectStore store,MeterRegistry meters,@Value("${app.worker-enabled}") boolean enabled){this.db=db;this.business=business;this.tx=tx;this.bizTx=bizTx;this.catalog=catalog;this.rules=rules;this.store=store;this.meters=meters;this.enabled=enabled;}
    @Scheduled(fixedDelay=1000,initialDelay=3000) public void tick(){if(!enabled)return;try{var job=claim();if(job!=null)process(job);}catch(Exception e){meters.counter("moss.worker.failures","phase","claim").increment();}}
    private Map<String,Object> claim(){return tx.execute(status->{var rows=db.queryForList("SELECT * FROM outbox_job WHERE (state IN ('PENDING','RETRY') AND available_at<=now()) OR (state='PROCESSING' AND lease_until<now()) ORDER BY available_at LIMIT 1 FOR UPDATE SKIP LOCKED");if(rows.isEmpty())return null;var job=rows.getFirst();long fence=((Number)job.get("fence")).longValue()+1;int attempts=((Number)job.get("attempts")).intValue()+1;db.update("UPDATE outbox_job SET state='PROCESSING',fence=?,attempts=?,lease_until=now()+interval '120 seconds',updated_at=now() WHERE id=?",fence,attempts,job.get("id"));job.put("fence",fence);job.put("attempts",attempts);db.update("UPDATE ingest_batch SET status='PROCESSING',updated_at=now() WHERE id=?",job.get("batch_id"));return job;});}
    private void process(Map<String,Object> job){UUID batchId=(UUID)job.get("batch_id");long fence=((Number)job.get("fence")).longValue();
        try{
            var batch=catalog.find("ingest_batch",batchId);byte[] bytes=store.get((String)batch.get("object_key"));if(!DomainSupport.sha(bytes).equals(batch.get("object_sha256")))throw new IllegalStateException("RAW_HASH_MISMATCH");
            JsonNode envelope=DomainSupport.parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));UUID dataset=(UUID)batch.get("dataset_id");int schemaVersion=((Number)batch.get("schema_version")).intValue();Integer mappingVersion=(Integer)batch.get("mapping_version");JsonNode schema=catalog.versionDocument(dataset,"schemas",schemaVersion),mapping=mappingVersion==null?null:catalog.versionDocument(dataset,"mappings",mappingVersion);
            String pipeline="generic-v1:d"+batch.get("dataset_id")+":s"+schemaVersion+":m"+(mappingVersion==null?0:mappingVersion);var raws=db.queryForList("SELECT * FROM raw_event WHERE tenant_id=? AND received_at=? AND batch_id=? ORDER BY ordinal",DomainSupport.TENANT,batch.get("received_at"),batchId);
            int index=0;for(var raw:raws){
                if(index++%50==0){int renewed=db.update("UPDATE outbox_job SET lease_until=now()+interval '120 seconds' WHERE id=? AND fence=? AND state='PROCESSING'",job.get("id"),fence);if(renewed!=1)throw new LeaseLost();}
                if(!raw.get("status").equals("PENDING"))continue;
                JsonNode event=envelope.path("events").get(((Number)raw.get("ordinal")).intValue());String eventId=(String)raw.get("event_id"),hash=(String)raw.get("event_hash");UUID source=(UUID)batch.get("source_id"),rawId=(UUID)raw.get("id");
                boolean same=Boolean.TRUE.equals(tx.execute(s->{db.update("INSERT INTO event_identity(tenant_id,source_id,event_id,payload_hash,first_raw_id) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",DomainSupport.TENANT,source,eventId,hash,rawId);return hash.equals(db.queryForObject("SELECT payload_hash FROM event_identity WHERE tenant_id=? AND source_id=? AND event_id=?",String.class,DomainSupport.TENANT,source,eventId));}));
                if(!same){mark(job,raw,"QUARANTINED","EVENT_ID_CONFLICT",Map.of("reason","同一来源事件ID出现不同内容"));continue;}
                boolean deletion=event.path("operation").asText("upsert").equals("delete");var errors=deletion?List.<Map<String,String>>of():rules.validate(schema,event.path("data"));
                if(!errors.isEmpty()){mark(job,raw,"QUARANTINED","SCHEMA_INVALID",Map.of("violations",errors));continue;}
                JsonNode projected;
                try{projected=deletion?event.path("data"):rules.project(event.path("data"),mapping);}catch(org.springframework.web.server.ResponseStatusException ex){mark(job,raw,"QUARANTINED","MAPPING_INVALID",Map.of("reason",Objects.requireNonNullElse(ex.getReason(),"字段转换失败")));continue;}
                String result=bizTx.execute(status->{int inserted=business.update("INSERT INTO business_inbox(tenant_id,consumer_name,source_id,event_id,projection_version,payload_hash,first_raw_id) VALUES (?,'generic-event',?,?,?,?,?) ON CONFLICT DO NOTHING",DomainSupport.TENANT,source,eventId,pipeline,hash,rawId);
                    if(inserted==0){var inbox=business.queryForMap("SELECT payload_hash,first_raw_id FROM business_inbox WHERE tenant_id=? AND consumer_name='generic-event' AND source_id=? AND event_id=? AND projection_version=?",DomainSupport.TENANT,source,eventId,pipeline);if(!hash.equals(inbox.get("payload_hash")))return "CONFLICT";return rawId.equals(inbox.get("first_raw_id"))?"APPLIED":"DUPLICATE";}
                    business.update("INSERT INTO standard_event_projection(id,tenant_id,source_id,dataset_id,batch_id,raw_id,event_id,entity_key,projection_version,schema_version,mapping_version,operation,payload,occurred_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?::jsonb,?)",UUID.randomUUID(),DomainSupport.TENANT,source,dataset,batchId,rawId,eventId,DomainSupport.json(event.path("entity_key")),pipeline,schemaVersion,mappingVersion,event.path("operation").asText("upsert"),DomainSupport.json(projected),OffsetDateTime.parse(event.path("time").asText()));return "APPLIED";});
                if("CONFLICT".equals(result))mark(job,raw,"QUARANTINED","EVENT_ID_CONFLICT",Map.of());else mark(job,raw,result,null,Map.of());
            }
            tx.executeWithoutResult(status->{var counts=db.queryForMap("SELECT count(*) FILTER(WHERE status='APPLIED') applied,count(*) FILTER(WHERE status='DUPLICATE') duplicates,count(*) FILTER(WHERE status='QUARANTINED') rejected,count(*) FILTER(WHERE status='PENDING') pending FROM raw_event WHERE tenant_id=? AND batch_id=? AND received_at=?",DomainSupport.TENANT,batchId,batch.get("received_at"));if(((Number)counts.get("pending")).longValue()!=0)throw new LeaseLost();int finished=db.update("UPDATE outbox_job SET state='DONE',lease_until=NULL,last_error=NULL,updated_at=now() WHERE id=? AND fence=? AND state='PROCESSING'",job.get("id"),fence);if(finished!=1)throw new LeaseLost();String state=((Number)counts.get("rejected")).longValue()>0?"COMPLETED_WITH_ERRORS":"COMPLETED";db.update("UPDATE ingest_batch SET status=?,applied_count=?,duplicate_count=?,rejected_count=?,error_code=NULL,updated_at=now() WHERE id=?",state,counts.get("applied"),counts.get("duplicates"),counts.get("rejected"),batchId);});meters.counter("moss.worker.batches","result","completed").increment();
        }catch(LeaseLost ignored){/* Another lease owns status progression; inbox still protects writes. */}
        catch(Exception ex){int attempts=((Number)job.get("attempts")).intValue();String code=ex instanceof ObjectStore.StoreUnavailable?"RAW_STORAGE_UNAVAILABLE":ex instanceof org.springframework.dao.DataAccessException?"DATABASE_UNAVAILABLE":"PROCESSING_ERROR";String state=attempts>=5?"FAILED":"RETRY";try{tx.executeWithoutResult(s->{int updated=db.update("UPDATE outbox_job SET state=?,last_error=?,lease_until=NULL,available_at=now()+(? * interval '1 second'),updated_at=now() WHERE id=? AND fence=?",state,code,Math.min(300,5*(1<<Math.min(attempts,6))),job.get("id"),fence);if(updated==1)db.update("UPDATE ingest_batch SET status=?,error_code=?,updated_at=now() WHERE id=?",state,code,batchId);});}catch(Exception ignored){}meters.counter("moss.worker.failures","phase","process").increment();}
    }
    private void mark(Map<String,Object> job,Map<String,Object> raw,String state,String error,Map<String,?> details){int changed=db.update("UPDATE raw_event SET status=?,error_code=?,error_detail=?::jsonb,updated_at=now() WHERE tenant_id=? AND received_at=? AND id=? AND EXISTS(SELECT 1 FROM outbox_job WHERE id=? AND fence=? AND state='PROCESSING')",state,error,DomainSupport.json(details),DomainSupport.TENANT,raw.get("received_at"),raw.get("id"),job.get("id"),job.get("fence"));if(changed!=1)throw new LeaseLost();meters.counter("moss.worker.events","result",state.toLowerCase(Locale.ROOT)).increment();}
    private static class LeaseLost extends RuntimeException{}
}
