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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Only the deployment-owned S3 endpoint is reachable through this adapter. */
@Service
class ObjectStore {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;private final String bucket,access,secret;
    ObjectStore(@Value("${app.s3-endpoint}") String endpoint,@Value("${app.s3-bucket}") String bucket,@Value("${MOSS_S3_CREDENTIAL_FILE:/run/secrets/s3_client.json}") String file){
        this.endpoint=URI.create(endpoint);this.bucket=bucket;
        try{JsonNode credentials=DomainSupport.parse(Files.readString(Path.of(file)));access=credentials.path("accessKey").asText();secret=credentials.path("secretKey").asText();if(access.isBlank()||secret.length()<16)throw new IllegalArgumentException();}
        catch(Exception e){throw new IllegalStateException("S3 credential is missing or invalid");}
    }
    void put(String key,byte[] bytes){request("PUT",key,bytes);}
    byte[] get(String key){return request("GET",key,new byte[0]);}
    private byte[] request(String method,String key,byte[] body){
        if(!key.matches("[A-Za-z0-9_./-]+")||key.contains(".."))throw new IllegalArgumentException("Invalid object key");
        for(int attempt=0;attempt<2;attempt++){
            try{
                Instant now=Instant.now();String day=DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now);String stamp=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now);
                String host=endpoint.getRawAuthority(),path="/"+bucket+"/"+key,hash=DomainSupport.sha(body),headers="host:"+host+"\nx-amz-content-sha256:"+hash+"\nx-amz-date:"+stamp+"\n";
                String signed="host;x-amz-content-sha256;x-amz-date",scope=day+"/us-east-1/s3/aws4_request";
                String canonical=method+"\n"+path+"\n\n"+headers+"\n"+signed+"\n"+hash;
                String toSign="AWS4-HMAC-SHA256\n"+stamp+"\n"+scope+"\n"+DomainSupport.sha(canonical.getBytes(StandardCharsets.UTF_8));
                byte[] signing=("AWS4"+secret).getBytes(StandardCharsets.UTF_8);for(String segment:new String[]{day,"us-east-1","s3","aws4_request"})signing=hmac(signing,segment);
                String authorization="AWS4-HMAC-SHA256 Credential="+access+"/"+scope+", SignedHeaders="+signed+", Signature="+HexFormat.of().formatHex(hmac(signing,toSign));
                HttpRequest request=HttpRequest.newBuilder(URI.create(endpoint+path)).timeout(Duration.ofSeconds(12)).header("x-amz-content-sha256",hash).header("x-amz-date",stamp).header("Authorization",authorization).method(method,method.equals("PUT")?HttpRequest.BodyPublishers.ofByteArray(body):HttpRequest.BodyPublishers.noBody()).build();
                var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());
                if(response.statusCode()>=200&&response.statusCode()<300){if(response.body().length>6*1024*1024)throw new IllegalStateException("Stored object exceeds protocol limit");return response.body();}
                if(response.statusCode()<500||attempt==1)throw new StoreUnavailable();
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new StoreUnavailable();}catch(java.io.IOException e){if(attempt==1)throw new StoreUnavailable();}
        }throw new StoreUnavailable();
    }
    private static byte[] hmac(byte[] key,String text){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    static class StoreUnavailable extends RuntimeException {StoreUnavailable(){super("Raw object storage unavailable");}}
}
