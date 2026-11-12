package cn.emigo.mossdoom;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** 经营总览 / 数据健康所需的聚合视图，仅读取本地采集库，不访问任何源平台。 */
@RestController @RequestMapping("/api/insight")
class InsightController {
    private final JdbcTemplate db;
    InsightController(JdbcTemplate db){this.db=db;}

    @GetMapping("/overview")
    Map<String,Object> overview(){
        String t=DomainSupport.TENANT;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("generatedAt",OffsetDateTime.now().toString());

        Map<String,Object> pipeline=new LinkedHashMap<>();
        pipeline.put("sources",Map.of("total",count("SELECT count(*) FROM source_instance WHERE tenant_id=?",t),
                "active",count("SELECT count(*) FROM source_instance WHERE tenant_id=? AND state='ACTIVE'",t)));
        pipeline.put("platforms",count("SELECT count(*) FROM platform WHERE tenant_id=?",t));
        pipeline.put("datasets",Map.of("total",count("SELECT count(*) FROM dataset WHERE tenant_id=?",t),
                "active",count("SELECT count(*) FROM dataset WHERE tenant_id=? AND state='ACTIVE'",t)));
        pipeline.put("batches",Map.of("total",count("SELECT count(*) FROM ingest_batch WHERE tenant_id=?",t),
                "last24h",count("SELECT count(*) FROM ingest_batch WHERE tenant_id=? AND received_at>now()-interval '24 hours'",t),
                "lastReceivedAt",scalar("SELECT max(received_at) FROM ingest_batch WHERE tenant_id=?",t)));
        pipeline.put("jobs",Map.of("running",count("SELECT count(*) FROM collection_job WHERE tenant_id=? AND state IN ('QUEUED','RUNNING','RETRY','WAITING_PROJECTION')",t),
                "failed7d",count("SELECT count(*) FROM collection_job WHERE tenant_id=? AND state='FAILED' AND updated_at>now()-interval '7 days'",t)));
        pipeline.put("schedules",Map.of("total",count("SELECT count(*) FROM collection_schedule WHERE tenant_id=?",t),
                "enabled",count("SELECT count(*) FROM collection_schedule WHERE tenant_id=? AND enabled",t)));
        pipeline.put("quarantined",count("SELECT count(*) FROM raw_event WHERE tenant_id=? AND status='QUARANTINED'",t));
        pipeline.put("audits24h",count("SELECT count(*) FROM audit_event WHERE tenant_id=? AND occurred_at>now()-interval '24 hours'",t));
        result.put("pipeline",pipeline);

        List<Map<String,Object>> sources=db.queryForList("""
            SELECT s.id,s.name,s.state,s.connector_type,s.environment,
                   p.code AS platform_code,p.name AS platform_name,
                   b.last_at,b.last_status,b.last_error,b.cnt24,
                   sch.enabled AS schedule_enabled,sch.interval_seconds,sch.next_run_at
            FROM source_instance s
            LEFT JOIN platform p ON p.id=s.platform_id
            LEFT JOIN LATERAL (
                SELECT max(received_at) AS last_at,
                       (array_agg(status ORDER BY received_at DESC))[1] AS last_status,
                       (array_agg(error_code ORDER BY received_at DESC))[1] AS last_error,
                       count(*) FILTER (WHERE received_at>now()-interval '24 hours') AS cnt24
                FROM ingest_batch bb WHERE bb.source_id=s.id
            ) b ON true
            LEFT JOIN collection_schedule sch ON sch.source_id=s.id
            WHERE s.tenant_id=? ORDER BY p.code,s.name
            """,t);
        List<Map<String,Object>> health=new ArrayList<>();
        for(Map<String,Object> row:sources){
            Map<String,Object> item=new LinkedHashMap<>(row);
            Object lastAt=row.get("last_at");
            item.put("hoursBehind",lastAt==null?null:Math.round(Duration.between(((java.sql.Timestamp)lastAt).toInstant(),java.time.Instant.now()).toMinutes()/6.0)/10.0);
            item.remove("last_at");
            item.put("lastBatchAt",lastAt==null?null:((java.sql.Timestamp)lastAt).toInstant().toString());
            health.add(item);
        }
        result.put("sources",health);

        result.put("recentBatches",db.queryForList("""
            SELECT b.id,b.status,b.event_count,b.applied_count,b.rejected_count,b.error_code,b.received_at,
                   s.name AS source_name,d.code AS dataset_code
            FROM ingest_batch b
            LEFT JOIN source_instance s ON s.id=b.source_id
            LEFT JOIN dataset d ON d.id=b.dataset_id
            WHERE b.tenant_id=? ORDER BY b.received_at DESC LIMIT 8
            """,t));
        result.put("recentJobs",db.queryForList("""
            SELECT j.id,j.state,j.pages_done,j.record_count,j.error_code,j.created_at,j.finished_at,
                   s.name AS source_name,d.code AS dataset_code
            FROM collection_job j
            LEFT JOIN source_instance s ON s.id=j.source_id
            LEFT JOIN dataset d ON d.id=j.dataset_id
            WHERE j.tenant_id=? ORDER BY j.created_at DESC LIMIT 8
            """,t));
        return result;
    }

    /** 近 N 天采集趋势与来源占比，用于看板图表卡片。同样只读本地采集库。 */
    @GetMapping("/trend")
    Map<String,Object> trend(@RequestParam(defaultValue="14") int days){
        String t=DomainSupport.TENANT;
        int n=Math.max(7,Math.min(60,days));
        Map<String,long[]> slots=new LinkedHashMap<>();
        java.time.LocalDate today=java.time.LocalDate.now();
        for(int i=n-1;i>=0;i--) slots.put(today.minusDays(i).toString(),new long[]{0,0});
        db.queryForList("""
            SELECT to_char(received_at,'YYYY-MM-DD') AS d, count(*) AS batches,
                   coalesce(sum(event_count),0) AS events
            FROM ingest_batch
            WHERE tenant_id=? AND received_at > now() - make_interval(days => ?)
            GROUP BY 1
            """,t,n).forEach(r->{
            long[] slot=slots.get(String.valueOf(r.get("d")));
            if(slot!=null){slot[0]=((Number)r.get("batches")).longValue();slot[1]=((Number)r.get("events")).longValue();}
        });
        List<Map<String,Object>> series=new ArrayList<>();
        slots.forEach((d,v)->series.add(Map.of("day",d,"label",d.substring(5),"batches",v[0],"events",v[1])));
        List<Map<String,Object>> bySource=db.queryForList("""
            SELECT coalesce(p.code,'other') AS code, s.name AS name,
                   coalesce(sum(b.event_count),0) AS events
            FROM source_instance s
            LEFT JOIN platform p ON p.id=s.platform_id
            LEFT JOIN ingest_batch b ON b.source_id=s.id AND b.received_at > now() - interval '30 days'
            WHERE s.tenant_id=?
            GROUP BY p.code,s.name
            HAVING coalesce(sum(b.event_count),0) > 0
            ORDER BY 3 DESC
            """,t);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("days",series);
        result.put("bySource",bySource);
        return result;
    }

    @GetMapping("/coverage")
    Map<String,Object> coverage(){
        String t=DomainSupport.TENANT;
        return Map.of("datasets",db.queryForList("""
            SELECT d.code,d.name,d.state,d.grain,p.code AS platform_code,p.name AS platform_name,
                   s.name AS source_name,s.state AS source_state,
                   (SELECT count(*) FROM ingest_batch b WHERE b.dataset_id=d.id) AS batches,
                   (SELECT coalesce(sum(b.event_count),0) FROM ingest_batch b WHERE b.dataset_id=d.id) AS events,
                   (SELECT max(received_at) FROM ingest_batch b WHERE b.dataset_id=d.id) AS last_received_at
            FROM dataset d
            LEFT JOIN source_instance s ON s.id=d.source_id
            LEFT JOIN platform p ON p.id=s.platform_id
            WHERE d.tenant_id=? ORDER BY p.code,d.code
            """,t));
    }

    private long count(String sql,Object... args){Long v=db.queryForObject(sql,Long.class,args);return v==null?0:v;}
    private Object scalar(String sql,Object... args){return db.queryForObject(sql,Object.class,args);}
}
