/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.repositories.s3;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSCredentialsProviderChain;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.auth.EC2ContainerCredentialsProviderWrapper;
import com.amazonaws.auth.STSAssumeRoleWithWebIdentitySessionCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.http.IdleConnectionReaper;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.internal.Constants;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.metadata.RepositoryMetaData;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import org.elasticsearch.env.Environment;

import static com.amazonaws.SDKGlobalConfiguration.AWS_ROLE_ARN_ENV_VAR;
import static com.amazonaws.SDKGlobalConfiguration.AWS_ROLE_SESSION_NAME_ENV_VAR;
import static com.amazonaws.SDKGlobalConfiguration.AWS_WEB_IDENTITY_ENV_VAR;
import static java.util.Collections.emptyMap;

class S3Service extends AbstractComponent implements Closeable {

    private volatile Map<S3ClientSettings, AmazonS3Reference> clientsCache = emptyMap();

    /**
     * Client settings calculated from static configuration and settings in the keystore.
     */
    private volatile Map<String, S3ClientSettings> staticClientSettings = MapBuilder.<String, S3ClientSettings>newMapBuilder()
        .put("default", S3ClientSettings.getClientSettings(Settings.EMPTY, "default")).immutableMap();

    /**
     * Client settings derived from those in {@link #staticClientSettings} by combining them with settings
     * in the {@link RepositoryMetaData}.
     */
    private volatile Map<S3ClientSettings, Map<RepositoryMetaData, S3ClientSettings>> derivedClientSettings = emptyMap();

    final CustomWebIdentityTokenCredentialsProvider webIdentityTokenCredentialsProvider;

    S3Service(Environment environment) {
        webIdentityTokenCredentialsProvider = new CustomWebIdentityTokenCredentialsProvider(
            environment,
            System::getenv,
            System::getProperty,
            Clock.systemUTC()
        );
    }

    /**
     * Refreshes the settings for the AmazonS3 clients and clears the cache of
     * existing clients. New clients will be build using these new settings. Old
     * clients are usable until released. On release they will be destroyed instead
     * of being returned to the cache.
     */
    public synchronized void refreshAndClearCache(Map<String, S3ClientSettings> clientsSettings) {
        // shutdown all unused clients
        // others will shutdown on their respective release
        releaseCachedClients();
        this.staticClientSettings = MapBuilder.newMapBuilder(clientsSettings).immutableMap();
        derivedClientSettings = emptyMap();
        assert this.staticClientSettings.containsKey("default") : "always at least have 'default'";
        // clients are built lazily by {@link client}
    }

    /**
     * Attempts to retrieve a client by its repository metadata and settings from the cache.
     * If the client does not exist it will be created.
     */
    public AmazonS3Reference client(RepositoryMetaData repositoryMetaData) {
        final S3ClientSettings clientSettings = settings(repositoryMetaData);
        {
            final AmazonS3Reference clientReference = clientsCache.get(clientSettings);
            if (clientReference != null && clientReference.tryIncRef()) {
                return clientReference;
            }
        }
        synchronized (this) {
            final AmazonS3Reference existing = clientsCache.get(clientSettings);
            if (existing != null && existing.tryIncRef()) {
                return existing;
            }
            final AmazonS3Reference clientReference = new AmazonS3Reference(buildClient(clientSettings));
            clientReference.incRef();
            clientsCache = MapBuilder.newMapBuilder(clientsCache).put(clientSettings, clientReference).immutableMap();
            return clientReference;
        }
    }

    /**
     * Either fetches {@link S3ClientSettings} for a given {@link RepositoryMetaData} from cached settings or creates them
     * by overriding static client settings from {@link #staticClientSettings} with settings found in the repository metadata.
     * @param repositoryMetaData Repository Metadata
     * @return S3ClientSettings
     */
    private S3ClientSettings settings(RepositoryMetaData repositoryMetaData) {
        final String clientName = S3Repository.CLIENT_NAME.get(repositoryMetaData.settings());
        final S3ClientSettings staticSettings = staticClientSettings.get(clientName);
        if (staticSettings != null) {
            {
                final S3ClientSettings existing = derivedClientSettings.getOrDefault(staticSettings, emptyMap()).get(repositoryMetaData);
                if (existing != null) {
                    return existing;
                }
            }
            synchronized (this) {
                final Map<RepositoryMetaData, S3ClientSettings> derivedSettings =
                    derivedClientSettings.getOrDefault(staticSettings, emptyMap());
                final S3ClientSettings existing = derivedSettings.get(repositoryMetaData);
                if (existing != null) {
                    return existing;
                }
                final S3ClientSettings newSettings = staticSettings.refine(repositoryMetaData);
                derivedClientSettings = MapBuilder.newMapBuilder(derivedClientSettings).put(
                    staticSettings, MapBuilder.newMapBuilder(derivedSettings).put(repositoryMetaData, newSettings).immutableMap()
                ).immutableMap();
                return newSettings;
            }
        }
        throw new IllegalArgumentException("Unknown s3 client name [" + clientName + "]. Existing client configs: "
            + Strings.collectionToDelimitedString(staticClientSettings.keySet(), ","));
    }

    // proxy for testing
    AmazonS3 buildClient(final S3ClientSettings clientSettings) {
        final AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        builder.withCredentials(buildCredentials(logger, clientSettings, webIdentityTokenCredentialsProvider));
        builder.withClientConfiguration(buildConfiguration(clientSettings));

        final String endpoint = Strings.hasLength(clientSettings.endpoint) ? clientSettings.endpoint : Constants.S3_HOSTNAME;
        logger.debug("using endpoint [{}]", endpoint);

        // If the endpoint configuration isn't set on the builder then the default behaviour is to try
        // and work out what region we are in and use an appropriate endpoint - see AwsClientBuilder#setRegion.
        // In contrast, directly-constructed clients use s3.amazonaws.com unless otherwise instructed. We currently
        // use a directly-constructed client, and need to keep the existing behaviour to avoid a breaking change,
        // so to move to using the builder we must set it explicitly to keep the existing behaviour.
        //
        // We do this because directly constructing the client is deprecated (was already deprecated in 1.1.223 too)
        // so this change removes that usage of a deprecated API.
        builder.withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, null));

        return builder.build();
    }

    // pkg private for tests
    static ClientConfiguration buildConfiguration(S3ClientSettings clientSettings) {
        final ClientConfiguration clientConfiguration = new ClientConfiguration();
        // the response metadata cache is only there for diagnostics purposes,
        // but can force objects from every response to the old generation.
        clientConfiguration.setResponseMetadataCacheSize(0);
        clientConfiguration.setProtocol(clientSettings.protocol);

        if (Strings.hasText(clientSettings.proxyHost)) {
            // TODO: remove this leniency, these settings should exist together and be validated
            clientConfiguration.setProxyHost(clientSettings.proxyHost);
            clientConfiguration.setProxyPort(clientSettings.proxyPort);
            clientConfiguration.setProxyUsername(clientSettings.proxyUsername);
            clientConfiguration.setProxyPassword(clientSettings.proxyPassword);
        }

        clientConfiguration.setMaxErrorRetry(clientSettings.maxRetries);
        clientConfiguration.setUseThrottleRetries(clientSettings.throttleRetries);
        clientConfiguration.setSocketTimeout(clientSettings.readTimeoutMillis);

        return clientConfiguration;
    }

    static AWSCredentialsProvider buildCredentials(
        Logger logger,
        S3ClientSettings clientSettings,
        CustomWebIdentityTokenCredentialsProvider webIdentityTokenCredentialsProvider
    ) {
        final S3BasicCredentials credentials = clientSettings.credentials;
        if (credentials == null) {
            if (webIdentityTokenCredentialsProvider.isActive()) {
                logger.debug("Using a custom provider chain of Web Identity Token and instance profile credentials");
                return new PrivilegedAWSCredentialsProvider(
                    new AWSCredentialsProviderChain(
                        new ErrorLoggingCredentialsProvider(webIdentityTokenCredentialsProvider, logger)
                    )
                );
            } else {
                logger.debug("Using instance profile credentials");
                return new PrivilegedAWSCredentialsProvider(new EC2ContainerCredentialsProviderWrapper());
            }
        } else {
            logger.debug("Using basic key/secret credentials");
            return new AWSStaticCredentialsProvider(credentials);
        }
    }


    private synchronized void releaseCachedClients() {
        // the clients will shutdown when they will not be used anymore
        for (final AmazonS3Reference clientReference : clientsCache.values()) {
            clientReference.decRef();
        }
        // clear previously cached clients, they will be build lazily
        clientsCache = emptyMap();
        // shutdown IdleConnectionReaper background thread
        // it will be restarted on new client usage
        IdleConnectionReaper.shutdown();
    }

    static class PrivilegedInstanceProfileCredentialsProvider implements AWSCredentialsProvider {
        private final AWSCredentialsProvider credentials;

        private PrivilegedInstanceProfileCredentialsProvider() {
            // InstanceProfileCredentialsProvider as last item of chain
            this.credentials = new EC2ContainerCredentialsProviderWrapper();
        }

        @Override
        public AWSCredentials getCredentials() {
            return SocketAccess.doPrivileged(credentials::getCredentials);
        }

        @Override
        public void refresh() {
            SocketAccess.doPrivilegedVoid(credentials::refresh);
        }
    }

    @Override
    public void close() throws IOException {
        releaseCachedClients();
    }

    /**
     * Customizes {@link com.amazonaws.auth.WebIdentityTokenCredentialsProvider}
     *
     * <ul>
     * <li>Reads the the location of the web identity token not from AWS_WEB_IDENTITY_TOKEN_FILE, but from a symlink
     * in the plugin directory, so we don't need to create a hardcoded read file permission for the plugin.</li>
     * <li>Supports customization of the STS endpoint via a system property, so we can test it against a test fixture.</li>
     * <li>Supports gracefully shutting down the provider and the STS client.</li>
     * </ul>
     */
    static class CustomWebIdentityTokenCredentialsProvider implements AWSCredentialsProvider {

        private static final String STS_HOSTNAME = "https://sts.amazonaws.com";

        private STSAssumeRoleWithWebIdentitySessionCredentialsProvider credentialsProvider;
        private AWSSecurityTokenService stsClient;

        CustomWebIdentityTokenCredentialsProvider(
            Environment environment,
            SystemEnvironment systemEnvironment,
            JvmEnvironment jvmEnvironment,
            Clock clock
        ) {
            // Check whether the original environment variable exists. If it doesn't,
            // the system doesn't support AWS web identity tokens
            if (systemEnvironment.getEnv(AWS_WEB_IDENTITY_ENV_VAR) == null) {
                return;
            }
            // Make sure that a readable symlink to the token file exists in the plugin config directory
            // AWS_WEB_IDENTITY_TOKEN_FILE exists but we only use Web Identity Tokens if a corresponding symlink exists and is readable
            Path webIdentityTokenFileSymlink = environment.configFile().resolve("repository-s3/aws-web-identity-token-file");
            if (Files.exists(webIdentityTokenFileSymlink) == false) {
                System.err.println(
                    "Cannot use AWS Web Identity Tokens: AWS_WEB_IDENTITY_TOKEN_FILE is defined but no corresponding symlink exists "
                        + "in the config directory"
                );
                return;
            }
            if (Files.isReadable(webIdentityTokenFileSymlink) == false) {
                throw new IllegalStateException("Unable to read a Web Identity Token symlink in the config directory");
            }
            String roleArn = systemEnvironment.getEnv(AWS_ROLE_ARN_ENV_VAR);
            if (roleArn == null) {
                System.err.println(
                    "Unable to use a web identity token for authentication. The AWS_WEB_IDENTITY_TOKEN_FILE environment "
                        + "variable is set, but either AWS_ROLE_ARN is missing"
                );
                return;
            }
            String roleSessionName = Optional.ofNullable(systemEnvironment.getEnv(AWS_ROLE_SESSION_NAME_ENV_VAR)).orElseGet(() -> "aws-sdk-java-" + clock.millis());
            AWSSecurityTokenServiceClientBuilder stsClientBuilder = AWSSecurityTokenServiceClient.builder();

            // Custom system property used for specifying a mocked version of the STS for testing
            String customStsEndpoint = jvmEnvironment.getProperty("com.amazonaws.sdk.stsMetadataServiceEndpointOverride", STS_HOSTNAME);
            // Set the region explicitly via the endpoint URL, so the AWS SDK doesn't make any guesses internally.
            stsClientBuilder.withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(customStsEndpoint, null));
            stsClientBuilder.withCredentials(new AWSStaticCredentialsProvider(new AnonymousAWSCredentials()));
            stsClient = SocketAccess.doPrivileged(stsClientBuilder::build);
            try {
                credentialsProvider = new STSAssumeRoleWithWebIdentitySessionCredentialsProvider.Builder(
                    roleArn,
                    roleSessionName,
                    webIdentityTokenFileSymlink.toString()
                ).withStsClient(stsClient).build();
            } catch (Exception e) {
                stsClient.shutdown();
                throw e;
            }
        }

        boolean isActive() {
            return credentialsProvider != null;
        }

        @Override
        public AWSCredentials getCredentials() {
            Objects.requireNonNull(credentialsProvider, "credentialsProvider is not set");
            return credentialsProvider.getCredentials();
        }

        @Override
        public void refresh() {
            if (credentialsProvider != null) {
                credentialsProvider.refresh();
            }
        }

        public void shutdown() throws IOException {
            if (credentialsProvider != null) {
                credentialsProvider.close();
                stsClient.shutdown();
            }
        }
    }


    static class PrivilegedAWSCredentialsProvider implements AWSCredentialsProvider {
        private final AWSCredentialsProvider credentialsProvider;

        private PrivilegedAWSCredentialsProvider(AWSCredentialsProvider credentialsProvider) {
            this.credentialsProvider = credentialsProvider;
        }

        AWSCredentialsProvider getCredentialsProvider() {
            return credentialsProvider;
        }

        @Override
        public AWSCredentials getCredentials() {
            return SocketAccess.doPrivileged(credentialsProvider::getCredentials);
        }

        @Override
        public void refresh() {
            SocketAccess.doPrivilegedVoid(credentialsProvider::refresh);
        }
    }

    static class ErrorLoggingCredentialsProvider implements AWSCredentialsProvider {

        private final AWSCredentialsProvider delegate;
        private final Logger logger;

        ErrorLoggingCredentialsProvider(AWSCredentialsProvider delegate, Logger logger) {
            this.delegate = Objects.requireNonNull(delegate);
            this.logger = Objects.requireNonNull(logger);
        }

        @Override
        public AWSCredentials getCredentials() {
            try {
                return delegate.getCredentials();
            } catch (Exception e) {
                logger.error(() -> "Unable to load credentials from " + delegate, e);
                throw e;
            }
        }

        @Override
        public void refresh() {
            try {
                delegate.refresh();
            } catch (Exception e) {
                logger.error(() -> "Unable to refresh " + delegate, e);
                throw e;
            }
        }
    }
    @FunctionalInterface
    interface SystemEnvironment {
        String getEnv(String name);
    }

    @FunctionalInterface
    interface JvmEnvironment {
        String getProperty(String key, String defaultValue);
    }

}
