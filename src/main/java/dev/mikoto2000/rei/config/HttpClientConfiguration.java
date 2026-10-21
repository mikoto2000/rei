package dev.mikoto2000.rei.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

/** 外部情報を取得するHTTP通信の窓口の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration
public class HttpClientConfiguration {

  /** 設定の管理で用いるmdnsRestクライアントCustomizerを返す。 */
  @Bean
  RestClientCustomizer mdnsRestClientCustomizer() {
    ReactorClientHttpRequestFactory requestFactory =
        new ReactorClientHttpRequestFactory(mdnsHttpClient());

    return builder -> builder.requestFactory(requestFactory);
  }

  /** 設定の管理で用いるmdnsWebクライアントCustomizerを返す。 */
  @Bean
  WebClientCustomizer mdnsWebClientCustomizer() {
    ReactorClientHttpConnector connector = new ReactorClientHttpConnector(mdnsHttpClient());
    return builder -> builder.clientConnector(connector);
  }

  /** 設定の管理で用いるmdns外部情報を取得するHTTP通信の窓口を返す。 */
  private HttpClient mdnsHttpClient() {
    return HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE);
  }
}
