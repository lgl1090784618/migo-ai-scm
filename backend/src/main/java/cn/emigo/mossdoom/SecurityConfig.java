package cn.emigo.mossdoom;

import com.fasterxml.jackson.core.StreamReadConstraints;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration @EnableMethodSecurity
class SecurityConfig {
    @Bean BCryptPasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder(12);}
    @Bean UserDetailsService users(BCryptPasswordEncoder encoder,@Value("${MOSS_ADMIN_PASSWORD_FILE:/run/secrets/app_admin}") String admin,@Value("${MOSS_VIEWER_PASSWORD_FILE:/run/secrets/app_viewer}") String viewer){
        return new InMemoryUserDetailsManager(User.withUsername("admin").password(encoder.encode(DatabaseConfig.secret(admin,6))).roles("ADMIN").build(),User.withUsername("viewer").password(encoder.encode(DatabaseConfig.secret(viewer,6))).roles("VIEWER").build());
    }
    @Bean AuthenticationManager authenticationManager(UserDetailsService users,BCryptPasswordEncoder encoder){DaoAuthenticationProvider p=new DaoAuthenticationProvider(users);p.setPasswordEncoder(encoder);return new ProviderManager(List.of(p));}
    @Bean HttpSessionSecurityContextRepository contextRepository(){return new HttpSessionSecurityContextRepository();}
    @Bean CookieCsrfTokenRepository csrfRepository(){CookieCsrfTokenRepository r=CookieCsrfTokenRepository.withHttpOnlyFalse();r.setCookieCustomizer(c->c.secure(true).sameSite("Strict").path("/"));return r;}
    @Bean FilterRegistrationBean<ClientTokenFilter> disableAutoFilter(ClientTokenFilter filter){FilterRegistrationBean<ClientTokenFilter> registration=new FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;}
    @Bean SecurityFilterChain security(HttpSecurity http,ClientTokenFilter clients,HttpSessionSecurityContextRepository contexts,CookieCsrfTokenRepository csrf)throws Exception{
        return http.securityContext(c->c.securityContextRepository(contexts))
            .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()).ignoringRequestMatchers("/ingestion/v1/**"))
            .authorizeHttpRequests(a->a.requestMatchers("/","/index.html","/assets/**","/favicon.ico","/api/auth/csrf","/api/auth/session","/api/auth/login","/actuator/health","/actuator/prometheus").permitAll()
                .requestMatchers("/ingestion/v1/**").hasRole("INGESTOR").requestMatchers("/api/**").hasAnyRole("ADMIN","VIEWER").anyRequest().denyAll())
            .addFilterBefore(clients,UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->jsonError(res,401,"请先登录或提供有效的采集身份"))
                .accessDeniedHandler((req,res,ex)->jsonError(res,403,"权限不足或请求校验已过期，请刷新页面")))
            .formLogin(f->f.disable()).httpBasic(b->b.disable()).logout(l->l.disable()).build();
    }
    static void jsonError(HttpServletResponse response,int status,String message)throws java.io.IOException{response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.getWriter().write(DomainSupport.json(java.util.Map.of("code","ACCESS_DENIED","message",message,"requestId",RequestContext.requestId().toString())));}
    @Bean Jackson2ObjectMapperBuilderCustomizer constraints(){return builder->builder.postConfigurer(mapper->mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40).maxStringLength(5*1024*1024).maxDocumentLength(6*1024*1024).build()));}
}
