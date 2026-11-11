package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/ingestion/v1")
class IngestionController {
    private final IngestionService service;private final CatalogService catalog;
    IngestionController(IngestionService s,CatalogService c){service=s;catalog=c;}
    private ClientTokenFilter.CollectorIdentity client(HttpServletRequest request){return (ClientTokenFilter.CollectorIdentity)request.getAttribute("collector");}
    @PostMapping(value="/batches",consumes="application/json") ResponseEntity<?> receive(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody byte[] body){return ResponseEntity.accepted().body(service.receive(client(request),key,body));}
    @GetMapping("/batches/{id}") Object status(HttpServletRequest request,@PathVariable UUID id){return service.status(client(request),id);}
    @GetMapping("/datasets/{id}/schema") Object schema(HttpServletRequest request,@PathVariable UUID id){var c=client(request);if(!c.dataset().equals(id))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"数据集不存在");var d=catalog.find("dataset",id);if(d.get("active_schema_version")==null)throw DomainSupport.conflict("数据结构尚未发布");return Map.of("dataset_id",id.toString(),"source_instance_id",c.source().toString(),"schema_version",d.get("active_schema_version"),"document",catalog.versionDocument(id,"schemas",(Integer)d.get("active_schema_version")),"max_events",1000,"max_bytes",5*1024*1024);}
}
