/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.remotecluster;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.core.Strings;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.util.resource.Resource;
import org.elasticsearch.xcontent.ObjectPath;
import org.junit.ClassRule;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

public class RemoteClusterSecuritySpecialUserIT extends AbstractRemoteClusterSecurityTestCase {

    static {
        fulfillingCluster = ElasticsearchCluster.local()
            .name("fulfilling-cluster")
            .apply(commonClusterConfig)
            // anonymous user has superuser role, but it won't be applied to cross cluster access users
            .setting("xpack.security.authc.anonymous.roles", "superuser")
            .setting("remote_cluster_server.enabled", "true")
            .setting("remote_cluster.port", "0")
            .setting("xpack.security.remote_cluster_server.ssl.enabled", "true")
            .setting("xpack.security.remote_cluster_server.ssl.key", "remote-cluster.key")
            .setting("xpack.security.remote_cluster_server.ssl.certificate", "remote-cluster.crt")
            .keystore("xpack.security.remote_cluster_server.ssl.secure_key_passphrase", "remote-cluster-password")
            .build();

        queryCluster = ElasticsearchCluster.local()
            .name("query-cluster")
            .apply(commonClusterConfig)
            .rolesFile(Resource.fromClasspath("roles.yml"))
            .setting("xpack.security.authc.anonymous.roles", "read_remote_shared_logs")
            .setting("xpack.security.remote_cluster_client.ssl.enabled", "true")
            .setting("xpack.security.remote_cluster_client.ssl.certificate_authorities", "remote-cluster-ca.crt")
            .user(REMOTE_SEARCH_USER, PASS.toString(), "read_remote_shared_metrics")
            .build();
    }

    @ClassRule
    // Use a RuleChain to ensure that fulfilling cluster is started before query cluster
    public static TestRule clusterRule = RuleChain.outerRule(fulfillingCluster).around(queryCluster);

    public void testAnonymousUserFromQueryClusterWorks() throws Exception {
        final String crossClusterAccessApiKeyId = configureRemoteClustersWithApiKey("""
            [
               {
                 "names": ["shared-*", "apm-1", ".security*"],
                 "privileges": ["read", "read_cross_cluster"],
                 "allow_restricted_indices": true
               }
             ]""");

        // Fulfilling cluster
        {
            final Request bulkRequest = new Request("POST", "/_bulk?refresh=true");
            bulkRequest.setJsonEntity(Strings.format("""
                { "index": { "_index": "shared-logs" } }
                { "name": "shared-logs" }
                { "index": { "_index": "shared-metrics" } }
                { "name": "shared-metrics" }
                { "index": { "_index": "private-logs" } }
                { "name": "private-logs" }
                { "index": { "_index": "private-metrics" } }
                { "name": "private-metrics" }
                { "index": { "_index": "apm-1" } }
                { "name": "apm-1" }
                { "index": { "_index": "apm-2" } }
                { "name": "apm-2" }
                { "index": { "_index": "logs-apm.1" } }
                { "name": "logs-apm.1" }\n"""));
            assertOK(performRequestAgainstFulfillingCluster(bulkRequest));
        }

        // Query cluster
        {
            // 1. QC anonymous user can search FC shared-logs because QC anonymous role allows it (and cluster API key allows it)
            final Response response1 = performAnonymousRequestAgainstQueryCluster(
                new Request("GET", "/my_remote_cluster:" + randomFrom("*", "shared-*", "shared-logs") + "/_search")
            );
            assertOK(response1);
            final SearchResponse searchResponse1 = SearchResponse.fromXContent(responseAsParser(response1));
            assertThat(
                Arrays.stream(searchResponse1.getHits().getHits()).map(SearchHit::getIndex).collect(Collectors.toList()),
                containsInAnyOrder("shared-logs")
            );

            // 2. QC anonymous user fails to search more than it is allowed by the QC anonymous role
            // even when FC anonymous role allows everything
            final String inaccessibleIndexForAnonymous = randomFrom("shared-metrics", "private-logs");
            final ResponseException e2 = expectThrows(
                ResponseException.class,
                () -> performAnonymousRequestAgainstQueryCluster(
                    new Request("GET", "/my_remote_cluster:" + inaccessibleIndexForAnonymous + "/_search")
                )
            );
            assertThat(e2.getResponse().getStatusLine().getStatusCode(), equalTo(403));
            assertThat(
                e2.getMessage(),
                containsString(
                    "action [indices:data/read/search] towards remote cluster is unauthorized for user [_anonymous] "
                        + "with assigned roles [read_remote_shared_logs] authenticated by API key id ["
                        + crossClusterAccessApiKeyId
                        + "] of user [test_user] on indices ["
                        + inaccessibleIndexForAnonymous
                        + "]"
                )
            );

            // 3. QC search user can search both FC shared-logs (inherit from anonymous) and shared-metrics (its own role)
            final Response response3 = performRequestAgainstQueryCluster(
                new Request(
                    "GET",
                    randomFrom(
                        "/my_remote_cluster:*",
                        "/my_remote_cluster:shared-*",
                        "/my_remote_cluster:shared-logs,my_remote_cluster:shared-metrics"
                    ) + "/_search"
                )
            );
            assertOK(response3);
            final SearchResponse searchResponse3 = SearchResponse.fromXContent(responseAsParser(response3));
            assertThat(
                Arrays.stream(searchResponse3.getHits().getHits()).map(SearchHit::getIndex).collect(Collectors.toList()),
                containsInAnyOrder("shared-logs", "shared-metrics")
            );

            // 4. QC service account
            final Request createServiceTokenRequest = new Request("POST", "/_security/service/elastic/kibana/credential/token");
            final Response createServiceTokenResponse = client().performRequest(createServiceTokenRequest);
            assertOK(createServiceTokenResponse);
            final String serviceToken = ObjectPath.eval("token.value", responseAsMap(createServiceTokenResponse));

            final Request kibanaServiceSearchRequest = new Request("GET", "/*:*/_search");
            kibanaServiceSearchRequest.setOptions(RequestOptions.DEFAULT.toBuilder().addHeader("Authorization", "Bearer " + serviceToken));
            final Response kibanaServiceSearchResponse = client().performRequest(kibanaServiceSearchRequest);
            assertOK(kibanaServiceSearchResponse);
            final SearchResponse searchResponse4 = SearchResponse.fromXContent(responseAsParser(kibanaServiceSearchResponse));
            assertThat(
                Arrays.stream(searchResponse4.getHits().getHits()).map(SearchHit::getIndex).collect(Collectors.toList()),
                containsInAnyOrder("apm-1")
            );

            // 5. QC elastic superuser access system indices
            final Request changePasswordRequest = new Request("PUT", "/_security/user/elastic/_password");
            changePasswordRequest.setJsonEntity(Strings.format("""
                { "password": "%s" }""", PASS));
            assertOK(client().performRequest(changePasswordRequest));

            final Request elasticUserSearchRequest = new Request("GET", "/*:.security*/_search");
            elasticUserSearchRequest.setOptions(
                RequestOptions.DEFAULT.toBuilder().addHeader("Authorization", basicAuthHeaderValue("elastic", PASS))
            );
            final Response elasticUserSearchResponse = client().performRequest(elasticUserSearchRequest);
            assertOK(elasticUserSearchResponse);
            final SearchResponse searchResponse5 = SearchResponse.fromXContent(responseAsParser(elasticUserSearchResponse));
            assertThat(
                Arrays.stream(searchResponse5.getHits().getHits()).map(SearchHit::getIndex).collect(Collectors.toList()),
                containsInAnyOrder(".security-7")
            );
            assertThat(searchResponse5.getHits().getTotalHits().value, greaterThanOrEqualTo(1L));
        }
    }

    private Response performAnonymousRequestAgainstQueryCluster(Request request) throws IOException {
        request.setOptions(RequestOptions.DEFAULT.toBuilder().addHeader("Authorization", ""));
        return client().performRequest(request);
    }

    private Response performRequestAgainstQueryCluster(Request request) throws IOException {
        request.setOptions(RequestOptions.DEFAULT.toBuilder().addHeader("Authorization", basicAuthHeaderValue(REMOTE_SEARCH_USER, PASS)));
        return client().performRequest(request);
    }
}
