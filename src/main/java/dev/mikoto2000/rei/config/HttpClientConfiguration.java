package dev.mikoto2000.rei.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import reactor.netty.http.client.HttpClient;

@Configuration
public class HttpClientConfiguration {

    @Bean
    RestClientCustomizer mdnsRestClientCustomizer() {
        HttpClient httpClient = HttpClient.create()
            .resolver(DefaultAddressResolverGroup.INSTANCE);

        ReactorClientHttpRequestFactory requestFactory =
            new ReactorClientHttpRequestFactory(httpClient);

        return builder -> builder.requestFactory(requestFactory);
    }
}

