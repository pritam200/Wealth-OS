package com.marketai.admin.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The access filter must run only inside the security chain (after JWT authentication), never as a plain servlet filter. */
@Configuration
public class AdminFilterRegistration {
    @Bean
    FilterRegistrationBean<AdminAccessFilter> adminAccessFilterRegistration(AdminAccessFilter filter) {
        FilterRegistrationBean<AdminAccessFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }
}
