package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable source jobs. Source calls are bounded and every successful page is reusable after restart. */
@Service @DependsOn({"ingestionFlyway","businessFlyway"})
class CollectionJobs {
    private final JdbcTemplate db;
    private final CatalogService catalog;
    private final CollectionConfiguration configuration;
    private final CollectionImport importer;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private static final Set<String> RETRYABLE=Set.of("FAILED","WAITING_AUTH","WAITING_SOURCE");
    CollectionJobs(JdbcTemplate db,CatalogService catalog,CollectionConfiguration configuration,CollectionImport importer,@Qualifier("ingestionTx") TransactionTemplate tx,AuditService audit){this.db=db;this.catalog=catalog;this.configuration=configuration;this.importer=importer;this.tx=tx;this.audit=audit;}

    record Options(int pages,int records,int window,String timezone){}
    static int integer(JsonNode input,String key,int minimum,int maximum){JsonNode value=input.path(key);if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<minimum||value.asInt()>maximum)throw DomainSupport.invalid(key+"超出允许范围："+minimum+"至"+maximum);return value.asInt();}
    static Options options(JsonNode input,Map<String,Object> profile){
        int pages=integer(input,"max_pages",1,100),records=integer(input,"max_records",1,10000),window=integer(input,"window_seconds",0,5097600);
        String timezone=DomainSupport.required(input,"query_timezone",80);try{ZoneId.of(timezone);}catch(Exception e){throw DomainSupport.invalid("查询时区不正确");}
        JsonNode query=DomainSupport.parse(profile.get("request_json").toString());int size=query.path(profile.get("size_field").toString()).asInt();if(records<size)throw DomainSupport.invalid("记录上限不能小于固定的单页大小");
        if(window>0&&(!profile.get("endpoint_id").equals("xinwuyun.produce-orders")||window<60))throw DomainSupport.invalid("滚动时间窗口仅用于已核实的新物云查询，至少60秒");
        return new Options(pages,records,window,timezone);
    }
    static JsonNode freezeQuery(Map<String,Object> profile,Options options,Instant now){
        ObjectNode query=(ObjectNode)DomainSupport.parse(profile.get("request_json").toString());
        if(options.window()>0){ZonedDateTime end=now.atZone(ZoneId.of(options.timezone()));var format=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");query.put("modified_start",end.minusSeconds(options.window()).format(format));query.put("modified_end",end.format(format));}
        CollectionConfiguration.validateRequest(profile.get("endpoint_id").toString(),query);return query;
    }
    private Map<String,Object> dataset(UUID source,UUID dataset){var row=catalog.find("dataset",dataset);if(!source.equals(row.get("source_id")))throw DomainSupport.invalid("数据集不属于该来源");return row;}
    Object plan(UUID source){catalog.find("source_instance",source);var rows=db.queryForList("SELECT * FROM collection_schedule WHERE tenant_id=? AND source_id=?",DomainSupport.TENANT,source);return rows.isEmpty()?Map.of("configured",false,"revision",0):DomainSupport.row(rows.getFirst());}
    Object savePlan(UUID source,JsonNode input){
        var profile=configuration.runtimeProfile(source);var options=options(input,profile);int interval=integer(input,"interval_seconds",60,86400),revision=input.path("revision").asInt(-1);
        UUID target=DomainSupport.id(DomainSupport.required(input,"dataset_id",36));dataset(source,target);if(!input.path("enabled").isBoolean())throw DomainSupport.invalid("enabled需为布尔值");boolean enabled=input.path("enabled").asBoolean();
        if(enabled)readySource(source,target);
        return tx.execute(status->{var rows=db.queryForList("SELECT * FROM collection_schedule WHERE tenant_id=? AND source_id=? FOR UPDATE",DomainSupport.TENANT,source);
            if(rows.isEmpty()){if(revision!=0)throw DomainSupport.conflict("计划版本已变更");db.update("INSERT INTO collection_schedule(source_id,tenant_id,dataset_id,enabled,interval_seconds,max_pages,max_records,window_seconds,query_timezone) VALUES (?,?,?,?,?,?,?,?,?)",source,DomainSupport.TENANT,target,enabled,interval,options.pages(),options.records(),options.window(),options.timezone());}
            else{int n=db.update("UPDATE collection_schedule SET dataset_id=?,enabled=?,interval_seconds=?,max_pages=?,max_records=?,window_seconds=?,query_timezone=?,next_run_at=CASE WHEN ? AND NOT enabled THEN now() ELSE next_run_at END,revision=revision+1,updated_at=now() WHERE tenant_id=? AND source_id=? AND revision=?",target,enabled,interval,options.pages(),options.records(),options.window(),options.timezone(),enabled,DomainSupport.TENANT,source,revision);if(n!=1)throw DomainSupport.conflict("计划已被修改，请刷新");}
            audit.record("COLLECTION_PLAN_SAVE","source",source,Map.of("enabled",enabled,"intervalSeconds",interval));return plan(source);});
    }
    private void readySource(UUID source,UUID target){var s=catalog.find("source_instance",source);var d=dataset(source,target);if(!s.get("state").equals("ACTIVE")||!d.get("state").equals("ACTIVE")||d.get("active_schema_version")==null)throw DomainSupport.conflict("来源和已发布数据集须先启用");}
    Object create(UUID source,JsonNode input,String key){
        if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,128}"))throw DomainSupport.invalid("创建采集任务需要稳定的Idempotency-Key");
        try{return createInternal(source,input,key,"MANUAL");}catch(DuplicateKeyException ex){var existing=db.queryForList("SELECT id FROM collection_job WHERE tenant_id=? AND source_id=? AND state IN ('QUEUED','RUNNING','RETRY','WAITING_AUTH','WAITING_PROJECTION','WAITING_SOURCE')",DomainSupport.TENANT,source);throw DomainSupport.conflict(existing.isEmpty()?"任务编号冲突，请刷新检查":"来源已有未结束任务："+existing.getFirst().get("id"));}
    }
    private Object createInternal(UUID source,JsonNode input,String key,String trigger){
        UUID target=DomainSupport.id(DomainSupport.required(input,"dataset_id",36));var p=configuration.runtimeProfile(source);Options limits=options(input,p);
        String requestHash=DomainSupport.sha(DomainSupport.canonical(DomainSupport.JSON.valueToTree(Map.of("source",source.toString(),"dataset",target.toString(),"pages",limits.pages(),"records",limits.records(),"window",limits.window(),"timezone",limits.timezone()))));
        return tx.execute(status->{var old=db.queryForList("SELECT * FROM collection_job WHERE tenant_id=? AND idempotency_key=?",DomainSupport.TENANT,key);if(!old.isEmpty()){if(!requestHash.equals(old.getFirst().get("request_hash")))throw DomainSupport.conflict("同一任务幂等键已用于不同配置");return DomainSupport.row(old.getFirst());}
            readySource(source,target);var d=dataset(source,target);JsonNode query=freezeQuery(p,limits,Instant.now());UUID id=UUID.randomUUID();
            db.update("INSERT INTO collection_job(id,tenant_id,source_id,dataset_id,trigger_type,idempotency_key,request_hash,schema_version,mapping_version,endpoint_id,base_url,profile_revision,config_fingerprint,profile_request_json,query_json,max_pages,max_records,next_page,request_id,created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?)",id,DomainSupport.TENANT,source,target,trigger,key,requestHash,d.get("active_schema_version"),d.get("current_mapping"),p.get("endpoint_id"),p.get("base_url"),p.get("revision"),CollectionConfiguration.fingerprint(p),p.get("request_json").toString(),DomainSupport.json(query),limits.pages(),limits.records(),query.path(p.get("page_field").toString()).asInt(),RequestContext.requestId(),RequestContext.actor());
            audit.record("COLLECTION_JOB_CREATE","collection_job",id,Map.of("source",source.toString(),"trigger",trigger,"maxPages",limits.pages()));return detail(id);});
    }
    Map<String,Object> find(UUID id){var rows=db.queryForList("SELECT * FROM collection_job WHERE tenant_id=? AND id=?",DomainSupport.TENANT,id);if(rows.isEmpty())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"采集任务不存在");return rows.getFirst();}
    Object detail(UUID id){var out=new LinkedHashMap<>(DomainSupport.row(find(id)));out.put("pages",db.queryForList("SELECT * FROM collection_job_page WHERE job_id=? ORDER BY page_no LIMIT 100",id).stream().map(DomainSupport::row).toList());out.put("queries",db.queryForList("SELECT id,page_no,state,error_code,profile_revision,record_count,started_at,finished_at FROM collection_probe WHERE tenant_id=? AND job_id=? ORDER BY started_at DESC LIMIT 100",DomainSupport.TENANT,id).stream().map(DomainSupport::row).toList());return out;}
    Object list(UUID source,String cursor,int limit){catalog.find("source_instance",source);limit=Math.max(1,Math.min(limit,50));List<Object> args=new ArrayList<>(List.of(DomainSupport.TENANT,source));String where="";if(!cursor.isBlank()){where=" AND (created_at,id)<(?::timestamptz,?::uuid)";args.addAll(Arrays.asList(DomainSupport.cursor(cursor)));}args.add(limit+1);return DomainSupport.page(db.queryForList("SELECT * FROM collection_job WHERE tenant_id=? AND source_id=?"+where+" ORDER BY created_at DESC,id DESC LIMIT ?",args.toArray()),limit,"created_at");}
    Object retry(UUID id){return tx.execute(status->{var j=find(id);if(!RETRYABLE.contains(j.get("state")))throw DomainSupport.conflict("此任务当前不允许重试");var p=configuration.runtimeProfile((UUID)j.get("source_id"));if(!CollectionConfiguration.fingerprint(p).equals(j.get("config_fingerprint")))throw DomainSupport.conflict("查询或目标配置已改变，请新建任务以保留原任务快照");readySource((UUID)j.get("source_id"),(UUID)j.get("dataset_id"));int changed=db.update("UPDATE collection_job SET state='QUEUED',available_at=now(),failures=0,error_code=NULL,finished_at=NULL,profile_revision=?,updated_at=now() WHERE tenant_id=? AND id=? AND state=?",p.get("revision"),DomainSupport.TENANT,id,j.get("state"));if(changed!=1)throw DomainSupport.conflict("任务状态已变化");audit.record("COLLECTION_JOB_RETRY","collection_job",id,Map.of());return detail(id);});}
    Object cancel(UUID id){find(id);int n=db.update("UPDATE collection_job SET state='CANCELLED',fence=fence+1,lease_until=NULL,finished_at=now(),updated_at=now() WHERE tenant_id=? AND id=? AND state IN ('QUEUED','RUNNING','RETRY','WAITING_AUTH','WAITING_PROJECTION','WAITING_SOURCE')",DomainSupport.TENANT,id);if(n!=1)throw DomainSupport.conflict("任务已结束");audit.record("COLLECTION_JOB_CANCEL","collection_job",id,Map.of());return detail(id);}

    @Scheduled(fixedDelay=2000,initialDelay=5000) public void scheduleTick(){try{scheduleOne();}catch(Exception ignored){/* Re-evaluate database scheduling state on the next pass. */}}
    @Scheduled(fixedDelay=1000,initialDelay=5000) public void tick(){
        Map<String,Object> job=null;try{job=claim();if(job!=null)execute(job);}catch(Exception ex){if(job!=null)fail(job,"COLLECTION_PROCESSING_FAILED",false);}
    }
    private void scheduleOne(){tx.executeWithoutResult(status->{var due=db.queryForList("SELECT * FROM collection_schedule WHERE tenant_id=? AND enabled AND next_run_at<=now() ORDER BY next_run_at LIMIT 1 FOR UPDATE SKIP LOCKED",DomainSupport.TENANT);if(due.isEmpty())return;var plan=due.getFirst();UUID source=(UUID)plan.get("source_id");String result;
        var active=db.queryForList("SELECT state FROM collection_job WHERE tenant_id=? AND source_id=? AND state IN ('QUEUED','RUNNING','RETRY','WAITING_AUTH','WAITING_PROJECTION','WAITING_SOURCE')",DomainSupport.TENANT,source);
        if(!active.isEmpty())result="BLOCKED_"+active.getFirst().get("state");
        else{try{readySource(source,(UUID)plan.get("dataset_id"));createInternal(source,DomainSupport.JSON.valueToTree(DomainSupport.row(plan)),"schedule:"+source+":"+OffsetDateTime.parse(DomainSupport.row(plan).get("next_run_at").toString()).toInstant().toEpochMilli(),"SCHEDULE");result="QUEUED";}catch(org.springframework.web.server.ResponseStatusException ex){result="CONFIGURATION_REQUIRED";}}
        db.update("UPDATE collection_schedule SET next_run_at=now()+(interval_seconds*interval '1 second'),last_result=?,updated_at=now() WHERE source_id=? AND tenant_id=?",result,source,DomainSupport.TENANT);
    });}
    private Map<String,Object> claim(){return tx.execute(status->{var rows=db.queryForList("SELECT * FROM collection_job WHERE tenant_id=? AND ((state IN ('QUEUED','RETRY','WAITING_PROJECTION') AND available_at<=now()) OR (state='RUNNING' AND lease_until<now())) ORDER BY available_at LIMIT 1 FOR UPDATE SKIP LOCKED",DomainSupport.TENANT);if(rows.isEmpty())return null;var j=rows.getFirst();long fence=((Number)j.get("fence")).longValue()+1;db.update("UPDATE collection_job SET state='RUNNING',fence=?,lease_until=now()+interval '180 seconds',attempts=attempts+1,updated_at=now() WHERE id=?",fence,j.get("id"));j.put("fence",fence);return j;});}
    private boolean lockedOwner(Map<String,Object> j){return !db.queryForList("SELECT id FROM collection_job WHERE tenant_id=? AND id=? AND fence=? AND state='RUNNING' FOR UPDATE",DomainSupport.TENANT,j.get("id"),j.get("fence")).isEmpty();}
    private boolean owned(Map<String,Object> j){return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM collection_job WHERE tenant_id=? AND id=? AND fence=? AND state='RUNNING')",Boolean.class,DomainSupport.TENANT,j.get("id"),j.get("fence")));}
    private void release(Map<String,Object> j,String state,String error,int delay){db.update("UPDATE collection_job SET state=?,error_code=?,lease_until=NULL,available_at=now()+(?*interval '1 second'),updated_at=now() WHERE tenant_id=? AND id=? AND fence=? AND state='RUNNING'",state,error,delay,DomainSupport.TENANT,j.get("id"),j.get("fence"));}
    private void finish(Map<String,Object> j,String state,String error){tx.executeWithoutResult(status->{int n=db.update("UPDATE collection_job SET state=?,error_code=?,lease_until=NULL,finished_at=now(),updated_at=now() WHERE tenant_id=? AND id=? AND fence=? AND state='RUNNING'",state,error,DomainSupport.TENANT,j.get("id"),j.get("fence"));if(n==1)audit.recordAs("collection-worker","COLLECTION_JOB_"+state,"collection_job",j.get("id"),Map.of("pages",j.get("pages_done"),"records",j.get("record_count")));});}
    private void fail(Map<String,Object> j,String error,boolean authentication){
        if(authentication){release(j,"WAITING_AUTH",error,0);return;}
        if("CIRCUIT_OPEN".equals(error)){release(j,"WAITING_SOURCE",error,30);return;}
        int failures=((Number)j.get("failures")).intValue()+1;
        db.update("UPDATE collection_job SET failures=? WHERE id=? AND fence=? AND state='RUNNING'",failures,j.get("id"),j.get("fence"));
        if(failures>=3)finish(j,"FAILED",error);else release(j,"RETRY",error,Math.min(60,5*(1<<failures)));
    }
    private void execute(Map<String,Object> j){
        UUID source=(UUID)j.get("source_id"),target=(UUID)j.get("dataset_id"),id=(UUID)j.get("id");
        if(Boolean.TRUE.equals(j.get("query_finished"))){checkProjection(j);return;}
        var s=catalog.find("source_instance",source);var d=dataset(source,target);
        if(!s.get("state").equals("ACTIVE")||!d.get("state").equals("ACTIVE")){release(j,"WAITING_SOURCE","SOURCE_OR_DATASET_PAUSED",0);return;}
        var p=configuration.runtimeProfile(source);
        if(!CollectionConfiguration.fingerprint(p).equals(j.get("config_fingerprint"))||!Objects.equals(j.get("schema_version"),d.get("active_schema_version"))||!Objects.equals(j.get("mapping_version"),d.get("current_mapping"))){finish(j,"FAILED","CONFIGURATION_CHANGED");return;}
        j.put("profile_revision",p.get("revision"));db.update("UPDATE collection_job SET profile_revision=? WHERE id=? AND fence=? AND state='RUNNING'",p.get("revision"),id,j.get("fence"));
        int page=((Number)j.get("next_page")).intValue();
        ObjectNode query=(ObjectNode)DomainSupport.parse(j.get("query_json").toString());query.put(p.get("page_field").toString(),page);
        Map<String,Object> row=tx.execute(status->{if(!lockedOwner(j))return null;db.update("INSERT INTO collection_job_page(job_id,page_no,query_json) VALUES (?,?,?::jsonb) ON CONFLICT DO NOTHING",id,page,DomainSupport.json(query));return db.queryForMap("SELECT * FROM collection_job_page WHERE job_id=? AND page_no=?",id,page);});if(row==null)return;
        if(row.get("probe_id")==null){
            var saved=db.queryForList("SELECT id FROM collection_probe WHERE tenant_id=? AND job_id=? AND page_no=? AND state='PREVIEW_STORED' ORDER BY started_at DESC LIMIT 1",DomainSupport.TENANT,id,page);
            if(!saved.isEmpty()&&owned(j)){row.put("probe_id",saved.getFirst().get("id"));db.update("UPDATE collection_job_page SET probe_id=?,state='CAPTURED',updated_at=now() WHERE job_id=? AND page_no=?",row.get("probe_id"),id,page);}
        }
        Map<String,Object> captured=null;
        if(row.get("probe_id")!=null){captured=db.queryForMap("SELECT * FROM collection_probe WHERE tenant_id=? AND id=?",DomainSupport.TENANT,row.get("probe_id"));if(!captured.get("state").equals("PREVIEW_STORED")){db.update("UPDATE collection_probe SET state='FAILED',error_code='WORKER_RESTARTED',finished_at=now() WHERE id=? AND state='RUNNING'",row.get("probe_id"));captured=null;}}
        if(captured==null){
            if(!owned(j))return;
            captured=configuration.probeForJob(source,((Number)p.get("revision")).intValue(),query,id,page);
            UUID probe=captured.get("id") instanceof UUID value?value:UUID.fromString(captured.get("id").toString());
            if(!owned(j))return;
            db.update("UPDATE collection_job_page SET probe_id=?,attempts=attempts+1,state=?,error_code=?,updated_at=now() WHERE job_id=? AND page_no=? AND EXISTS(SELECT 1 FROM collection_job WHERE id=? AND fence=? AND state='RUNNING')",probe,captured.get("state").equals("PREVIEW_STORED")?"CAPTURED":"FAILED",captured.get("error_code"),id,page,id,j.get("fence"));
            if(!captured.get("state").equals("PREVIEW_STORED")){String error=Objects.toString(captured.get("error_code"),"SOURCE_QUERY_FAILED");fail(j,error,error.equals("AUTHORIZATION_REQUIRED"));return;}
            captured=db.queryForMap("SELECT * FROM collection_probe WHERE tenant_id=? AND id=?",DomainSupport.TENANT,probe);
        }
        final Map<String,Object> capturedPage=captured;
        try{tx.executeWithoutResult(status->{
            if(!lockedOwner(j))return;
            int count=((Number)capturedPage.get("record_count")).intValue();UUID batch=null;
            if(count>0){var receipt=(Map<?,?>)importer.importRun((UUID)capturedPage.get("id"),target,((Number)j.get("schema_version")).intValue(),(Integer)j.get("mapping_version"));batch=UUID.fromString(receipt.get("batch_id").toString());}
            int pages=((Number)j.get("pages_done")).intValue()+1,records=((Number)j.get("record_count")).intValue()+count,size=query.path(p.get("size_field").toString()).asInt();
            boolean more=Boolean.TRUE.equals(capturedPage.get("has_more")),limit=more&&(pages>=((Number)j.get("max_pages")).intValue()||records+size>((Number)j.get("max_records")).intValue());
            db.update("UPDATE collection_job_page SET state='IMPORTED',batch_id=?,record_count=?,has_more=?,error_code=NULL,updated_at=now() WHERE job_id=? AND page_no=?",batch,count,more,id,page);
            db.update("UPDATE collection_job SET pages_done=?,record_count=?,next_page=?,query_finished=?,limit_hit=?,failures=0,state=?,error_code=NULL,lease_until=NULL,available_at=now(),updated_at=now() WHERE id=? AND fence=? AND state='RUNNING'",pages,records,page+1,!more||limit,limit,!more||limit?"WAITING_PROJECTION":"QUEUED",id,j.get("fence"));
        });}catch(org.springframework.web.server.ResponseStatusException ex){finish(j,"FAILED","DATASET_IMPORT_REJECTED");}
    }
    private void checkProjection(Map<String,Object> j){
        var states=db.queryForList("SELECT b.status FROM collection_job_page p JOIN ingest_batch b ON b.id=p.batch_id WHERE p.job_id=?",j.get("id"));
        if(states.stream().anyMatch(b->b.get("status").equals("FAILED"))){finish(j,"FAILED","PROJECTION_FAILED");return;}
        if(states.stream().anyMatch(b->!Set.of("COMPLETED","COMPLETED_WITH_ERRORS").contains(b.get("status")))){release(j,"WAITING_PROJECTION",null,2);return;}
        String result=states.stream().anyMatch(b->b.get("status").equals("COMPLETED_WITH_ERRORS"))?"SUCCEEDED_WITH_ERRORS":Boolean.TRUE.equals(j.get("limit_hit"))?"LIMIT_REACHED":"SUCCEEDED";finish(j,result,null);
    }
}
