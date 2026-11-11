package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Normal gateway login: user supplies the CAPTCHA; password and token stay server-side. */
@Service
class GatewayLogin {
    record Challenge(UUID source,int revision,String upstreamId,Instant expires){}
    private final JdbcTemplate db;private final CredentialVault vault;private final AuditService audit;
    private final CollectionConfiguration configuration;
    private final ConcurrentHashMap<UUID,Challenge> challenges=new ConcurrentHashMap<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    GatewayLogin(JdbcTemplate db,CredentialVault vault,AuditService audit,CollectionConfiguration configuration){this.db=db;this.vault=vault;this.audit=audit;this.configuration=configuration;}
    private Map<String,Object> profile(UUID source){return profile(source,true);}
    private Map<String,Object> profile(UUID source,boolean requireAccount){
        var rows=db.queryForList("SELECT p.*,s.environment,s.mode,s.connector_type FROM collection_profile p JOIN source_instance s ON s.id=p.source_id AND s.tenant_id=p.tenant_id WHERE p.tenant_id=? AND p.source_id=?",DomainSupport.TENANT,source);
        if(rows.isEmpty())throw DomainSupport.conflict("请先保存查询配置");var p=rows.getFirst();
        if(!p.get("environment").equals("test")||!p.get("mode").equals("PULL")||!p.get("connector_type").equals("HTTP")||!p.get("endpoint_id").toString().startsWith("gateway."))throw DomainSupport.invalid("仅支持已核实的微服务测试连接登录");
        boolean allowed=configuration.targets().stream().anyMatch(t->t.get("origin").equals(p.get("base_url"))&&((java.util.List<?>)t.get("endpoint_ids")).contains(p.get("endpoint_id")));
        if(!allowed)throw DomainSupport.invalid("登录目标未在测试允许列表中");
        if(p.get("credential_cipher")==null||(requireAccount&&!java.util.Set.of("LOGIN_ACCOUNT","GATEWAY_SESSION").contains(p.get("credential_kind"))))throw DomainSupport.conflict("请先以登录账号类型保存用户名、密码和密码编码规则");return p;
    }
    Object retainSession(UUID source,JsonNode input)throws Exception{
        var p=profile(source,false);int revision=input.path("revision").asInt(-1);
        String username=DomainSupport.required(input,"username",240),password=DomainSupport.required(input,"password",8192),token=DomainSupport.required(input,"token",8192),encoding=DomainSupport.required(input,"passwordEncoding",20);
        if(!java.util.Set.of("MD5_LOWER","PLAIN").contains(encoding))throw DomainSupport.invalid("密码编码不支持");
        OffsetDateTime expires;try{expires=OffsetDateTime.parse(DomainSupport.required(input,"expiresAt",80));if(!expires.isAfter(OffsetDateTime.now())||expires.isAfter(OffsetDateTime.now().plusYears(1)))throw new IllegalArgumentException();}catch(Exception ex){throw DomainSupport.invalid("Token有效期须来自本次来源登录响应");}
        JsonNode existing=DomainSupport.parse(vault.decrypt(source.toString(),(String)p.get("credential_cipher")));
        if(!java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),existing.path("token").asText().getBytes(StandardCharsets.UTF_8)))throw DomainSupport.conflict("只能接管当前已录入的合法登录会话，请先通过正常登录取得并录入Token");
        var runtime=configuration.runtimeProfile(source);var query=DomainSupport.parse(runtime.get("request_json").toString());CollectionConfiguration.validateRequest(runtime.get("endpoint_id").toString(),query);
        var request=HttpRequest.newBuilder(URI.create(p.get("base_url").toString()+runtime.get("path"))).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json").header("Authorization",token.startsWith("Bearer ")?token:"Bearer "+token).POST(HttpRequest.BodyPublishers.ofByteArray(DomainSupport.canonical(query))).build();
        var response=http.send(request,info->new LimitedResponseBody(5*1024*1024));JsonNode result=DomainSupport.parse(new String(response.body(),StandardCharsets.UTF_8));
        if(response.statusCode()!=200||result.path("code").asInt(-1)!=((Number)runtime.get("success_code")).intValue()||!result.at(runtime.get("items_pointer").toString()).isArray())throw DomainSupport.conflict("当前会话已失效或无查询权限，不能接管");
        var account=DomainSupport.JSON.createObjectNode();account.put("username",username);account.put("password",password);account.put("passwordEncoding",encoding);account.put("token",token);account.put("expiresAt",expires.toString());
        String encrypted=vault.encrypt(source.toString(),DomainSupport.json(account));int changed=db.update("UPDATE collection_profile SET credential_kind='GATEWAY_SESSION',credential_cipher=?,token_expires_at=?,credential_updated_at=now(),revision=revision+1,updated_at=now() WHERE tenant_id=? AND source_id=? AND revision=?",encrypted,expires,DomainSupport.TENANT,source,revision);
        if(changed!=1)throw DomainSupport.conflict("配置已变更，请刷新后重新接管");audit.record("GATEWAY_SESSION_RETAINED","source",source,Map.of("expiresAt",expires.toString()));return configuration.get(source);
    }
    private JsonNode request(String origin,String path,JsonNode body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(DomainSupport.canonical(body))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;try(var stream=response.body()){bytes=stream.readNBytes(2*1024*1024+1);}if(response.statusCode()!=200||bytes.length>2*1024*1024)throw DomainSupport.conflict("认证服务返回异常，请稍后重试");return DomainSupport.parse(new String(bytes,StandardCharsets.UTF_8));
    }
    Object captcha(UUID source)throws Exception{
        var p=profile(source);challenges.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now()));if(challenges.size()>=100)throw DomainSupport.conflict("待验证请求过多，请稍后重试");
        JsonNode response=request((String)p.get("base_url"),"/auth-service/auth/captcha",DomainSupport.JSON.createObjectNode());String image=response.path("data").path("imageBase64").asText("");
        if(response.path("code").asInt(-1)!=200||!image.matches("(?:data:image/(?:png|jpeg);base64,)?[A-Za-z0-9+/=\\r\\n]+"))throw DomainSupport.conflict("无法取得有效验证码图片");
        String upstream=DomainSupport.required(response.path("data"),"uniqueCode",200);UUID id=UUID.randomUUID();challenges.put(id,new Challenge(source,((Number)p.get("revision")).intValue(),upstream,Instant.now().plusSeconds(300)));
        audit.record("GATEWAY_CAPTCHA_REQUEST","source",source,Map.of());return Map.of("challenge_id",id.toString(),"image",image.startsWith("data:")?image:"data:image/png;base64,"+image,"notice","请由用户填写图片中的验证码");
    }
    Object authorize(UUID source,JsonNode input)throws Exception{
        var p=profile(source);UUID id=DomainSupport.id(DomainSupport.required(input,"challenge_id",36));Challenge challenge=challenges.remove(id);
        if(challenge==null||!challenge.source().equals(source)||challenge.revision()!=((Number)p.get("revision")).intValue()||challenge.expires().isBefore(Instant.now()))throw DomainSupport.conflict("验证码已失效或配置改变，请重新获取");
        ObjectNode credential=(ObjectNode)DomainSupport.parse(vault.decrypt(source.toString(),(String)p.get("credential_cipher")));String encoding=credential.path("passwordEncoding").asText("MD5_LOWER"),password=DomainSupport.required(credential,"password",8192);
        if(encoding.equals("MD5_LOWER"))password=SourceAuthentication.md5(password);else if(!encoding.equals("PLAIN"))throw DomainSupport.invalid("密码编码仅支持MD5_LOWER或PLAIN");
        var body=DomainSupport.JSON.createObjectNode();body.put("username",DomainSupport.required(credential,"username",240));body.put("password",password);body.put("uniqueCode",challenge.upstreamId());body.put("captcha",DomainSupport.required(input,"captcha",40));
        JsonNode response=request((String)p.get("base_url"),"/auth-service/auth/login",body);String token=response.path("data").path("accessToken").asText("");int minutes=response.path("data").path("expire").asInt(0);
        if(response.path("code").asInt(-1)!=200||token.isBlank()||minutes<=0||minutes>525600){audit.record("GATEWAY_LOGIN_FAILED","source",source,Map.of());throw DomainSupport.conflict("登录未成功，请核对账号、密码编码和验证码后重新获取验证码");}
        OffsetDateTime expires=OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(minutes);credential.put("token",token);credential.put("expiresAt",expires.toString());
        String encrypted=vault.encrypt(source.toString(),DomainSupport.json(credential));int changed=db.update("UPDATE collection_profile SET credential_kind='GATEWAY_SESSION',credential_cipher=?,token_expires_at=?,credential_updated_at=now(),revision=revision+1,updated_at=now() WHERE tenant_id=? AND source_id=? AND revision=?",encrypted,expires,DomainSupport.TENANT,source,p.get("revision"));
        if(changed!=1)throw DomainSupport.conflict("配置已修改，Token未保存，请重新授权");audit.record("GATEWAY_LOGIN_SUCCEEDED","source",source,Map.of("expiresAt",expires.toString()));return configuration.get(source);
    }
}
