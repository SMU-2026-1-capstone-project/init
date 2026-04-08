package com.shadowfit.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${ai-server.url}")
    private String aiServerUrl;

    // 메모리 버퍼 확장 설정
    @Bean
    public WebClient fastapiWebclient(){
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer ->configurer.defaultCodecs()
                        .maxInMemorySize(16*1024*1024)) // 16MB (단위: byte)
                .build();

        //위에서 설정한 ExchangeStrategies 적용
        return WebClient.builder()
                .exchangeStrategies(strategies)
                .baseUrl(aiServerUrl)
                .build();
    }
}
