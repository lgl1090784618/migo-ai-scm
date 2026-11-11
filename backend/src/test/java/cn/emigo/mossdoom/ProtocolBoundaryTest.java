package cn.emigo.mossdoom;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProtocolBoundaryTest {
    private final SchemaRules rules=new SchemaRules();
    @Test void canonicalOrderDoesNotChangeReceiptFingerprintAndMoneyKeepsPrecision(){
        var a=DomainSupport.parse("{\"z\":1,\"nested\":{\"b\":2,\"a\":3},\"amount\":9007199254740993.123456789012}");
        var b=DomainSupport.parse("{\"amount\":9007199254740993.123456789012,\"nested\":{\"a\":3,\"b\":2},\"z\":1}");
        assertThat(DomainSupport.sha(DomainSupport.canonical(a))).isEqualTo(DomainSupport.sha(DomainSupport.canonical(b)));
        assertThat(a.path("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("9007199254740993.123456789012"));
    }
    @Test void schemaCannotPerformRemoteResolutionButLegitimatePropertyNamesWork(){
        assertThatThrownBy(()->rules.checkSchema(DomainSupport.parse("{\"type\":\"object\",\"properties\":{\"value\":{\"$ref\":\"http://169.254.169.254/latest/meta-data\"}}}"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->rules.checkSchema(DomainSupport.parse("{\"type\":\"object\",\"$schema\":\"http://169.254.169.254\"}"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        rules.checkSchema(DomainSupport.parse("{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"}}}"));
    }
    @Test void schemaErrorsArePathsAndKeywordsWithoutLeakingFieldValues(){
        var schema=DomainSupport.parse("{\"type\":\"object\",\"properties\":{\"quantity\":{\"type\":\"integer\",\"minimum\":0}},\"required\":[\"quantity\"]}");
        var errors=rules.validate(schema,DomainSupport.parse("{\"quantity\":\"sensitive-source-value\"}"));
        assertThat(errors).isNotEmpty();assertThat(DomainSupport.json(errors)).doesNotContain("sensitive-source-value");
    }
    @Test void decimalProjectionIsExactAndFractionalCountsCannotBeSilentlyTruncated(){
        var mapping=DomainSupport.parse("{\"fields\":{\"amount\":\"/price\"},\"types\":{\"amount\":\"decimal\"},\"includeUnmapped\":false}");rules.checkMapping(mapping);
        var result=rules.project(DomainSupport.parse("{\"price\":\"9007199254740993.123456789012\",\"other\":\"ignored\"}"),mapping);
        assertThat(result.path("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("9007199254740993.123456789012"));assertThat(result.has("other")).isFalse();
        assertThatThrownBy(()->rules.project(DomainSupport.parse("{\"qty\":\"1.5\"}"),DomainSupport.parse("{\"fields\":{\"quantity\":\"/qty\"},\"types\":{\"quantity\":\"integer\"}}"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void sourceIdentityAndPatchOperationsCannotBypassEnvelopeValidation(){
        UUID source=UUID.randomUUID();var event=DomainSupport.parse("{\"id\":\"e-1\",\"specversion\":\"1.0\",\"source\":\"urn:mossdoom:source:"+source+"\",\"type\":\"test.event\",\"time\":\"2026-11-10T00:00:00Z\",\"operation\":\"upsert\",\"entity_key\":{\"id\":\"0001\"},\"data\":{}} ");
        IngestionService.checkEnvelope(source,event);
        assertThatThrownBy(()->IngestionService.checkEnvelope(UUID.randomUUID(),event)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode)event).put("operation","patch");
        assertThatThrownBy(()->IngestionService.checkEnvelope(source,event)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
