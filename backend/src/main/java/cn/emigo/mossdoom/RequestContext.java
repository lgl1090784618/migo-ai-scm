package cn.emigo.mossdoom;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE)
class RequestContext extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        UUID id=UUID.randomUUID();request.setAttribute("requestId",id);response.setHeader("X-Request-Id",id.toString());
        response.setHeader("Cache-Control","no-store");chain.doFilter(request,response);
    }
    static UUID requestId(){var attrs=RequestContextHolder.getRequestAttributes();if(attrs instanceof ServletRequestAttributes a&&a.getRequest().getAttribute("requestId") instanceof UUID id)return id;return UUID.randomUUID();}
    static String actor(){var auth=SecurityContextHolder.getContext().getAuthentication();return auth==null?"system":auth.getName();}
}
