package cn.emigo.mossdoom;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class GatewayLoginTest {
    @TempDir Path directory;
    @Test void humanChallengeUsesDocumentedPasswordEncodingAndNeverReturnsToken()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger successfulRequests=new AtomicInteger();
        server.createContext("/auth-service/auth/captcha",exchange->{byte[] bytes="{\"code\":200,\"data\":{\"uniqueCode\":\"fixture-id\",\"imageBase64\":\"AA==\"}}".getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        server.createContext("/auth-service/auth/login",exchange->{var body=DomainSupport.parse(new String(exchange.getRequestBody().readAllBytes()));boolean valid=body.path("username").asText().equals("fixture-user")&&body.path("password").asText().equals(SourceAuthentication.md5("fixture-password"))&&body.path("uniqueCode").asText().equals("fixture-id")&&body.path("captcha").asText().equals("human-fixture-answer");if(valid)successfulRequests.incrementAndGet();byte[] bytes=(valid?"{\"code\":200,\"data\":{\"accessToken\":\"fixture-session-token\",\"expire\":2}}":"{\"code\":600}").getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        server.start();try{
            String origin="http://127.0.0.1:"+server.getAddress().getPort();UUID source=UUID.randomUUID();Path file=directory.resolve("key");Files.write(file,new byte[32]);CredentialVault vault=new CredentialVault(file.toString());
            JdbcTemplate db=mock(JdbcTemplate.class);CollectionConfiguration config=mock(CollectionConfiguration.class);AuditService audit=mock(AuditService.class);
            Map<String,Object> profile=new HashMap<>(Map.of("source_id",source,"base_url",origin,"endpoint_id","gateway.factory-stock","environment","test","mode","PULL","connector_type","HTTP","revision",4,"credential_kind","LOGIN_ACCOUNT"));
            profile.put("credential_cipher",vault.encrypt(source.toString(),"{\"username\":\"fixture-user\",\"password\":\"fixture-password\",\"passwordEncoding\":\"MD5_LOWER\"}"));
            when(db.queryForList(anyString(),eq(DomainSupport.TENANT),eq(source))).thenReturn(List.of(profile));
            when(config.targets()).thenReturn(List.of(Map.of("origin",origin,"endpoint_ids",List.of("gateway.factory-stock"))));
            when(db.update(anyString(),any(Object[].class))).thenReturn(1);when(config.get(source)).thenReturn(Map.of("credential_kind","GATEWAY_SESSION","credential_configured",true));
            GatewayLogin login=new GatewayLogin(db,vault,audit,config);var challenge=(Map<?,?>)login.captcha(source);
            Object result=login.authorize(source,DomainSupport.parse("{\"challenge_id\":\""+challenge.get("challenge_id")+"\",\"captcha\":\"human-fixture-answer\"}"));
            assertThat(successfulRequests.get()).isEqualTo(1);assertThat(DomainSupport.json(result)).doesNotContain("fixture-session-token","fixture-password");
            assertThatThrownBy(()->login.authorize(source,DomainSupport.parse("{\"challenge_id\":\""+challenge.get("challenge_id")+"\",\"captcha\":\"human-fixture-answer\"}"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(successfulRequests.get()).isEqualTo(1);
        }finally{server.stop(0);}
    }
}
