package cn.emigo.mossdoom;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectionSecurityTest {
    @TempDir Path directory;
    @Test void encryptedCredentialsAreRandomizedAndBoundToSource() throws Exception {
        byte[] key=new byte[32];new SecureRandom().nextBytes(key);Path file=directory.resolve("key");Files.write(file,key);
        CredentialVault vault=new CredentialVault(file.toString());String first=vault.encrypt("source-a","test-value");
        assertThat(first).doesNotContain("test-value").isNotEqualTo(vault.encrypt("source-a","test-value"));
        assertThat(vault.decrypt("source-a",first)).isEqualTo("test-value");
        assertThatThrownBy(()->vault.decrypt("source-b",first)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void targetOriginCannotCarryCredentialsPathsQueriesOrFragments(){
        assertThat(CollectionConfiguration.origin("https://example.test/")).isEqualTo("https://example.test");
        for(String invalid:new String[]{"https://user:pass@example.test","https://example.test/api","https://example.test/?token=x","https://example.test/#x","file:///etc/passwd"})
            assertThatThrownBy(()->CollectionConfiguration.origin(invalid)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void queryLimitsAndExportFlagsAreEnforced(){
        var good=DomainSupport.parse("{\"conditions\":[],\"pageIndex\":1,\"pageSize\":5,\"isExcel\":false,\"isSync\":false}");
        CollectionConfiguration.validateRequest(good);
        for(String replacement:new String[]{"\"pageSize\":1000","\"isExcel\":true","\"isSync\":true"}){
            String field=replacement.substring(0,replacement.indexOf(':'));
            String bad=DomainSupport.json(good).replaceAll(java.util.regex.Pattern.quote(field)+":[^,}]+",java.util.regex.Matcher.quoteReplacement(replacement));
            assertThatThrownBy(()->CollectionConfiguration.validateRequest(DomainSupport.parse(bad))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
    }
    @Test void productionQueryRequiresBoundedTimeAndDocumentedPageSize(){
        var query=DomainSupport.parse("{\"modified_start\":\"2026-11-10 10:00:00\",\"modified_end\":\"2026-11-10 11:00:00\",\"date_type\":1,\"page\":1,\"page_size\":10}");
        CollectionConfiguration.validateRequest("xinwuyun.produce-orders",query);
        var copy=query.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)copy).put("page_size",5);
        assertThatThrownBy(()->CollectionConfiguration.validateRequest("xinwuyun.produce-orders",copy)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var reverse=query.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)reverse).put("modified_end","2026-11-10 09:00:00");
        assertThatThrownBy(()->CollectionConfiguration.validateRequest("xinwuyun.produce-orders",reverse)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void platformSigningRulesRemainSeparate(){
        String wms=SourceAuthentication.wmsSignature("sample-app","20261110180000","sample-secret");
        String xwy=SourceAuthentication.xwySignature("sample.test","sample-app","sample-secret","1794304800000");
        assertThat(wms).hasSize(32).isNotEqualTo(xwy);
        assertThat(wms).isEqualTo(SourceAuthentication.md5(SourceAuthentication.md5("sample-app20261110180000sample-secret")));
        assertThat(xwy).isEqualTo(SourceAuthentication.md5("sample.test.sample-app.sample-secret.1794304800000"));
    }
    @Test void authenticationCredentialsCannotBeSentToAnotherPlatformsAuthEndpoint(){
        CollectionConfiguration.validateAuthentication("gateway.factory-stock","LOGIN_ACCOUNT");
        CollectionConfiguration.validateAuthentication("wms.sku-stock","WMS_APP");
        assertThatThrownBy(()->CollectionConfiguration.validateAuthentication("gateway.factory-stock","WMS_APP")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->CollectionConfiguration.validateAuthentication("xinwuyun.produce-orders","BEARER")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
