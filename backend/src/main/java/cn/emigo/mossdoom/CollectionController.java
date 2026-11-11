package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/collection") @PreAuthorize("hasRole('ADMIN')")
class CollectionController {
    private final CollectionConfiguration service;private final CollectionImport importer;private final GatewayLogin login;private final CollectionJobs jobs;
    CollectionController(CollectionConfiguration service,CollectionImport importer,GatewayLogin login,CollectionJobs jobs){this.service=service;this.importer=importer;this.login=login;this.jobs=jobs;}
    @GetMapping("/endpoints") Object endpoints(){return service.endpoints();}
    @GetMapping("/targets") Object targets(){return service.targets();}
    @GetMapping("/sources/{id}") Object get(@PathVariable UUID id){return service.get(id);}
    @PostMapping("/sources/{id}") Object save(@PathVariable UUID id,@RequestBody JsonNode body){return service.save(id,body);}
    @PostMapping("/sources/{id}/credential") Object credential(@PathVariable UUID id,@RequestBody JsonNode body){return service.credential(id,body);}
    @PostMapping("/sources/{id}/credential/clear") Object clear(@PathVariable UUID id){return service.clearCredential(id);}
    @PostMapping("/sources/{id}/probe") Object probe(@PathVariable UUID id){return service.probe(id);}
    @GetMapping("/sources/{id}/runs") Object runs(@PathVariable UUID id){return service.runs(id);}
    @GetMapping("/probes/{id}/raw") org.springframework.http.ResponseEntity<byte[]> raw(@PathVariable UUID id){return org.springframework.http.ResponseEntity.ok().header("Content-Type","application/json").header("Content-Disposition","attachment; filename=source-"+id+".json").body(importer.raw(id));}
    @GetMapping("/probes/{id}") Object detail(@PathVariable UUID id){return DomainSupport.row(importer.run(id));}
    @PostMapping("/probes/{id}/import") Object importRun(@PathVariable UUID id,@RequestBody JsonNode body){return importer.importRun(id,DomainSupport.id(DomainSupport.required(body,"dataset_id",36)));}
    @PostMapping("/sources/{id}/captcha") Object captcha(@PathVariable UUID id)throws Exception{return login.captcha(id);}
    @PostMapping("/sources/{id}/login") Object login(@PathVariable UUID id,@RequestBody JsonNode body)throws Exception{return login.authorize(id,body);}
    @PostMapping("/sources/{id}/session/retain") Object retain(@PathVariable UUID id,@RequestBody JsonNode body)throws Exception{return login.retainSession(id,body);}
    @GetMapping("/sources/{id}/plan") Object plan(@PathVariable UUID id){return jobs.plan(id);}
    @PostMapping("/sources/{id}/plan") Object plan(@PathVariable UUID id,@RequestBody JsonNode body){return jobs.savePlan(id,body);}
    @GetMapping("/sources/{id}/jobs") Object jobs(@PathVariable UUID id,@RequestParam(defaultValue="") String cursor,@RequestParam(defaultValue="20") int limit){return jobs.list(id,cursor,limit);}
    @PostMapping("/sources/{id}/jobs") Object createJob(@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key){return jobs.create(id,body,key);}
    @GetMapping("/jobs/{id}") Object job(@PathVariable UUID id){return jobs.detail(id);}
    @PostMapping("/jobs/{id}/retry") Object retry(@PathVariable UUID id){return jobs.retry(id);}
    @PostMapping("/jobs/{id}/cancel") Object cancel(@PathVariable UUID id){return jobs.cancel(id);}
}
