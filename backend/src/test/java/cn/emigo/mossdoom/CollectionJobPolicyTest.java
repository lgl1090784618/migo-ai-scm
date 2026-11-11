package cn.emigo.mossdoom;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class CollectionJobPolicyTest {
    @Test void rollingWindowIsFrozenInConfiguredTimezoneWithoutChangingTemplate(){
        Map<String,Object> profile=Map.of("endpoint_id","xinwuyun.produce-orders","size_field","page_size","request_json","{\"page\":1,\"page_size\":10,\"date_type\":1,\"modified_start\":\"\",\"modified_end\":\"\"}");
        var options=new CollectionJobs.Options(2,20,3600,"Asia/Shanghai");
        var query=CollectionJobs.freezeQuery(profile,options,Instant.parse("2026-11-11T02:00:00Z"));
        assertThat(query.path("modified_start").asText()).isEqualTo("2026-11-11 09:00:00");
        assertThat(query.path("modified_end").asText()).isEqualTo("2026-11-11 10:00:00");
        assertThat(profile.get("request_json").toString()).contains("\"modified_start\":\"\"");
        var input=DomainSupport.parse("{\"max_pages\":2,\"max_records\":5,\"window_seconds\":3600,\"query_timezone\":\"Asia/Shanghai\"}");
        assertThatThrownBy(()->CollectionJobs.options(input,profile)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void pageTerminationUsesDocumentedSignalsAndNeverChangesPageSize(){
        var query=DomainSupport.parse("{\"pageNum\":3,\"pageSize\":5}");
        var profile=new HashMap<String,Object>(Map.of("page_field","pageNum","size_field","pageSize","total_pointer","/data/total"));
        assertThat(CollectionConfiguration.hasMore(profile,DomainSupport.parse("{\"data\":{\"total\":15}}"),5,query)).isFalse();
        assertThat(CollectionConfiguration.hasMore(profile,DomainSupport.parse("{\"data\":{\"total\":16}}"),5,query)).isTrue();
        profile.remove("total_pointer");
        assertThat(CollectionConfiguration.hasMore(profile,DomainSupport.parse("{}"),5,query)).isTrue();
        assertThat(CollectionConfiguration.hasMore(profile,DomainSupport.parse("{}"),0,query)).isFalse();
        assertThat(query.path("pageSize").asInt()).isEqualTo(5);
    }
    @Test void oversizedResponsesAreCancelledBeforeTheWholeBodyIsBuffered(){
        var body=new LimitedResponseBody(5);Flow.Subscription subscription=mock(Flow.Subscription.class);
        body.onSubscribe(subscription);body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2,3})));body.onNext(List.of(ByteBuffer.wrap(new byte[]{4,5,6})));
        verify(subscription).cancel();assertThatThrownBy(()->body.getBody().toCompletableFuture().join()).hasCauseInstanceOf(LimitedResponseBody.TooLarge.class);
    }
}
