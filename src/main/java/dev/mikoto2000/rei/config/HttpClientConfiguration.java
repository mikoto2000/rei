package dev.mikoto2000.rei.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

@Configuration
public class HttpClientConfiguration {

    @Bean
    RestClientCustomizer mdnsRestClientCustomizer() {
        ReactorClientHttpRequestFactory requestFactory =
            new ReactorClientHttpRequestFactory(mdnsHttpClient());

        return builder -> builder.requestFactory(requestFactory);
    }

    @Bean
    WebClientCustomizer mdnsWebClientCustomizer() {
        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(mdnsHttpClient());
        return builder -> builder.clientConnector(connector);
    }

    private HttpClient mdnsHttpClient() {
        return HttpClient.create()
            .resolver(DefaultAddressResolverGroup.INSTANCE);
    }
}
