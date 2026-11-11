package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicitly imports an already captured response; never repeats a source query. */
@Service
class CollectionImport {
    private final JdbcTemplate db;private final CatalogService catalog;private final ObjectStore store;
    private final IngestionService ingestion;private final TransactionTemplate tx;private final AuditService audit;
    CollectionImport(JdbcTemplate db,CatalogService catalog,ObjectStore store,IngestionService ingestion,@Qualifier("ingestionTx") TransactionTemplate tx,AuditService audit){this.db=db;this.catalog=catalog;this.store=store;this.ingestion=ingestion;this.tx=tx;this.audit=audit;}
    Map<String,Object> run(UUID id){var rows=db.queryForList("SELECT r.*,e.items_pointer FROM collection_probe r JOIN collection_endpoint e ON e.id=r.endpoint_id WHERE r.tenant_id=? AND r.id=?",DomainSupport.TENANT,id);if(rows.isEmpty())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"试查记录不存在");return rows.getFirst();}
    byte[] raw(UUID id){var record=run(id);if(record.get("raw_object_key")==null)throw DomainSupport.conflict("本次没有可读取的原始响应");byte[] bytes=store.get((String)record.get("raw_object_key"));if(!DomainSupport.sha(bytes).equals(record.get("raw_sha256")))throw DomainSupport.conflict("来源原文SHA256校验失败");audit.record("COLLECTION_RAW_READ","collection_probe",id,Map.of("bytes",bytes.length));return bytes;}
    Object importRun(UUID id,UUID dataset){
        return importRun(id,dataset,null,null);
    }
    Object importRun(UUID id,UUID dataset,Integer schemaExpected,Integer mappingExpected){
        var record=run(id);if(!record.get("state").equals("PREVIEW_STORED"))throw DomainSupport.conflict("只有成功留存的来源响应可以导入");
        var d=catalog.find("dataset",dataset);if(!d.get("source_id").equals(record.get("source_id")))throw DomainSupport.invalid("数据集必须属于该响应来源");
        if(schemaExpected!=null&&(!Objects.equals(schemaExpected,d.get("active_schema_version"))||!Objects.equals(mappingExpected,d.get("current_mapping"))))throw DomainSupport.conflict("任务绑定的数据结构或映射已改变");
        if(record.get("import_batch_id")!=null){if(!dataset.equals(record.get("import_dataset_id")))throw DomainSupport.conflict("响应已经绑定其他数据集");return ingestion.receipt(catalog.find("ingest_batch",(UUID)record.get("import_batch_id")));}
        if(!d.get("state").equals("ACTIVE")||d.get("active_schema_version")==null)throw DomainSupport.conflict("请先发布结构并启用数据集");
        byte[] bytes=raw(id);JsonNode rows=DomainSupport.parse(new String(bytes,StandardCharsets.UTF_8)).at((String)record.get("items_pointer"));
        if(!rows.isArray()||rows.size()>1000)throw DomainSupport.conflict("来源记录不符合有界批次协议");if(rows.isEmpty())return Map.of("state","EMPTY_RESPONSE","received_count",0,"notice","空响应已留存，不生成虚构事件或空批次");
        var reservation=tx.execute(status->{var locked=db.queryForMap("SELECT * FROM collection_probe WHERE tenant_id=? AND id=? FOR UPDATE",DomainSupport.TENANT,id);if(locked.get("import_dataset_id")!=null){if(!dataset.equals(locked.get("import_dataset_id")))throw DomainSupport.conflict("试查已绑定其他数据集");return locked;}
            var current=db.queryForMap("SELECT * FROM dataset WHERE tenant_id=? AND id=? FOR UPDATE",DomainSupport.TENANT,dataset);if(!current.get("state").equals("ACTIVE")||current.get("active_schema_version")==null)throw DomainSupport.conflict("数据集状态已变化");
            if(schemaExpected!=null&&(!Objects.equals(schemaExpected,current.get("active_schema_version"))||!Objects.equals(mappingExpected,current.get("current_mapping"))))throw DomainSupport.conflict("任务绑定的数据结构或映射已改变");
            var existing=db.queryForList("SELECT producer_id FROM collection_producer WHERE dataset_id=?",dataset);UUID producer;
            if(existing.isEmpty()){producer=UUID.randomUUID();db.update("INSERT INTO api_client(id,tenant_id,name,source_id,dataset_id,token_hash,created_by) VALUES (?,?,?,?,?,?,?)",producer,DomainSupport.TENANT,"平台内部采集",d.get("source_id"),dataset,DomainSupport.sha(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)),RequestContext.actor());db.update("INSERT INTO collection_producer(dataset_id,producer_id) VALUES (?,?)",dataset,producer);}else producer=(UUID)existing.getFirst().get("producer_id");
            db.update("UPDATE collection_probe SET import_dataset_id=?,import_schema_version=?,import_producer_id=? WHERE tenant_id=? AND id=?",dataset,current.get("active_schema_version"),producer,DomainSupport.TENANT,id);
            return db.queryForMap("SELECT * FROM collection_probe WHERE tenant_id=? AND id=?",DomainSupport.TENANT,id);
        });
        UUID producer=(UUID)reservation.get("import_producer_id");if(!Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM api_client WHERE tenant_id=? AND id=?",Boolean.class,DomainSupport.TENANT,producer)))throw DomainSupport.conflict("内部采集身份已停用");
        var envelope=DomainSupport.JSON.createObjectNode();envelope.put("protocol_version","1.0");envelope.put("source_instance_id",record.get("source_id").toString());envelope.put("dataset_id",dataset.toString());envelope.put("schema_version",((Number)reservation.get("import_schema_version")).intValue());var events=envelope.putArray("events");int ordinal=0;
        String observed=DomainSupport.row(record).get("started_at").toString();
        for(JsonNode row:rows){if(!row.isObject())throw DomainSupport.invalid("来源记录必须为JSON对象");var event=events.addObject();event.put("id",id+":"+ordinal);event.put("specversion","1.0");event.put("source","urn:mossdoom:source:"+record.get("source_id"));event.put("type","moss.collection.observed-snapshot");event.put("time",observed);event.put("operation","upsert");var identity=event.putObject("entity_key");identity.put("collection_run",id.toString());identity.put("row_index",ordinal++);event.set("data",row);var scope=event.putObject("scope");scope.put("environment","test");scope.put("source_raw_sha256",record.get("raw_sha256").toString());}
        var client=new ClientTokenFilter.CollectorIdentity(producer,DomainSupport.TENANT,(UUID)record.get("source_id"),dataset);
        var receipt=schemaExpected==null?ingestion.receiveCollected(client,"probe:"+id,DomainSupport.canonical(envelope)):ingestion.receiveCollectedPinned(client,"probe:"+id,DomainSupport.canonical(envelope),mappingExpected);UUID batch=UUID.fromString(receipt.get("batch_id").toString());
        tx.executeWithoutResult(status->{db.update("UPDATE collection_probe SET import_batch_id=? WHERE tenant_id=? AND id=?",batch,DomainSupport.TENANT,id);audit.record("COLLECTION_IMPORT","collection_probe",id,Map.of("batch",batch.toString(),"records",rows.size()));});
        return receipt;
    }
}
