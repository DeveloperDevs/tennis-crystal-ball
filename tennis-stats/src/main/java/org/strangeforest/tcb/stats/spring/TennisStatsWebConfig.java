package org.strangeforest.tcb.stats.spring;

import java.time.*;

import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.*;
import org.springframework.boot.web.servlet.error.*;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.web.servlet.config.annotation.*;
import org.strangeforest.tcb.stats.controller.*;

@Configuration @ConditionalOnWebApplication
@EnableConfigurationProperties(ServerSSLProperties.class)
public class TennisStatsWebConfig implements WebMvcConfigurer {

	@Autowired(required = false) private DownForMaintenanceInterceptor downForMaintenanceInterceptor;
	@Autowired private PlayerImages playerImages;

	@Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler(PlayerImages.URL_PREFIX + "**")
			.addResourceLocations(playerImages.getDir().toUri().toString().replaceFirst("/?$", "/"))
			.setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
	}

	@Override public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new RequestURLLoggingHandlerInterceptor());
		if (downForMaintenanceInterceptor != null)
			registry.addInterceptor(downForMaintenanceInterceptor);
	}

	@Bean
	public UTSThymeleafDialect utsDialect() {
		return new UTSThymeleafDialect();
	}

	@Bean
	public ErrorAttributes errorAttributes() {
		return new TennisStatsErrorAttributes();
	}
}
