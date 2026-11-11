package cn.emigo.mossdoom;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiErrors {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ApiErrors.class);
    private ResponseEntity<?> response(int status,String code,String message){return ResponseEntity.status(status).body(Map.of("code",code,"message",message,"requestId",RequestContext.requestId().toString()));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> domain(ResponseStatusException e){return response(e.getStatusCode().value(),"REQUEST_REJECTED",e.getReason()==null?"请求未完成":e.getReason());}
    @ExceptionHandler(DuplicateKeyException.class) ResponseEntity<?> duplicate(){return response(409,"DUPLICATE_RECORD","编号或名称已存在，请刷新后重试");}
    @ExceptionHandler(AccessDeniedException.class) ResponseEntity<?> denied(){return response(403,"ACCESS_DENIED","当前账号没有此操作权限");}
    @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<?> invalidJson(){return response(400,"INVALID_JSON","JSON格式、嵌套层级或请求大小不符合要求");}
    @ExceptionHandler(DataAccessException.class) ResponseEntity<?> database(){return response(503,"STORAGE_UNAVAILABLE","数据存储暂不可用，请稍后重试");}
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class) ResponseEntity<?> invalidParameter(){return response(400,"INVALID_PARAMETER","请求参数格式不正确");}
    @ExceptionHandler(ObjectStore.StoreUnavailable.class) ResponseEntity<?> objectStore(){return response(503,"RAW_STORAGE_UNAVAILABLE","原料存储暂不可用，请稍后重试");}
    @ExceptionHandler(Exception.class) ResponseEntity<?> other(Exception e){LOG.warn("request_failed id={} type={}",RequestContext.requestId(),e.getClass().getSimpleName());return response(500,"INTERNAL_ERROR","操作未完成，请凭请求编号查询处理记录");}
}
