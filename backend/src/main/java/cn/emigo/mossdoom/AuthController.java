package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/auth")
class AuthController {
    private final AuthenticationManager manager;private final HttpSessionSecurityContextRepository contexts;private final CookieCsrfTokenRepository csrf;private final AuditService audit;
    private final ConcurrentHashMap<String,Attempt> attempts=new ConcurrentHashMap<>();
    AuthController(AuthenticationManager m,HttpSessionSecurityContextRepository c,CookieCsrfTokenRepository s,AuditService a){manager=m;contexts=c;csrf=s;audit=a;}
    @GetMapping("/csrf") Map<String,Object> csrf(CsrfToken token){return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
    @GetMapping("/session") Map<String,Object> session(Authentication auth){boolean ok=auth!=null&&auth.isAuthenticated()&&auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN")||a.getAuthority().equals("ROLE_VIEWER"));return ok?Map.of("authenticated",true,"username",auth.getName(),"role",auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"))?"ADMIN":"VIEWER","tenant",DomainSupport.TENANT,"environment","test"):Map.of("authenticated",false);}
    @PostMapping("/login") Map<String,Object> login(@RequestBody JsonNode body,HttpServletRequest request,HttpServletResponse response){
        String ip=request.getRemoteAddr();long now=Instant.now().getEpochSecond();Attempt before=attempts.get(ip);
        if(before!=null&&before.until>now&&before.failures>=10)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"登录失败次数过多，请5分钟后再试");
        String username=DomainSupport.required(body,"username",64),password=DomainSupport.required(body,"password",200);
        try{
            Authentication auth=manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username,password));
            audit.recordAs(auth.getName(),"LOGIN","session","current",Map.of());
            request.getSession(true);request.changeSessionId();var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);csrf.saveToken(null,request,response);attempts.remove(ip);return session(auth);
        }catch(AuthenticationException e){if(attempts.size()>1000)attempts.entrySet().removeIf(x->x.getValue().until<now);attempts.compute(ip,(key,a)->a==null||a.until<now?new Attempt(1,now+300):new Attempt(a.failures+1,a.until));audit.recordAs("anonymous","LOGIN_FAILED","session","current",Map.of());throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"账号或密码不正确");}
    }
    @PostMapping("/logout") Map<String,Object> logout(HttpServletRequest request,HttpServletResponse response){audit.record("LOGOUT","session","current",Map.of());var session=request.getSession(false);if(session!=null)session.invalidate();SecurityContextHolder.clearContext();csrf.saveToken(null,request,response);return Map.of("ok",true);}
    private record Attempt(int failures,long until){}
}
