package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Only reviewed query endpoints are executable. Credentials are never returned. */
@Service
class CollectionConfiguration {
    private final JdbcTemplate db;private final CatalogService catalog;private final CredentialVault vault;
    private final AuditService audit;private final ObjectStore store;private final TransactionTemplate tx;
    private final CircuitBreaker breaker;private final ResponseCache cache;
    private final String targetsFile;private final Semaphore slots=new Semaphore(2);
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    CollectionConfiguration(JdbcTemplate db,CatalogService catalog,CredentialVault vault,AuditService audit,ObjectStore store,CircuitBreaker breaker,ResponseCache cache,@Qualifier("ingestionTx") TransactionTemplate tx,@Value("${MOSS_CONNECTOR_TARGETS_FILE:/run/secrets/connector_targets}") String file){this.db=db;this.catalog=catalog;this.vault=vault;this.audit=audit;this.store=store;this.breaker=breaker;this.cache=cache;this.tx=tx;targetsFile=file;}
    /** Source-health failures that should open the breaker. Auth failures are config problems, not source outages. */
    static boolean sourceHealthError(String error){return error!=null&&Set.of("UPSTREAM_HTTP_ERROR","SOURCE_TIMEOUT","RESPONSE_CONTRACT_MISMATCH","SOURCE_PAGE_LIMIT_IGNORED","RESPONSE_TOO_LARGE","QUERY_FAILED","REDIRECT_BLOCKED").contains(error);}
    List<Map<String,Object>> endpoints(){return db.queryForList("SELECT * FROM collection_endpoint ORDER BY id").stream().map(DomainSupport::row).toList();}
    List<Map<String,Object>> targets(){try{JsonNode json=DomainSupport.parse(Files.readString(Path.of(targetsFile)));List<Map<String,Object>> result=new ArrayList<>();for(JsonNode item:json.path("targets"))result.add(Map.of("origin",item.path("origin").asText(),"name",item.path("name").asText(),"endpoint_ids",DomainSupport.JSON.convertValue(item.path("endpoint_ids"),List.class)));return result;}catch(Exception ex){throw DomainSupport.conflict("测试目标允许列表未配置");}}
    static String origin(String text){try{URI u=URI.create(text);if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath().isEmpty()||u.getPath().equals("/")))throw new IllegalArgumentException();return u.getScheme()+"://"+u.getRawAuthority();}catch(Exception ex){throw DomainSupport.invalid("目标需为不带路径、查询参数或凭据的HTTP/HTTPS来源地址");}}
    private void checkTarget(String url,String endpoint){String target=origin(url);if(targets().stream().noneMatch(t->t.get("origin").equals(target)&&((List<?>)t.get("endpoint_ids")).contains(endpoint)))throw DomainSupport.invalid("目标未加入本次测试环境允许列表");}
    Object get(UUID source){catalog.find("source_instance",source);var rows=db.queryForList("SELECT source_id,endpoint_id,base_url,request_json,credential_kind,(credential_cipher IS NOT NULL) credential_configured,credential_updated_at,token_expires_at,revision,updated_at,CASE WHEN credential_cipher IS NULL OR credential_kind='LOGIN_ACCOUNT' THEN 'AUTH_REQUIRED' WHEN token_expires_at<=now() THEN 'EXPIRED' WHEN token_expires_at<=now()+interval '24 hours' THEN 'EXPIRING' WHEN credential_kind='BEARER' AND token_expires_at IS NULL THEN 'UNKNOWN_EXPIRY' ELSE 'READY' END auth_state FROM collection_profile WHERE tenant_id=? AND source_id=?",DomainSupport.TENANT,source);return rows.isEmpty()?Map.of("configured",false):DomainSupport.row(rows.getFirst());}
    static void validateRequest(JsonNode request){
        if(!request.isObject()||DomainSupport.canonical(request).length>16384)throw DomainSupport.invalid("查询参数需为不超过16KiB的对象");
        request.fieldNames().forEachRemaining(k->{if(!Set.of("conditions","pageIndex","pageSize","isExcel","isSync","orderBy").contains(k))throw DomainSupport.invalid("未允许的WMS查询参数："+k);});
        if(!request.path("conditions").isArray()||request.path("conditions").size()>20)throw DomainSupport.invalid("conditions需为数组，最多20个筛选条件");
        if(!request.path("pageIndex").isIntegralNumber()||request.path("pageIndex").asInt()<1||!request.path("pageSize").isIntegralNumber()||request.path("pageSize").asInt()<1||request.path("pageSize").asInt()>100)throw DomainSupport.invalid("页码从1开始，单页1至100条");
        if(!request.path("isExcel").isBoolean()||request.path("isExcel").asBoolean()||!request.path("isSync").isBoolean()||request.path("isSync").asBoolean())throw DomainSupport.invalid("只允许JSON查询，isExcel/isSync必须为false");
    }
    static void validateRequest(String endpoint,JsonNode request){
        if(endpoint.equals("wms.sku-stock")){validateRequest(request);return;}
        if(Set.of("gateway.factory-stock","gateway.fba-stock").contains(endpoint)){
            if(!request.isObject()||DomainSupport.canonical(request).length>16384)throw DomainSupport.invalid("微服务查询参数需为不超过16KiB的对象");
            Set<String> allowed=endpoint.equals("gateway.factory-stock")?Set.of("pageNum","pageSize","brandIds","companyIds","factoryIds","localSpuCodeList","skuKeys","teamIds","timeBegin","timeEnd","timeSearchType"):Set.of("pageNum","pageSize","brandIds","categoryThirdIds","functionaryIds","keyword","platformSkuStatusCodes","selectType","shopIds");
            request.fields().forEachRemaining(e->{if(!allowed.contains(e.getKey()))throw DomainSupport.invalid("未核实的微服务查询参数："+e.getKey());if(e.getValue().isArray()&&e.getValue().size()>100)throw DomainSupport.invalid("单个筛选数组不能超过100项");});
            if(!request.path("pageNum").isIntegralNumber()||request.path("pageNum").asInt()<1||!request.path("pageSize").isIntegralNumber()||request.path("pageSize").asInt()<1||request.path("pageSize").asInt()>100)throw DomainSupport.invalid("微服务页码从1开始，单页1至100条");return;
        }
        if(!endpoint.equals("xinwuyun.produce-orders")||!request.isObject()||DomainSupport.canonical(request).length>16384)throw DomainSupport.invalid("查询参数不符合已核实接口");
        request.fieldNames().forEachRemaining(k->{if(!Set.of("modified_start","modified_end","date_type","page","page_size","produce_status").contains(k))throw DomainSupport.invalid("未允许的新物云查询参数："+k);});
        if(!request.path("page").isIntegralNumber()||request.path("page").asInt()<1||!request.path("page_size").isIntegralNumber()||request.path("page_size").asInt()<10||request.path("page_size").asInt()>100||!Set.of(1,2).contains(request.path("date_type").asInt(-1)))throw DomainSupport.invalid("新物云页码从1开始，单页10至100条，date_type为1或2");
        try{var format=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");var start=java.time.LocalDateTime.parse(request.path("modified_start").asText(),format);var end=java.time.LocalDateTime.parse(request.path("modified_end").asText(),format);var seconds=java.time.Duration.between(start,end).getSeconds();if(seconds<=0||seconds>=60L*24*3600)throw new IllegalArgumentException();}catch(Exception ex){throw DomainSupport.invalid("必须设置有效起止时间，格式yyyy-MM-dd HH:mm:ss，跨度小于60天");}
        if(request.has("produce_status")){if(!request.path("produce_status").isArray())throw DomainSupport.invalid("produce_status需为数组");for(JsonNode value:request.path("produce_status"))if(!Set.of("in_finished","un_confirm","none","in_progress","finish","stored","cancel").contains(value.asText()))throw DomainSupport.invalid("生产状态不在文档允许范围");}
    }
    static void validateAuthentication(String endpoint,String kind){
        Set<String> allowed=endpoint.equals("wms.sku-stock")?Set.of("BEARER","WMS_APP"):endpoint.equals("xinwuyun.produce-orders")?Set.of("XWY_SIGNING"):endpoint.startsWith("gateway.")?Set.of("BEARER","LOGIN_ACCOUNT","GATEWAY_SESSION"):Set.of();
        if(!allowed.contains(kind))throw DomainSupport.invalid("认证类型不适用于该平台，禁止向其他认证端点发送凭据");
    }
    Object save(UUID source,JsonNode input){
        var s=catalog.find("source_instance",source);if(!s.get("environment").equals("test")||!s.get("connector_type").equals("HTTP")||!s.get("mode").equals("PULL"))throw DomainSupport.invalid("只为test环境HTTP主动连接配置查询");
        String endpoint=DomainSupport.required(input,"endpoint_id",120),url=origin(DomainSupport.required(input,"base_url",240));checkTarget(url,endpoint);
        var platform=catalog.find("platform",(UUID)s.get("platform_id"));
        var ops=db.queryForList("SELECT id FROM collection_endpoint WHERE id=? AND review_status='APPROVED' AND platform_code=?",endpoint,platform.get("code"));if(ops.isEmpty())throw DomainSupport.invalid("该平台尚无此只读接口适配，不能配置执行");
        JsonNode request=input.path("request_json");validateRequest(endpoint,request);int revision=input.path("revision").asInt(0);
        return tx.execute(status->{var existing=db.queryForList("SELECT * FROM collection_profile WHERE tenant_id=? AND source_id=? FOR UPDATE",DomainSupport.TENANT,source);
            if(existing.isEmpty()){if(revision!=0)throw DomainSupport.conflict("配置版本已改变");db.update("INSERT INTO collection_profile(source_id,tenant_id,endpoint_id,base_url,request_json) VALUES (?,?,?,?,?::jsonb)",source,DomainSupport.TENANT,endpoint,url,DomainSupport.json(request));}
            else{var previous=existing.getFirst();if(!Objects.equals(previous.get("revision"),revision))throw DomainSupport.conflict("配置已被修改，请刷新");boolean sameOrigin=url.equals(previous.get("base_url"));db.update("UPDATE collection_profile SET endpoint_id=?,base_url=?,request_json=?::jsonb,credential_cipher=CASE WHEN ? THEN credential_cipher ELSE NULL END,revision=revision+1,updated_at=now() WHERE tenant_id=? AND source_id=?",endpoint,url,DomainSupport.json(request),sameOrigin,DomainSupport.TENANT,source);}
            audit.record("COLLECTION_CONFIG_SAVE","source",source,Map.of("endpoint",endpoint,"target",url));return get(source);});
    }
    Object credential(UUID source,JsonNode input){catalog.find("source_instance",source);String kind=DomainSupport.required(input,"kind",32);if(!Set.of("BEARER","LOGIN_ACCOUNT","XWY_SIGNING","WMS_APP").contains(kind))throw DomainSupport.invalid("凭据类型不支持");JsonNode fields=input.path("fields");if(!fields.isObject()||DomainSupport.canonical(fields).length>16384)throw DomainSupport.invalid("凭据对象无效");
        Set<String> keys=switch(kind){case "BEARER"->Set.of("token");case "LOGIN_ACCOUNT"->Set.of("username","password","passwordEncoding");case "WMS_APP"->Set.of("appId","appSecret","timeZone","tokenMinutes");default->Set.of("account","secret","serverName");};fields.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw DomainSupport.invalid("不支持的凭据字段");});for(String k:keys)DomainSupport.required(fields,k,8192);
        var profiles=db.queryForList("SELECT endpoint_id FROM collection_profile WHERE tenant_id=? AND source_id=?",DomainSupport.TENANT,source);if(profiles.isEmpty())throw DomainSupport.conflict("请先保存查询配置");validateAuthentication((String)profiles.getFirst().get("endpoint_id"),kind);String encrypted=vault.encrypt(source.toString(),DomainSupport.json(fields));int revision=input.path("revision").asInt(-1);
        return tx.execute(status->{int n=db.update("UPDATE collection_profile SET credential_kind=?,credential_cipher=?,credential_updated_at=now(),token_expires_at=NULL,revision=revision+1,updated_at=now() WHERE tenant_id=? AND source_id=? AND revision=?",kind,encrypted,DomainSupport.TENANT,source,revision);if(n!=1)throw DomainSupport.conflict("请先保存配置，或刷新配置版本");audit.record("COLLECTION_CREDENTIAL_REPLACE","source",source,Map.of("kind",kind));return get(source);});
    }
    Object clearCredential(UUID source){catalog.find("source_instance",source);return tx.execute(s->{db.update("UPDATE collection_profile SET credential_cipher=NULL,token_expires_at=NULL,revision=revision+1,updated_at=now() WHERE tenant_id=? AND source_id=?",DomainSupport.TENANT,source);audit.record("COLLECTION_CREDENTIAL_CLEAR","source",source,Map.of());return get(source);});}
    Object runs(UUID source){catalog.find("source_instance",source);return db.queryForList("SELECT id,source_id,profile_revision,state,http_status,record_count,error_code,raw_sha256,import_batch_id,started_at,finished_at,request_id FROM collection_probe WHERE tenant_id=? AND source_id=? ORDER BY started_at DESC,id DESC LIMIT 30",DomainSupport.TENANT,source).stream().map(DomainSupport::row).toList();}
    Map<String,Object> runtimeProfile(UUID source){
        var s=catalog.find("source_instance",source);
        if(!s.get("environment").equals("test")||!s.get("mode").equals("PULL")||!s.get("connector_type").equals("HTTP"))throw DomainSupport.invalid("仅允许test环境HTTP查询");
        var rows=db.queryForList("SELECT p.*,e.method,e.path,e.items_pointer,e.review_status,e.success_code,e.page_field,e.size_field,e.next_pointer,e.total_pointer FROM collection_profile p JOIN collection_endpoint e ON e.id=p.endpoint_id WHERE p.tenant_id=? AND p.source_id=?",DomainSupport.TENANT,source);
        if(rows.isEmpty())throw DomainSupport.conflict("请先保存查询配置");
        var p=rows.getFirst();checkTarget((String)p.get("base_url"),(String)p.get("endpoint_id"));
        if(!p.get("review_status").equals("APPROVED")||!p.get("method").equals("POST"))throw DomainSupport.conflict("接口未通过当前适配器的只读核实");
        validateAuthentication((String)p.get("endpoint_id"),(String)p.get("credential_kind"));
        return p;
    }
    static String fingerprint(Map<String,Object> p){
        return DomainSupport.sha(DomainSupport.canonical(DomainSupport.JSON.valueToTree(Map.of("endpoint",p.get("endpoint_id"),"origin",p.get("base_url"),"query",DomainSupport.parse(p.get("request_json").toString())))));
    }
    static boolean hasMore(Map<String,Object> profile,JsonNode body,int count,JsonNode query){
        if(count==0)return false;
        Object pointer=profile.get("next_pointer");
        if(pointer!=null&&body.at(pointer.toString()).isBoolean())return body.at(pointer.toString()).asBoolean();
        long page=query.path(profile.get("page_field").toString()).asLong(),size=query.path(profile.get("size_field").toString()).asLong();
        pointer=profile.get("total_pointer");
        if(pointer!=null&&body.at(pointer.toString()).isIntegralNumber()&&body.at(pointer.toString()).canConvertToLong())return page*size<body.at(pointer.toString()).asLong();
        return count>=size;
    }
    Object probe(UUID source){return probeForJob(source,-1,null,null,null);}
    Map<String,Object> probeForJob(UUID source,int expectedRevision,JsonNode override,UUID job,Integer page){
        var profile=runtimeProfile(source);
        if(expectedRevision>=0&&((Number)profile.get("revision")).intValue()!=expectedRevision)throw DomainSupport.conflict("采集配置已变更");
        JsonNode params=override==null?DomainSupport.parse(profile.get("request_json").toString()):override;
        validateRequest((String)profile.get("endpoint_id"),params);
        if(!slots.tryAcquire())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"已有两个查询正在执行，请稍后重试");
        UUID run=UUID.randomUUID();String phase="AUTH";
        try{
            db.update("INSERT INTO collection_probe(id,tenant_id,source_id,profile_revision,state,request_id,endpoint_id,target_origin,request_snapshot,authentication_kind,job_id,page_no) VALUES (?,?,?,?,'RUNNING',?,?,?,?::jsonb,?,?,?)",run,DomainSupport.TENANT,source,profile.get("revision"),RequestContext.requestId(),profile.get("endpoint_id"),profile.get("base_url"),DomainSupport.json(params),profile.get("credential_kind"),job,page);
            audit.record("COLLECTION_QUERY","source",source,Map.of("run",run.toString(),"endpoint",profile.get("endpoint_id")));
            if(profile.get("credential_cipher")==null||profile.get("credential_kind").equals("LOGIN_ACCOUNT")){
                finish(run,"FAILED",null,null,"AUTHORIZATION_REQUIRED",null,null);return probeReceipt(run);
            }
            if(!breaker.allow(source)){
                finish(run,"FAILED",null,null,"CIRCUIT_OPEN",null,null);return probeReceipt(run);
            }
            JsonNode credential=DomainSupport.parse(vault.decrypt(source.toString(),(String)profile.get("credential_cipher")));
            var auth=authorizeCached(source,(String)profile.get("base_url"),(String)profile.get("credential_kind"),credential);
            phase="QUERY";
            var builder=HttpRequest.newBuilder(URI.create(profile.get("base_url").toString()+profile.get("path")+auth.query())).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json").header("X-Request-Id",RequestContext.requestId().toString());
            if(!auth.bearer().isEmpty())builder.header("Authorization",auth.bearer());
            var response=http.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(DomainSupport.canonical(params))).build(),info->new LimitedResponseBody(5*1024*1024));
            int code=response.statusCode();byte[] bytes=response.body();
            if(code!=200){String error=code==401||code==403?"AUTHORIZATION_REQUIRED":code>=300&&code<400?"REDIRECT_BLOCKED":"UPSTREAM_HTTP_ERROR";recordHealth(source,error);finish(run,"FAILED",code,null,error,null,null);return probeReceipt(run);}
            JsonNode body=DomainSupport.parse(new String(bytes,StandardCharsets.UTF_8)),items=body.at((String)profile.get("items_pointer"));
            int businessCode=body.path("code").asInt(-1);
            if(businessCode!=((Number)profile.get("success_code")).intValue()||!items.isArray()){
                String error=Set.of(401,403,600).contains(businessCode)?"AUTHORIZATION_REQUIRED":"RESPONSE_CONTRACT_MISMATCH";
                recordHealth(source,error);finish(run,"FAILED",code,null,error,null,null);return probeReceipt(run);
            }
            if(items.size()>params.path(profile.get("size_field").toString()).asInt()){
                finish(run,"FAILED",code,items.size(),"SOURCE_PAGE_LIMIT_IGNORED",null,null);recordHealth(source,"SOURCE_PAGE_LIMIT_IGNORED");return probeReceipt(run);
            }
            phase="STORAGE";
            String hash=DomainSupport.sha(bytes),key="probe/test/"+source+"/"+run+".json";store.put(key,bytes);
            boolean more=hasMore(profile,body,items.size(),params);
            db.update("UPDATE collection_probe SET state='PREVIEW_STORED',http_status=?,record_count=?,raw_object_key=?,raw_sha256=?,has_more=?,finished_at=now() WHERE tenant_id=? AND id=?",code,items.size(),key,hash,more,DomainSupport.TENANT,run);
            breaker.recordSuccess(source);
            return probeReceipt(run);
        }catch(InterruptedException ex){Thread.currentThread().interrupt();finish(run,"FAILED",null,null,"INTERRUPTED",null,null);return probeReceipt(run);}
        catch(Exception ex){
            String error=phase.equals("AUTH")?"AUTHORIZATION_REQUIRED":phase.equals("STORAGE")?"RAW_STORAGE_UNAVAILABLE":ex instanceof java.net.http.HttpTimeoutException?"SOURCE_TIMEOUT":"QUERY_FAILED";
            Throwable cause=ex;while(cause!=null){if(cause instanceof LimitedResponseBody.TooLarge){error="RESPONSE_TOO_LARGE";break;}cause=cause.getCause();}
            recordHealth(source,error);finish(run,"FAILED",null,null,error,null,null);return probeReceipt(run);
        }finally{slots.release();}
    }
    /** Records source-health failures on the breaker; auth/config/storage failures never open it. */
    private void recordHealth(UUID source,String error){if(sourceHealthError(error))breaker.recordFailure(source);}
    /** WMS_APP short-lived tokens are cached so multi-page jobs do not re-issue a token per page. */
    private SourceAuthentication.RequestAuth authorizeCached(UUID source,String origin,String kind,JsonNode credential)throws Exception{
        if(!kind.equals("WMS_APP"))return SourceAuthentication.authorize(http,origin,kind,credential);
        String key="wms-token:"+source;
        String token=cache.get(key,String.class);
        if(token!=null)return new SourceAuthentication.RequestAuth("",token);
        var auth=SourceAuthentication.authorize(http,origin,kind,credential);
        int minutes=credential.path("tokenMinutes").asInt(10);
        long ttl=Math.max(30,minutes*60L-60);
        cache.put(key,auth.bearer(),ttl);
        return auth;
    }
    Map<String,Object> probeReceipt(UUID run){
        return DomainSupport.row(db.queryForMap("SELECT id,state,http_status,record_count,error_code,raw_sha256,has_more FROM collection_probe WHERE tenant_id=? AND id=?",DomainSupport.TENANT,run));
    }

    private void finish(UUID id,String state,Integer status,Integer count,String error,String key,String hash){db.update("UPDATE collection_probe SET state=?,http_status=?,record_count=?,error_code=?,raw_object_key=?,raw_sha256=?,finished_at=now() WHERE id=? AND tenant_id=?",state,status,count,error,key,hash,id,DomainSupport.TENANT);}
}
