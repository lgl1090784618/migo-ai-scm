package cn.emigo.mossdoom;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class ClientTokenFilter extends OncePerRequestFilter {
    private final JdbcTemplate db;
    ClientTokenFilter(JdbcTemplate db){this.db=db;}
    @Override protected boolean shouldNotFilter(HttpServletRequest r){return !r.getRequestURI().startsWith("/ingestion/v1/");}
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String auth=r.getHeader("Authorization");SecurityContextHolder.clearContext();
        if(auth==null||!auth.startsWith("Bearer ")||auth.length()>256){SecurityConfig.jsonError(response,401,"采集接口需要独立的Bearer令牌");return;}
        try{
            String hash=DomainSupport.sha(auth.substring(7).getBytes(StandardCharsets.UTF_8));
            List<Map<String,Object>> rows=db.queryForList("SELECT id,tenant_id,source_id,dataset_id FROM api_client WHERE token_hash=? AND enabled=true",hash);
            if(rows.size()!=1){SecurityConfig.jsonError(response,401,"采集身份无效或已停用");return;}
            Map<String,Object> row=rows.getFirst();CollectorIdentity client=new CollectorIdentity((UUID)row.get("id"),(String)row.get("tenant_id"),(UUID)row.get("source_id"),(UUID)row.get("dataset_id"));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(client,null,List.of(new SimpleGrantedAuthority("ROLE_INGESTOR"))));
            r.setAttribute("collector",client);chain.doFilter(r,response);
        }catch(org.springframework.dao.DataAccessException ex){SecurityConfig.jsonError(response,503,"采集身份服务暂不可用");}
    }
    record CollectorIdentity(UUID id,String tenant,UUID source,UUID dataset) implements java.security.Principal {public String getName(){return "collector:"+id;}}
}
