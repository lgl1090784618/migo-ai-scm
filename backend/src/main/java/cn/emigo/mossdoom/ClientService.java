package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ClientService {
    private final CatalogService catalog;private final JdbcTemplate db;private final AuditService audit;private final TransactionTemplate tx;
    ClientService(CatalogService c,JdbcTemplate db,AuditService a,@Qualifier("ingestionTx") TransactionTemplate tx){catalog=c;this.db=db;audit=a;this.tx=tx;}
    Map<String,Object> create(UUID dataset,JsonNode body){Map<String,Object> d=catalog.find("dataset",dataset);String name=DomainSupport.required(body,"name",120);byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token="moss_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);UUID id=UUID.randomUUID();
        return tx.execute(s->{db.update("INSERT INTO api_client(id,tenant_id,name,source_id,dataset_id,token_hash,created_by) VALUES (?,?,?,?,?,?,?)",id,DomainSupport.TENANT,name,d.get("source_id"),dataset,DomainSupport.sha(token.getBytes(StandardCharsets.UTF_8)),RequestContext.actor());audit.record("CLIENT_CREATE","api_client",id,Map.of("dataset",dataset.toString()));return Map.of("id",id.toString(),"name",name,"token",token,"source_id",d.get("source_id").toString(),"dataset_id",dataset.toString(),"notice","令牌只展示一次，请在接收端安全保存");});}
    Map<String,Object> disable(UUID id){return tx.execute(s->{catalog.find("api_client",id);db.update("UPDATE api_client SET enabled=false WHERE tenant_id=? AND id=?",DomainSupport.TENANT,id);audit.record("CLIENT_DISABLE","api_client",id,Map.of());return Map.of("ok",true);});}
}
