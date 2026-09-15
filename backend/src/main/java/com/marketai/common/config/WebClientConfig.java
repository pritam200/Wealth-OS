package com.marketai.common.config;

import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebClientConfig {

    /**
     * Raise the reactive codec buffer limit for every WebClient in the app.
     *
     * The default is 256 KB, which is fine for a JSON quote but not for the bulk feeds this
     * app depends on — AMFI's NAVAll.txt is the whole Indian mutual-fund NAV universe and runs
     * to several megabytes. Hitting the cap surfaces as a DataBufferLimitException *after* a
     * 200 OK, so the fetch looks like it succeeded and then silently yields nothing.
     *
     * Applied centrally because each service builds its own client from the shared builder;
     * setting it per-call site would leave the next bulk feed to rediscover the same failure.
     */
    @Bean
    public WebClientCustomizer webClientCodecCustomizer() {
        return builder -> builder.codecs(codecs ->
            codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));   // 16 MB
    }
}
