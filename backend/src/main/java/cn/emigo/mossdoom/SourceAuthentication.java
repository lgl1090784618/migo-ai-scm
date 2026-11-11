package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;

final class SourceAuthentication {
    record RequestAuth(String query,String bearer){}
    static String md5(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException();}}
    static String wmsSignature(String appId,String nonce,String secret){return md5(md5(appId+nonce+secret));}
    static String xwySignature(String server,String account,String secret,String timestamp){return md5(server+"."+account+"."+secret+"."+timestamp);}
    static RequestAuth authorize(HttpClient http,String origin,String kind,JsonNode credential)throws Exception{
        if(kind.equals("BEARER")||kind.equals("GATEWAY_SESSION")){
            if(kind.equals("GATEWAY_SESSION")&&!java.time.OffsetDateTime.parse(credential.path("expiresAt").asText()).isAfter(java.time.OffsetDateTime.now()))throw DomainSupport.conflict("登录Token已过期，请重新完成验证码登录");
            String token=DomainSupport.required(credential,"token",8192);return new RequestAuth("",token.startsWith("Bearer ")?token:"Bearer "+token);
        }
        if(kind.equals("XWY_SIGNING")){
            String host=DomainSupport.required(credential,"serverName",240);if(!URI.create(origin).getHost().equals(host))throw DomainSupport.invalid("签名serverName必须匹配已确认的测试主机");
            String account=DomainSupport.required(credential,"account",240),timestamp=Long.toString(System.currentTimeMillis()),signature=xwySignature(host,account,DomainSupport.required(credential,"secret",8192),timestamp);
            return new RequestAuth("?account="+URLEncoder.encode(account,StandardCharsets.UTF_8)+"&timestamp="+timestamp+"&sign="+signature,"");
        }
        if(!kind.equals("WMS_APP"))throw DomainSupport.conflict("该认证资料尚不能执行自动授权");
        String nonce=ZonedDateTime.now(ZoneId.of(DomainSupport.required(credential,"timeZone",80))).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int minutes;try{minutes=Integer.parseInt(DomainSupport.required(credential,"tokenMinutes",2));}catch(Exception ex){throw DomainSupport.invalid("授权有效期需为整数分钟");}if(minutes<1||minutes>60)throw DomainSupport.invalid("授权有效期需为1至60分钟");
        String appId=DomainSupport.required(credential,"appId",240),secret=DomainSupport.required(credential,"appSecret",8192);
        var payload=Map.of("appId",appId,"timeNonce",nonce,"sign",wmsSignature(appId,nonce,secret),"expiredTime",minutes);
        HttpRequest request=HttpRequest.newBuilder(URI.create(origin+"/api/user/getauthtoken")).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(DomainSupport.json(payload))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;try(var stream=response.body()){bytes=stream.readNBytes(65537);}if(bytes.length>65536||response.statusCode()!=200)throw DomainSupport.conflict("应用授权失败，请检查配置、签名或来源限制");
        JsonNode body=DomainSupport.parse(new String(bytes,StandardCharsets.UTF_8));String token=body.path("result").asText("");
        if(body.path("code").asInt(-1)!=200||!token.startsWith("Bearer "))throw DomainSupport.conflict("应用授权未通过，请检查应用标识、签名密钥、时区及权限");
        return new RequestAuth("",token);
    }
}
