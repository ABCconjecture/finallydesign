package com.example.bysjdesign.config;

import com.example.bysjdesign.interceptor.DataPrivacyInterceptor;
import com.example.bysjdesign.interceptor.PageAccessInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final DataPrivacyInterceptor dataPrivacyInterceptor;
    private final PageAccessInterceptor pageAccessInterceptor;

    public WebConfig(DataPrivacyInterceptor dataPrivacyInterceptor,
                     PageAccessInterceptor pageAccessInterceptor) {
        this.dataPrivacyInterceptor = dataPrivacyInterceptor;
        this.pageAccessInterceptor = pageAccessInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(dataPrivacyInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health");

        registry.addInterceptor(pageAccessInterceptor)
                .addPathPatterns(
                        "/",
                        "/index.html",
                        "/dashboard.html",
                        "/analysis.html",
                        "/users.html",
                        "/warning.html",
                        "/profile.html",
                        "/account.html",
                        "/tasks.html",
                        "/campus/**"
                )
                .excludePathPatterns("/login.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }
}