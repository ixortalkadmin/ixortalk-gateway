/**
 *
 *  2016 (c) IxorTalk CVBA
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of IxorTalk CVBA
 *
 * The intellectual and technical concepts contained
 * herein are proprietary to IxorTalk CVBA
 * and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 *
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from IxorTalk CVBA.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.
 */
package com.ixortalk.gateway.security;

import java.io.IOException;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.security.oauth2.client.EnableOAuth2Sso;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.netflix.zuul.filters.ZuulProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.annotation.web.configurers.ExpressionUrlAuthorizationConfigurer;
import org.springframework.security.oauth2.client.filter.OAuth2ClientContextFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import static java.util.Arrays.stream;

@Configuration
@EnableOAuth2Sso
@EnableConfigurationProperties(IxorTalkProperties.class)
public class SecurityConfiguration extends WebSecurityConfigurerAdapter {

    @SuppressWarnings("SpringJavaAutowiringInspection")
    @Inject
    private ZuulProperties zuulProperties;

    @Inject
    private IxorTalkProperties ixorTalkProperties;

    @Bean
    @Primary
    public OAuth2ClientContextFilter dynamicOauth2ClientContextFilter() {
        return new DynamicOauth2ClientContextFilter();
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry authorizeRequests = http.authorizeRequests();

        authorizeRequests
                .antMatchers(
                        "/uaa/oauth/token",
                        "/uaa/oauth/authorize",
                        "/uaa/login",
                        "/uaa/user",
                        "/uaa/assets/**",
                        "/login")
                .permitAll()
                .antMatchers(ixorTalkProperties.getPermitAllPaths().toArray(new String[]{})).permitAll();

        authorizeRequests = authorizeHasAnyRoleRoutes(authorizeRequests);
        authorizeRequests = authorizePermitAllRoutes(authorizeRequests);

        authorizeRequests
                .anyRequest().authenticated()
                .and()
                .headers().frameOptions().disable().and()
                .csrf()
                .ignoringAntMatchers(
                        ixorTalkProperties.getRoleMatchers().keySet()
                                .stream()
                                .filter(routeKey -> !ixorTalkProperties.getRoleMatchers().get(routeKey).isCsrfEnabled())
                                .map(routeKey -> zuulProperties.getRoutes().get(routeKey).getPath())
                                .toArray(String[]::new))
                .requireCsrfProtectionMatcher(csrfRequestMatcher())
                .csrfTokenRepository(csrfTokenRepository())
                .and()
                .addFilterAfter(csrfHeaderFilter(), CsrfFilter.class)
                .logout()
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                .logoutSuccessUrl("/uaa/signout");
    }

    private ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry authorizePermitAllRoutes(ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry authorizeRequests) {
        zuulProperties.getRoutes().keySet()
                .stream()
                .filter(routeKey -> ixorTalkProperties.getRoleMatchers().containsKey(routeKey))
                .filter(routeKey -> ixorTalkProperties.getRoleMatchers().get(routeKey).isPermitAll())
                .forEach(routeKey ->
                        authorizeRequests
                                .antMatchers(zuulProperties.getRoutes().get(routeKey).getPath())
                                .permitAll());
        return authorizeRequests;
    }

    private ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry authorizeHasAnyRoleRoutes(ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry authorizeRequests) {
        zuulProperties.getRoutes().keySet()
                .stream()
                .filter(routeKey -> ixorTalkProperties.getRoleMatchers().containsKey(routeKey))
                .filter(routeKey -> !ixorTalkProperties.getRoleMatchers().get(routeKey).getHasAnyRole().isEmpty())
                .forEach(routeKey ->
                        authorizeRequests
                                .antMatchers(zuulProperties.getRoutes().get(routeKey).getPath())
                                .hasAnyRole(ixorTalkProperties.getRoleMatchers().get(routeKey).hasAnyRoleNames()));
        return authorizeRequests;
    }

    private RequestMatcher csrfRequestMatcher() {
        return new RequestMatcher() {
            // Always allow the HTTP GET method
            private final Pattern allowedMethods = Pattern.compile("^(GET|HEAD|OPTIONS|TRACE)$");

            // Disable CSRF protection on the following urls:
            private final AntPathRequestMatcher[] requestMatchers = {new AntPathRequestMatcher("/uaa/**")};

            @Override
            public boolean matches(HttpServletRequest request) {
                if (allowedMethods.matcher(request.getMethod()).matches()) {
                    return false;
                }

                return stream(requestMatchers).noneMatch(matcher -> matcher.matches(request));
            }
        };
    }

    private Filter csrfHeaderFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response, FilterChain filterChain)
                    throws ServletException, IOException {
                CsrfToken csrf = (CsrfToken) request
                        .getAttribute(CsrfToken.class.getName());
                if (csrf != null) {
                    Cookie cookie = WebUtils.getCookie(request, "XSRF-TOKEN");
                    String token = csrf.getToken();
                    if (cookie == null
                            || token != null && !token.equals(cookie.getValue())) {
                        cookie = new Cookie("XSRF-TOKEN", token);
                        cookie.setPath("/");
                        response.addCookie(cookie);
                    }
                }
                filterChain.doFilter(request, response);
            }
        };
    }

    private CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-XSRF-TOKEN");
        return repository;
    }
}
