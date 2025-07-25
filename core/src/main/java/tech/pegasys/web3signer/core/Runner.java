/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.web3signer.core;

import static tech.pegasys.web3signer.core.config.HealthCheckNames.DEFAULT_CHECK;
import static tech.pegasys.web3signer.core.config.HealthCheckNames.KEYS_CHECK_UNEXPECTED;

import tech.pegasys.web3signer.common.ApplicationInfo;
import tech.pegasys.web3signer.core.config.BaseConfig;
import tech.pegasys.web3signer.core.config.ClientAuthConstraints;
import tech.pegasys.web3signer.core.config.MetricsPushOptions;
import tech.pegasys.web3signer.core.config.TlsOptions;
import tech.pegasys.web3signer.core.metrics.vertx.VertxMetricsAdapterFactory;
import tech.pegasys.web3signer.core.routes.SwaggerUIRoute;
import tech.pegasys.web3signer.core.service.http.HostAllowListHandler;
import tech.pegasys.web3signer.core.service.http.handlers.LogErrorHandler;
import tech.pegasys.web3signer.core.service.http.handlers.UpcheckHandler;
import tech.pegasys.web3signer.core.util.FileUtil;
import tech.pegasys.web3signer.signing.ArtifactSignerProvider;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.ext.healthchecks.HealthCheckHandler;
import io.vertx.ext.healthchecks.Status;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.LoggerFormat;
import io.vertx.ext.web.handler.LoggerHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.tuweni.net.tls.VertxTrustOptions;
import org.hyperledger.besu.metrics.MetricsService;
import org.hyperledger.besu.metrics.MetricsSystemFactory;
import org.hyperledger.besu.metrics.StandardMetricCategory;
import org.hyperledger.besu.metrics.prometheus.MetricsConfiguration;
import org.hyperledger.besu.plugin.services.MetricsSystem;

public abstract class Runner implements Runnable, AutoCloseable {
  public static final String JSON = HttpHeaderValues.APPLICATION_JSON.toString();
  public static final String TEXT_PLAIN = HttpHeaderValues.TEXT_PLAIN.toString();
  public static final String HEALTHCHECK_PATH = "/healthcheck";
  public static final String UPCHECK_PATH = "/upcheck";

  private static final Logger LOG = LogManager.getLogger();

  protected final BaseConfig baseConfig;

  private HealthCheckHandler healthCheckHandler;
  private final List<Closeable> closeables = new ArrayList<>();

  protected Runner(final BaseConfig baseConfig) {
    this.baseConfig = baseConfig;
  }

  @Override
  public void run() {
    if (baseConfig.getLogLevel() != null) {
      System.out.println("Setting logging level to " + baseConfig.getLogLevel().name());
      Configurator.setRootLevel(baseConfig.getLogLevel());
    }

    final MetricsConfiguration metricsConfiguration = createMetricsConfiguration();
    final MetricsSystem metricsSystem = MetricsSystemFactory.create(metricsConfiguration);
    Optional<MetricsService> metricsService = Optional.empty();

    final Vertx vertx =
        Vertx.builder()
            .with(createVertxOptions())
            .withMetrics(new VertxMetricsAdapterFactory(metricsSystem))
            .build();
    final Router router = Router.router(vertx);

    final LogErrorHandler errorHandler = new LogErrorHandler();
    healthCheckHandler = HealthCheckHandler.create(vertx);

    final List<ArtifactSignerProvider> artifactSignerProviders =
        Optional.ofNullable(createArtifactSignerProvider(vertx, metricsSystem)).orElse(List.of());

    try {
      createVersionMetric(metricsSystem);
      metricsService = MetricsService.create(metricsConfiguration, metricsSystem);
      metricsService.ifPresent(MetricsService::start);

      // load the artifact signer providers in order ...
      artifactSignerProviders.forEach(
          provider -> {
            try {
              provider.load().get(); // wait for signers to get loaded ...
            } catch (final InterruptedException | ExecutionException e) {
              LOG.error("Error loading signers", e);
              registerHealthCheckProcedure(
                  KEYS_CHECK_UNEXPECTED, promise -> promise.complete(Status.KO()));
            }
          });

      // register access log handler first
      if (baseConfig.isAccessLogsEnabled()) {
        router.route().handler(LoggerHandler.create(LoggerFormat.DEFAULT));
      }

      router
          .route()
          .handler(
              CorsHandler.create()
                  .addRelativeOrigin(buildCorsRegexFromConfig())
                  .allowedHeader("*")
                  .allowedMethod(HttpMethod.GET)
                  .allowedMethod(HttpMethod.POST)
                  .allowedMethod(HttpMethod.DELETE)
                  .allowedMethod(HttpMethod.OPTIONS));
      registerHttpHostAllowListHandler(router);

      /*
       Add our own instance of BodyHandler as the default BodyHandler doesn't seem to handle large json bodies.
       BodyHandler must be first handler after platform and security handlers
      */
      router.route().handler(BodyHandler.create());
      registerUpcheckRoute(router, errorHandler);

      router
          .route(HttpMethod.GET, HEALTHCHECK_PATH)
          .handler(healthCheckHandler)
          .failureHandler(errorHandler);

      registerHealthCheckProcedure(DEFAULT_CHECK, promise -> promise.complete(Status.OK()));

      final Context context =
          new Context(router, metricsSystem, errorHandler, vertx, artifactSignerProviders);

      populateRouter(context);
      if (baseConfig.isSwaggerUIEnabled()) {
        new SwaggerUIRoute(router).register();
      }

      final HttpServer httpServer = createServerAndWait(vertx, router);
      final String tlsStatus = baseConfig.getTlsOptions().isPresent() ? "enabled" : "disabled";
      LOG.info(
          "Web3Signer has started with TLS {}, and ready to handle signing requests on {}:{}",
          tlsStatus,
          baseConfig.getHttpListenHost(),
          httpServer.actualPort());

      persistPortInformation(
          httpServer.actualPort(), metricsService.flatMap(MetricsService::getPort));

      closeables.add(() -> shutdownVertx(vertx));
    } catch (final Throwable e) {
      artifactSignerProviders.forEach(ArtifactSignerProvider::close);
      shutdownVertx(vertx);
      metricsService.ifPresent(MetricsService::stop);
      LOG.error("Failed to initialise application", e);
      throw new InitializationException(e);
    }
  }

  private void shutdownVertx(final Vertx vertx) {
    final CountDownLatch vertxShutdownLatch = new CountDownLatch(1);
    vertx.close((res) -> vertxShutdownLatch.countDown());
    try {
      vertxShutdownLatch.await();
    } catch (InterruptedException e) {
      throw new IllegalStateException("Interrupted while waiting for Vertx to stop", e);
    }
  }

  private MetricsConfiguration createMetricsConfiguration() {
    if (baseConfig.getMetricsPushOptions().isPresent()) {
      MetricsPushOptions options = baseConfig.getMetricsPushOptions().get();
      return MetricsConfiguration.builder()
          .metricCategories(baseConfig.getMetricCategories())
          .pushEnabled(options.isMetricsPushEnabled())
          .pushHost(options.getMetricsPushHost())
          .pushPort(options.getMetricsPushPort())
          .pushInterval(options.getMetricsPushIntervalSeconds())
          .prometheusJob(options.getMetricsPrometheusJob())
          .build();
    } else {
      return MetricsConfiguration.builder()
          .enabled(baseConfig.isMetricsEnabled())
          .port(baseConfig.getMetricsPort())
          .host(baseConfig.getMetricsNetworkInterface())
          .metricCategories(baseConfig.getMetricCategories())
          .hostsAllowlist(baseConfig.getMetricsHostAllowList())
          .build();
    }
  }

  private void createVersionMetric(final MetricsSystem metricsSystem) {
    metricsSystem
        .createLabelledSuppliedGauge(
            StandardMetricCategory.PROCESS, "release", "Release information", "version")
        .labels(() -> 1, ApplicationInfo.version());
  }

  private VertxOptions createVertxOptions() {
    return new VertxOptions()
        .setWorkerPoolSize(baseConfig.getVertxWorkerPoolSize())
        .setMetricsOptions(new MetricsOptions().setEnabled(true));
  }

  protected abstract List<ArtifactSignerProvider> createArtifactSignerProvider(
      final Vertx vertx, final MetricsSystem metricsSystem);

  protected abstract void populateRouter(final Context context);

  private void registerUpcheckRoute(final Router router, final LogErrorHandler errorHandler) {
    router
        .route(HttpMethod.GET, UPCHECK_PATH)
        .produces(TEXT_PLAIN)
        .handler(new UpcheckHandler())
        .failureHandler(errorHandler);
  }

  protected void registerHealthCheckProcedure(
      final String name, final Handler<Promise<Status>> procedure) {
    healthCheckHandler.register(name, procedure);
  }

  private void registerHttpHostAllowListHandler(final Router router) {
    router.route().handler(new HostAllowListHandler(baseConfig.getHttpHostAllowList()));
  }

  private HttpServer createServerAndWait(
      final Vertx vertx, final Handler<HttpServerRequest> requestHandler)
      throws ExecutionException, InterruptedException {
    final HttpServerOptions serverOptions =
        new HttpServerOptions()
            .setPort(baseConfig.getHttpListenPort())
            .setHost(baseConfig.getHttpListenHost())
            .setIdleTimeout(baseConfig.getIdleConnectionTimeoutSeconds())
            .setIdleTimeoutUnit(TimeUnit.SECONDS)
            .setReuseAddress(true)
            .setReusePort(true);
    final HttpServerOptions tlsServerOptions = applyConfigTlsSettingsTo(serverOptions);
    final HttpServer httpServer = vertx.createHttpServer(tlsServerOptions);
    final CompletableFuture<Void> serverRunningFuture = new CompletableFuture<>();
    httpServer
        .requestHandler(requestHandler)
        .listen(
            result -> {
              if (result.succeeded()) {
                serverRunningFuture.complete(null);
              } else {
                serverRunningFuture.completeExceptionally(result.cause());
              }
            });
    serverRunningFuture.get();

    return httpServer;
  }

  private HttpServerOptions applyConfigTlsSettingsTo(final HttpServerOptions input) {

    if (baseConfig.getTlsOptions().isEmpty()) {
      return input;
    }

    HttpServerOptions result = new HttpServerOptions(input);
    result.setSsl(true);
    final TlsOptions tlsConfig = baseConfig.getTlsOptions().get();

    result = applyTlsKeyStore(result, tlsConfig);

    if (tlsConfig.getClientAuthConstraints().isPresent()) {
      result = applyClientAuthentication(result, tlsConfig.getClientAuthConstraints().get());
    }

    return result;
  }

  private static HttpServerOptions applyTlsKeyStore(
      final HttpServerOptions input, final TlsOptions tlsConfig) {
    final HttpServerOptions result = new HttpServerOptions(input);

    try {
      final String keyStorePathname =
          tlsConfig.getKeyStoreFile().toPath().toAbsolutePath().toString();
      final String password =
          FileUtil.readFirstLineFromFile(tlsConfig.getKeyStorePasswordFile().toPath());
      result.setKeyCertOptions(new PfxOptions().setPath(keyStorePathname).setPassword(password));
      return result;
    } catch (final NoSuchFileException e) {
      throw new InitializationException(
          "Requested file " + e.getMessage() + " does not exist at specified location.", e);
    } catch (final AccessDeniedException e) {
      throw new InitializationException(
          "Current user does not have permissions to access " + e.getMessage(), e);
    } catch (final IOException e) {
      throw new InitializationException("Failed to load TLS files " + e.getMessage(), e);
    }
  }

  private static HttpServerOptions applyClientAuthentication(
      final HttpServerOptions input, final ClientAuthConstraints constraints) {
    final HttpServerOptions result = new HttpServerOptions(input);

    result.setClientAuth(ClientAuth.REQUIRED);
    try {
      constraints
          .getKnownClientsFile()
          .ifPresent(
              allowlistFile ->
                  result.setTrustOptions(
                      VertxTrustOptions.allowlistClients(
                          allowlistFile.toPath(), constraints.isCaAuthorizedClientAllowed())));
    } catch (final IllegalArgumentException e) {
      throw new InitializationException("Illegally formatted client fingerprint file.");
    }

    return result;
  }

  private void persistPortInformation(final int httpPort, final Optional<Integer> metricsPort) {
    if (baseConfig.getDataPath() == null) {
      return;
    }

    final File portsFile = new File(baseConfig.getDataPath().toFile(), "web3signer.ports");
    portsFile.deleteOnExit();

    final Properties properties = new Properties();
    properties.setProperty("http-port", String.valueOf(httpPort));
    metricsPort.ifPresent(port -> properties.setProperty("metrics-port", String.valueOf(port)));

    LOG.debug(
        "Writing web3signer.ports file: {}, with contents: {}",
        portsFile.getAbsolutePath(),
        properties);
    try (final FileOutputStream fileOutputStream = new FileOutputStream(portsFile)) {
      properties.store(
          fileOutputStream,
          "This file contains the ports used by the running instance of Web3Signer. "
              + "This file will be deleted after the node is shutdown.");
    } catch (final Exception e) {
      LOG.warn("Error writing ports file", e);
    }
  }

  private String buildCorsRegexFromConfig() {
    if (baseConfig.getCorsAllowedOrigins().isEmpty()) {
      return "";
    }
    if (baseConfig.getCorsAllowedOrigins().contains("*")) {
      return ".*";
    } else {
      final StringJoiner stringJoiner = new StringJoiner("|");
      baseConfig.getCorsAllowedOrigins().stream()
          .filter(s -> !s.isEmpty())
          .forEach(stringJoiner::add);
      return stringJoiner.toString();
    }
  }

  protected void registerClose(final Closeable closeable) {
    closeables.add(closeable);
  }

  @Override
  public void close() throws Exception {
    for (Closeable closeable : closeables) {
      try {
        closeable.close();
      } catch (Exception e) {
        LOG.error("Failed to close Runner resource", e);
      }
    }
  }
}
