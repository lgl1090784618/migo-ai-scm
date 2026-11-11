package cn.emigo.mossdoom;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class AuditService {
    private final JdbcTemplate db;
    AuditService(JdbcTemplate db){this.db=db;}
    void record(String action,String type,Object resource,Map<String,?> safeSummary){recordAs(RequestContext.actor(),action,type,resource,safeSummary);}
    void recordAs(String actor,String action,String type,Object resource,Map<String,?> summary){db.update("INSERT INTO audit_event(id,tenant_id,actor,action,resource_type,resource_id,detail,request_id) VALUES (?,?,?,?,?,?,?::jsonb,?)",UUID.randomUUID(),DomainSupport.TENANT,actor,action,type,String.valueOf(resource),DomainSupport.json(summary),RequestContext.requestId());}
}
