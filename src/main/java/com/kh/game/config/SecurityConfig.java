package com.kh.game.config;

import com.kh.game.security.CustomAuthenticationFailureHandler;
import com.kh.game.security.CustomAuthenticationSuccessHandler;
import com.kh.game.security.SessionCheckFilter;
import com.kh.game.security.SessionExpiredHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomAuthenticationSuccessHandler successHandler;
    private final CustomAuthenticationFailureHandler failureHandler;

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * 세션이 시간 초과로 사라질 때 SessionRegistry 에서도 지운다.
     * 없으면 레지스트리에 죽은 세션이 계속 쌓이고, /auth/validate-session 이 이미 끝난 세션을 유효하다고 답한다.
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * 열린 탭의 주기적 세션 확인(/auth/validate-session)은 세션을 읽지 않는 체인에서 SessionCheckFilter 가 바로 답한다.
     * 기본 체인(ConcurrentSessionFilter)이나 DispatcherServlet(FlashMap 조회)까지 가면 세션을 읽어 유휴 시간이 연장되고,
     * 탭을 열어 둔 동안 세션이 만료되지 않는다.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain sessionCheckFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(new AntPathRequestMatcher(SessionCheckFilter.PATH, "GET"))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.disable())
                .requestCache(cache -> cache.disable())
                // 익명 인증 객체를 만들 때도 세션 ID 를 읽으려고 getSession(false) 를 부른다
                .anonymous(anonymous -> anonymous.disable())
                .csrf(csrf -> csrf.disable())
                .addFilterBefore(new SessionCheckFilter(sessionRegistry()), AuthorizationFilter.class);

        return http.build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(new AntPathRequestMatcher("/css/**"),
                                new AntPathRequestMatcher("/js/**"),
                                new AntPathRequestMatcher("/images/**"),
                                new AntPathRequestMatcher("/uploads/**"),
                                new AntPathRequestMatcher("/favicon.svg")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/ws/**")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/auth/**")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/admin/login"),
                                new AntPathRequestMatcher("/admin/login-process"),
                                new AntPathRequestMatcher("/admin/logout")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/admin/**")).hasRole("ADMIN")
                        .requestMatchers(new AntPathRequestMatcher("/mypage/**")).authenticated()
                        .anyRequest().permitAll()
                )
                .csrf(csrf -> csrf
                        .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                        .csrfTokenRequestHandler(csrfHandler)
                        // SockJS 폴백 전송(xhr_streaming, xhr_send 등)은 POST 이지만 CSRF 토큰을 실을 수 없다.
                        // 인증은 핸드셰이크가 세션의 Principal 을 WebSocket 세션에 전파하고,
                        // 방 참가자 검사는 WebSocketAuthInterceptor 가 STOMP SUBSCRIBE 시점에 수행한다.
                        // 대기실·플레이 화면의 언로드(sendBeacon) 나가기도 헤더를 실을 수 없다.
                        // 즉시 나가지 않고 유예 뒤 적용되며 페이지 재진입 시 취소된다 (RoomUnloadService).
                        .ignoringRequestMatchers(new AntPathRequestMatcher("/ws/**"),
                                new AntPathRequestMatcher("/game/multi/room/*/unload", "POST"))
                )
                .formLogin(form -> form
                        .loginPage("/auth/login")
                        .loginProcessingUrl("/auth/login-process")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .successHandler(successHandler)
                        .failureHandler(failureHandler)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/auth/security-logout")
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                )
                .sessionManagement(session -> session
                        .maximumSessions(1)
                        .expiredSessionStrategy(new SessionExpiredHandler())
                        .sessionRegistry(sessionRegistry())
                )
                .httpBasic(basic -> basic.disable());

        return http.build();
    }
}
