// ABOUTME: Spring Security configuration for form-based authentication and authorization
// ABOUTME: Secures HTTP endpoints and WebSocket connections with in-memory users
package ai.apps.syncfusioncollaborativeediting.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AntPathRequestMatcher

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        AntPathRequestMatcher("/login.html"),
                        AntPathRequestMatcher("/login"),
                        AntPathRequestMatcher("/css/**"),
                        AntPathRequestMatcher("/js/**"),
                        AntPathRequestMatcher("/images/**")
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .formLogin { form ->
                form
                    .loginPage("/login.html")
                    .loginProcessingUrl("/login")
                    .defaultSuccessUrl("/files.html", true)
                    .permitAll()
            }
            .logout { logout ->
                logout
                    .logoutSuccessUrl("/login.html?logout")
                    .permitAll()
            }
            .csrf { it.disable() }

        return http.build()
    }

    @Bean
    fun userDetailsService(passwordEncoder: PasswordEncoder): UserDetailsService {
        val bob = User.builder()
            .username("bob")
            .password(passwordEncoder.encode("bob"))
            .roles("USER")
            .build()

        val joe = User.builder()
            .username("joe")
            .password(passwordEncoder.encode("joe"))
            .roles("USER")
            .build()

        val alice = User.builder()
            .username("alice")
            .password(passwordEncoder.encode("alice"))
            .roles("USER")
            .build()

        return InMemoryUserDetailsManager(bob, joe, alice)
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }
}
