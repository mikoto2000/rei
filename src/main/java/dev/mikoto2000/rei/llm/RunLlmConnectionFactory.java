package dev.mikoto2000.rei.llm;

import java.util.function.Function;

/** Resolves immutable settings only; credentials are obtained at the HTTP boundary. */
final class RunLlmConnectionFactory {
  static final String STATIC_CHAT = "_static-chat";

  record LegacySettings(
      ResolvedLlmEndpoint endpoint,
      java.util.Map<String, Object> generation,
      LlmAuthentication authentication,
      java.util.Map<String, String> transport) {
    LegacySettings(ResolvedLlmEndpoint endpoint, java.util.Map<String, Object> generation) {
      this(endpoint, generation, LlmAuthentication.LEGACY, java.util.Map.of());
    }

    LegacySettings {
      java.util.Objects.requireNonNull(endpoint, "endpoint");
      generation = RunGenerationSettings.copy(generation);
      java.util.Objects.requireNonNull(authentication, "authentication");
      transport = RunTransportSettings.copy(transport);
    }
  }

  @FunctionalInterface
  interface LegacySettingsResolver {
    LegacySettings resolve(String model);
  }

  static RunLlmConnectionFactory withLegacySettings(
      ChatEndpointCatalog catalog,
      LlmProperties properties,
      LegacySettingsResolver legacy,
      Function<String, String> registration) {
    return new RunLlmConnectionFactory(catalog, properties, legacy, registration);
  }

  private final ChatEndpointCatalog catalog;
  private final java.time.Duration readTimeout;
  private final int defaultOutputTokens;
  private final LegacySettingsResolver legacy;
  private final Function<String, String> registration;

  RunLlmConnectionFactory(
      ChatEndpointCatalog catalog,
      LlmProperties properties,
      Function<String, ResolvedLlmEndpoint> legacy,
      Function<String, String> registration) {
    this(
        catalog,
        properties,
        (LegacySettingsResolver)
            selected -> {
              var endpoint = legacy.apply(selected);
              return new LegacySettings(endpoint, generation(endpoint));
            },
        registration);
  }

  private RunLlmConnectionFactory(
      ChatEndpointCatalog catalog,
      LlmProperties properties,
      LegacySettingsResolver legacy,
      Function<String, String> registration) {
    this.catalog = catalog;
    this.readTimeout = properties.getReadTimeout();
    this.defaultOutputTokens = properties.getMaxOutputTokens();
    this.legacy = legacy;
    this.registration = registration;
  }

  /** Metadata requests do not require inference generation settings to be applicable. */
  RunLlmConnection discovery(String profile, String modelOverride) {
    if (!catalog.hasNamedProfiles()) return capture(profile, modelOverride);
    var source = catalog.connection(profile);
    var metadata =
        catalog.profiles().stream()
            .filter(value -> value.id().equals(profile))
            .findFirst()
            .orElseThrow();
    if (metadata.provider() == LlmProvider.CHATGPT_PLAN)
      throw new IllegalStateException("ChatGPT model discovery requires verified account support");
    var model = modelOverride == null ? metadata.model() : modelOverride;
    var capabilities = catalog.capabilities(metadata, model);
    var endpoint =
        new ResolvedLlmEndpoint(
            profile,
            metadata.generation(),
            metadata.api(),
            source.getBaseUrl(),
            "chat-endpoint:" + profile,
            model,
            new LlmCapabilities(
                ReasoningReplayMode.NONE, capabilities.maxTools(), java.util.Set.of()),
            null,
            null,
            source.getReadTimeout() == null ? readTimeout : source.getReadTimeout());
    return new RunLlmConnection(
        profile,
        true,
        metadata.provider(),
        metadata.authentication(),
        null,
        null,
        endpoint,
        capabilities.effective(),
        java.util.Map.of(),
        transport(source));
  }

  /**
   * Existing static chat settings used by autonomous inference, independent of normal selection.
   */
  RunLlmConnection captureIndependent(String model) {
    var source = catalog.feature(LlmFeature.CHAT);
    var settings = legacy.resolve(model == null && source != null ? source.getModel() : model);
    if (settings.authentication() != LlmAuthentication.API_KEY
        && settings.authentication() != LlmAuthentication.NONE)
      throw new IllegalStateException("Independent inference static authentication is unavailable");
    var provider =
        LlmProvider.configured(source == null ? null : source.getProvider(), "static chat");
    if (provider == LlmProvider.CHATGPT_PLAN)
      throw new IllegalStateException("Independent inference cannot inherit a ChatGPT account");
    var endpoint = settings.endpoint();
    var capabilities =
        LlmEndpointCapabilities.resolve(
                source,
                provider,
                endpoint.apiType(),
                endpoint.capabilities().maxTools(),
                "static chat",
                false)
            .effective();
    var snapshot =
        new RunLlmConnection(
            STATIC_CHAT,
            false,
            provider,
            settings.authentication(),
            null,
            null,
            endpoint,
            capabilities,
            settings.generation(),
            RunTransportSettings.defaults(settings.transport()));
    RunNativeOptions.options(snapshot);
    return snapshot;
  }

  RunLlmConnection capture(String profile, String modelOverride) {
    var connection = catalog.connection(profile);
    var metadata =
        catalog.profiles().stream().filter(p -> p.id().equals(profile)).findFirst().orElseThrow();
    if (!catalog.hasNamedProfiles()) {
      var settings = legacy.resolve(modelOverride);
      var resolved = settings.endpoint();
      var authentication =
          settings.authentication() == LlmAuthentication.LEGACY
              ? metadata.authentication()
              : settings.authentication();
      if (metadata.authentication() != LlmAuthentication.LEGACY
          && metadata.authentication() != authentication)
        throw new IllegalStateException(
            "Legacy authentication binding does not match its declaration");
      return new RunLlmConnection(
          profile,
          false,
          metadata.provider(),
          authentication,
          null,
          null,
          resolved,
          metadata.capabilities().effective(),
          settings.generation(),
          RunTransportSettings.defaults(settings.transport()));
    }
    var configured = namedConfiguration(profile, modelOverride, metadata, connection);
    var resolved = configured.endpoint();
    var capabilities = configured.capabilities();
    var accountRegistration =
        metadata.accountRef() == null ? null : registration.apply(metadata.accountRef());
    if (metadata.provider() == LlmProvider.CHATGPT_PLAN && accountRegistration == null)
      throw new IllegalStateException("ChatGPT account registration is unavailable");
    var snapshot =
        new RunLlmConnection(
            profile,
            true,
            metadata.provider(),
            metadata.authentication(),
            metadata.accountRef(),
            accountRegistration,
            resolved,
            capabilities,
            configured.generation(),
            transport(connection));
    RunNativeOptions.options(snapshot);
    return snapshot;
  }

  private record NamedConfiguration(
      ResolvedLlmEndpoint endpoint,
      java.util.Map<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support>
          capabilities,
      java.util.Map<String, Object> generation) {}

  /** Same pure generation resolution as admission, without account or credential access. */
  java.util.Map<String, Object> configuredGeneration(String profile, String model) {
    var metadata =
        catalog.profiles().stream()
            .filter(value -> value.id().equals(profile))
            .findFirst()
            .orElseThrow();
    var configured = namedConfiguration(profile, model, metadata, catalog.connection(profile));
    var preview =
        new RunLlmConnection(
            profile,
            true,
            metadata.provider(),
            metadata.authentication(),
            metadata.accountRef(),
            null,
            configured.endpoint(),
            configured.capabilities(),
            configured.generation());
    RunNativeOptions.options(preview);
    return configured.generation();
  }

  private NamedConfiguration namedConfiguration(
      String profile,
      String modelOverride,
      ChatEndpointCatalog.Profile metadata,
      LlmProperties.Server connection) {
    var model = modelOverride == null ? metadata.model() : modelOverride;
    if (model == null || model.isBlank() || model.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(
          "chat model must be explicit and contain no control characters");
    var capabilities = catalog.capabilities(metadata, model).effective();
    var replay =
        connection.getApi() == LlmApiType.CHAT_COMPLETIONS
            ? ReasoningReplayMode.NONE
            : connection.getReasoningReplay();
    if (replay == null)
      throw new IllegalStateException("reasoning-replay must be configured for Responses");
    var settings = java.util.EnumSet.noneOf(LlmCapabilities.Setting.class);
    for (var setting : LlmCapabilities.Setting.values()) {
      var capability =
          switch (setting) {
            case IMAGE_INPUT -> LlmEndpointCapabilities.Capability.VISION;
            default -> LlmEndpointCapabilities.Capability.valueOf(setting.name());
          };
      if (capabilities.get(capability) == LlmEndpointCapabilities.Support.SUPPORTED)
        settings.add(setting);
    }
    Integer output = connection.getMaxOutputTokens();
    if (capabilities.get(LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS)
        != LlmEndpointCapabilities.Support.SUPPORTED) {
      if (output != null)
        throw new IllegalStateException("max-output-tokens is unsupported or unconfirmed");
      output = null;
    } else if (output == null) output = defaultOutputTokens;
    if (connection.getTemperature() != null
        && !settings.contains(LlmCapabilities.Setting.TEMPERATURE))
      throw new IllegalStateException("temperature is unsupported or unconfirmed");
    var resolved =
        new ResolvedLlmEndpoint(
            profile,
            metadata.generation(),
            metadata.api(),
            connection.getBaseUrl(),
            "chat-endpoint:" + profile,
            model,
            new LlmCapabilities(replay, metadata.capabilities().maxTools(), settings),
            output,
            connection.getTemperature(),
            connection.getReadTimeout() == null ? readTimeout : connection.getReadTimeout());
    return new NamedConfiguration(
        resolved,
        capabilities,
        RunGenerationSettings.named(
            generation(resolved, metadata.provider()),
            connection.getGeneration(),
            resolved,
            capabilities));
  }

  /**
   * Validate the credential binding, retaining the saved model/options rather than current choices.
   */
  void verifyBinding(RunLlmConnection saved) {
    if (!saved.named() && STATIC_CHAT.equals(saved.profileId())) {
      var current = captureIndependent(saved.endpoint().model());
      if (saved.provider() != current.provider()
          || saved.authentication() != current.authentication()
          || saved.accountRef() != null
          || saved.accountRegistration() != null
          || saved.endpoint().generation() != current.endpoint().generation()
          || saved.endpoint().apiType() != current.endpoint().apiType()
          || !saved.endpoint().endpointId().equals(current.endpoint().endpointId())
          || !saved.endpoint().baseUrl().equals(current.endpoint().baseUrl())
          || !saved.endpoint().credentialRef().equals(current.endpoint().credentialRef())
          || !RunTransportSettings.binding(saved.transport())
              .equals(RunTransportSettings.binding(current.transport())))
        throw new IllegalStateException("Independent inference static connection binding changed");
      return;
    }
    var source = catalog.connection(saved.profileId());
    var metadata =
        catalog.profiles().stream()
            .filter(p -> p.id().equals(saved.profileId()))
            .findFirst()
            .orElseThrow();
    if (saved.named() != catalog.hasNamedProfiles())
      throw new IllegalStateException("Run connection source changed");
    if (saved.endpoint().generation() != metadata.generation())
      throw new IllegalStateException("Run connection generation changed");
    var legacySettings = saved.named() ? null : legacy.resolve(saved.endpoint().model());
    var authentication =
        legacySettings == null || legacySettings.authentication() == LlmAuthentication.LEGACY
            ? metadata.authentication()
            : legacySettings.authentication();
    if (saved.provider() != metadata.provider()
        || saved.authentication() != authentication
        || !java.util.Objects.equals(saved.accountRef(), metadata.accountRef()))
      throw new IllegalStateException("Run connection authentication binding changed");
    var current = legacySettings == null ? null : legacySettings.endpoint();
    var transport =
        legacySettings == null ? java.util.Map.<String, String>of() : legacySettings.transport();
    if (!RunTransportSettings.binding(saved.transport())
        .equals(RunTransportSettings.binding(transport)))
      throw new IllegalStateException("Run transport account binding changed");
    var baseUrl = saved.named() ? source.getBaseUrl() : current.baseUrl();
    var api = saved.named() ? metadata.api() : current.apiType();
    var credentialRef =
        saved.named() ? "chat-endpoint:" + saved.profileId() : current.credentialRef();
    if (!java.util.Objects.equals(baseUrl, saved.endpoint().baseUrl())
        || api != saved.endpoint().apiType()
        || !credentialRef.equals(saved.endpoint().credentialRef()))
      throw new IllegalStateException("Run connection destination changed");
    var identity = metadata.accountRef() == null ? null : registration.apply(metadata.accountRef());
    if (!java.util.Objects.equals(identity, saved.accountRegistration())
        || metadata.provider() == LlmProvider.CHATGPT_PLAN && identity == null)
      throw new IllegalStateException("Run account registration changed or unavailable");
  }

  private static java.util.Map<String, String> transport(LlmProperties.Server source) {
    var values = new java.util.HashMap<String, String>();
    if (source.getRequestLimitBytes() != null)
      values.put("request_limit_bytes", source.getRequestLimitBytes().toString());
    if (source.getResponseLimitBytes() != null)
      values.put("response_limit_bytes", source.getResponseLimitBytes().toString());
    if (source.getConnectTimeout() != null)
      values.put("connect_timeout", source.getConnectTimeout().toString());
    if (source.getRequestTimeout() != null)
      values.put("request_timeout", source.getRequestTimeout().toString());
    return RunTransportSettings.defaults(values);
  }

  private static java.util.Map<String, Object> generation(
      ResolvedLlmEndpoint endpoint, LlmProvider provider) {
    var values = new java.util.LinkedHashMap<>(generation(endpoint));
    if (provider == LlmProvider.OPENAI_API
        && endpoint.apiType() == LlmApiType.CHAT_COMPLETIONS
        && values.containsKey("max_tokens"))
      values.put("max_completion_tokens", values.remove("max_tokens"));
    return values;
  }

  private static java.util.Map<String, Object> generation(ResolvedLlmEndpoint endpoint) {
    var values = new java.util.LinkedHashMap<String, Object>();
    if (endpoint.maxOutputTokens() != null)
      values.put(
          endpoint.apiType() == LlmApiType.RESPONSES ? "max_output_tokens" : "max_tokens",
          endpoint.maxOutputTokens());
    if (endpoint.temperature() != null) values.put("temperature", endpoint.temperature());
    return values;
  }
}
