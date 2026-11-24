/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer;

import org.apache.kafka.common.ClusterResource;
import org.apache.kafka.common.Endpoint;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.NotControllerException;
import org.apache.kafka.common.errors.SecurityDisabledException;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Monitorable;
import org.apache.kafka.common.metrics.PluginMetrics;
import org.apache.kafka.common.metrics.internals.PluginMetricsImpl;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.metadata.authorizer.AclMutator;
import org.apache.kafka.metadata.authorizer.ClusterMetadataAuthorizer;
import org.apache.kafka.metadata.authorizer.StandardAcl;
import org.apache.kafka.metadata.authorizer.StandardAuthorizer;
import org.apache.kafka.server.authorizer.AclCreateResult;
import org.apache.kafka.server.authorizer.AclDeleteResult;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.apache.kafka.server.authorizer.Authorizer;
import org.apache.kafka.server.authorizer.AuthorizerServerInfo;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class MetaAuthorizerTest {
    private static final String USER_LISTENER = "TLS-9093";
    private static final String CONTROLPLANE_LISTENER = "CONTROLPLANE-9090";
    private static final String REPLICATION_LISTENER = "REPLICATION-9091";

    private static final String KAFKA_USER = "User:CN=my-cluster-kafka,O=io.strimzi";
    private static final String TOPIC_OPERATOR_USER = "User:CN=my-cluster-entity-topic-operator,O=io.strimzi";
    private static final String REGULAR_USER = "User:CN=my-user";

    private static final Action READ_TOPIC = action(AclOperation.READ, ResourceType.TOPIC, "my-topic");
    private static final Action CREATE_TOPIC = action(AclOperation.CREATE, ResourceType.TOPIC, "my-topic");

    private static Action action(AclOperation operation, ResourceType type, String name) {
        return new Action(operation, new ResourcePattern(type, name, PatternType.LITERAL), 1, true, true);
    }

    private static Map<String, Object> baseConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("strimzi.metaauthorizer.listeners", CONTROLPLANE_LISTENER + "," + REPLICATION_LISTENER);
        config.put("strimzi.metaauthorizer.kafka.user", KAFKA_USER);
        config.put("strimzi.metaauthorizer.topicoperator.user", TOPIC_OPERATOR_USER);
        return config;
    }

    private static MetaAuthorizer metaAuthorizer(Authorizer delegate) {
        MetaAuthorizer authorizer = new MetaAuthorizer();
        authorizer.configure(new MetaAuthorizerConfig(baseConfig()), delegate);
        return authorizer;
    }

    //////////////////////////////////////////////////
    // Routing
    //////////////////////////////////////////////////

    @Test
    public void testUserListenerIsDelegated() {
        Authorizer delegate = mock(Authorizer.class);
        when(delegate.authorize(any(), any())).thenReturn(List.of(AuthorizationResult.DENIED));
        MetaAuthorizer authorizer = metaAuthorizer(delegate);

        // Regular user
        TestRequestContext context = TestRequestContext.of(USER_LISTENER, REGULAR_USER);
        assertEquals(List.of(AuthorizationResult.DENIED), authorizer.authorize(context, List.of(READ_TOPIC)));
        verify(delegate).authorize(context, List.of(READ_TOPIC));

        // Internal principals get no special treatment on the user listeners
        TestRequestContext kafkaContext = TestRequestContext.of(USER_LISTENER, KAFKA_USER);
        assertEquals(List.of(AuthorizationResult.DENIED), authorizer.authorize(kafkaContext, List.of(READ_TOPIC)));
        verify(delegate).authorize(kafkaContext, List.of(READ_TOPIC));
    }

    @Test
    public void testInternalUserOnInternalListenerUsesRules() {
        Authorizer delegate = mock(Authorizer.class);
        MetaAuthorizer authorizer = metaAuthorizer(delegate);

        // Kafka nodes are allowed everything
        assertEquals(List.of(AuthorizationResult.ALLOWED, AuthorizationResult.ALLOWED),
                authorizer.authorize(TestRequestContext.of(REPLICATION_LISTENER, KAFKA_USER), List.of(READ_TOPIC, action(AclOperation.CLUSTER_ACTION, ResourceType.CLUSTER, "kafka-cluster"))));
        assertEquals(List.of(AuthorizationResult.ALLOWED),
                authorizer.authorize(TestRequestContext.of(CONTROLPLANE_LISTENER, KAFKA_USER), List.of(READ_TOPIC)));

        // The Topic Operator is allowed only what it needs. Anything else is denied without consulting the delegate.
        assertEquals(List.of(AuthorizationResult.ALLOWED, AuthorizationResult.DENIED),
                authorizer.authorize(TestRequestContext.of(REPLICATION_LISTENER, TOPIC_OPERATOR_USER), List.of(CREATE_TOPIC, READ_TOPIC)));

        verifyNoInteractions(delegate);
    }

    @Test
    public void testStrictDenyIgnoresDelegate() {
        // Even if the delegate would allow it, the internal user is denied anything outside its rules
        Authorizer delegate = mock(Authorizer.class);
        when(delegate.authorize(any(), any())).thenReturn(List.of(AuthorizationResult.ALLOWED));
        MetaAuthorizer authorizer = metaAuthorizer(delegate);

        assertEquals(List.of(AuthorizationResult.DENIED),
                authorizer.authorize(TestRequestContext.of(REPLICATION_LISTENER, TOPIC_OPERATOR_USER), List.of(READ_TOPIC)));
        verify(delegate, never()).authorize(any(), any());
    }

    @Test
    public void testOtherPrincipalOnInternalListenerIsDelegated() {
        // This is the case of the requests forwarded from the brokers to the controllers
        Authorizer delegate = mock(Authorizer.class);
        when(delegate.authorize(any(), any())).thenReturn(List.of(AuthorizationResult.ALLOWED));
        MetaAuthorizer authorizer = metaAuthorizer(delegate);

        TestRequestContext context = TestRequestContext.of(CONTROLPLANE_LISTENER, REGULAR_USER);
        assertEquals(List.of(AuthorizationResult.ALLOWED), authorizer.authorize(context, List.of(CREATE_TOPIC)));
        verify(delegate).authorize(context, List.of(CREATE_TOPIC));
    }

    @Test
    public void testPrincipalTypeIsMatched() {
        Authorizer delegate = mock(Authorizer.class);
        when(delegate.authorize(any(), any())).thenReturn(List.of(AuthorizationResult.DENIED));
        MetaAuthorizer authorizer = metaAuthorizer(delegate);

        // Same name, different principal type => not an internal user
        TestRequestContext context = TestRequestContext.of(REPLICATION_LISTENER, "Group:CN=my-cluster-kafka,O=io.strimzi");
        assertEquals(List.of(AuthorizationResult.DENIED), authorizer.authorize(context, List.of(READ_TOPIC)));
        verify(delegate).authorize(context, List.of(READ_TOPIC));
    }

    @Test
    public void testPrincipalSubclassIsMatched() {
        // Custom principal builders might use KafkaPrincipal subclasses. These should be matched as well.
        MetaAuthorizer authorizer = metaAuthorizer(mock(Authorizer.class));

        KafkaPrincipal principal = new KafkaPrincipal("User", "CN=my-cluster-entity-topic-operator,O=io.strimzi") {
            // Anonymous subclass
        };
        TestRequestContext context = new TestRequestContext(REPLICATION_LISTENER, principal, TestRequestContext.of(REPLICATION_LISTENER, REGULAR_USER).clientAddress(), 0);

        assertEquals(List.of(AuthorizationResult.ALLOWED, AuthorizationResult.DENIED), authorizer.authorize(context, List.of(CREATE_TOPIC, READ_TOPIC)));
    }

    @Test
    public void testListenerNamesAreCaseInsensitive() {
        Map<String, Object> config = baseConfig();
        config.put("strimzi.metaauthorizer.listeners", "replication-9091");
        MetaAuthorizer authorizer = new MetaAuthorizer();
        authorizer.configure(new MetaAuthorizerConfig(config), mock(Authorizer.class));

        // Kafka passes the normalized (upper case) listener name in the request context
        assertEquals(List.of(AuthorizationResult.DENIED),
                authorizer.authorize(TestRequestContext.of(REPLICATION_LISTENER, TOPIC_OPERATOR_USER), List.of(READ_TOPIC)));
    }

    @Test
    public void testAuthorizeByResourceType() {
        Authorizer delegate = mock(Authorizer.class);
        when(delegate.authorizeByResourceType(any(), any(), any())).thenReturn(AuthorizationResult.DENIED);
        MetaAuthorizer authorizer = metaAuthorizer(delegate);

        // Internal users use the rules
        assertEquals(AuthorizationResult.ALLOWED, authorizer.authorizeByResourceType(TestRequestContext.of(REPLICATION_LISTENER, KAFKA_USER), AclOperation.WRITE, ResourceType.TRANSACTIONAL_ID));
        assertEquals(AuthorizationResult.ALLOWED, authorizer.authorizeByResourceType(TestRequestContext.of(REPLICATION_LISTENER, TOPIC_OPERATOR_USER), AclOperation.CREATE, ResourceType.TOPIC));
        assertEquals(AuthorizationResult.DENIED, authorizer.authorizeByResourceType(TestRequestContext.of(REPLICATION_LISTENER, TOPIC_OPERATOR_USER), AclOperation.WRITE, ResourceType.TOPIC));
        verify(delegate, never()).authorizeByResourceType(any(), any(), any());

        // Others are delegated
        TestRequestContext userContext = TestRequestContext.of(USER_LISTENER, TOPIC_OPERATOR_USER);
        assertEquals(AuthorizationResult.DENIED, authorizer.authorizeByResourceType(userContext, AclOperation.CREATE, ResourceType.TOPIC));
        verify(delegate).authorizeByResourceType(userContext, AclOperation.CREATE, ResourceType.TOPIC);

        TestRequestContext forwardedContext = TestRequestContext.of(CONTROLPLANE_LISTENER, REGULAR_USER);
        assertEquals(AuthorizationResult.DENIED, authorizer.authorizeByResourceType(forwardedContext, AclOperation.WRITE, ResourceType.TOPIC));
        verify(delegate).authorizeByResourceType(forwardedContext, AclOperation.WRITE, ResourceType.TOPIC);

        // Invalid arguments
        assertThrows(IllegalArgumentException.class, () -> authorizer.authorizeByResourceType(TestRequestContext.of(REPLICATION_LISTENER, KAFKA_USER), AclOperation.ANY, ResourceType.TOPIC));
        assertThrows(IllegalArgumentException.class, () -> authorizer.authorizeByResourceType(TestRequestContext.of(REPLICATION_LISTENER, KAFKA_USER), AclOperation.READ, ResourceType.ANY));
    }

    //////////////////////////////////////////////////
    // No delegate
    //////////////////////////////////////////////////

    @Test
    public void testNoDelegate() {
        MetaAuthorizer authorizer = metaAuthorizer(null);

        // Regular users are allowed everything
        assertEquals(List.of(AuthorizationResult.ALLOWED, AuthorizationResult.ALLOWED),
                authorizer.authorize(TestRequestContext.of(USER_LISTENER, REGULAR_USER), List.of(READ_TOPIC, CREATE_TOPIC)));
        assertEquals(List.of(AuthorizationResult.ALLOWED),
                authorizer.authorize(TestRequestContext.of(CONTROLPLANE_LISTENER, REGULAR_USER), List.of(CREATE_TOPIC)));
        assertEquals(AuthorizationResult.ALLOWED,
                authorizer.authorizeByResourceType(TestRequestContext.of(USER_LISTENER, REGULAR_USER), AclOperation.WRITE, ResourceType.TOPIC));

        // Internal users are still restricted
        assertEquals(List.of(AuthorizationResult.ALLOWED, AuthorizationResult.DENIED),
                authorizer.authorize(TestRequestContext.of(REPLICATION_LISTENER, TOPIC_OPERATOR_USER), List.of(CREATE_TOPIC, READ_TOPIC)));

        // ACL management is not available
        AclBinding binding = new AclBinding(new ResourcePattern(ResourceType.TOPIC, "my-topic", PatternType.LITERAL),
                new AccessControlEntry(REGULAR_USER, "*", AclOperation.READ, AclPermissionType.ALLOW));
        List<? extends CompletionStage<AclCreateResult>> createResults = authorizer.createAcls(TestRequestContext.of(CONTROLPLANE_LISTENER, KAFKA_USER), List.of(binding, binding));
        assertEquals(2, createResults.size());
        assertInstanceOf(SecurityDisabledException.class, createResults.get(0).toCompletableFuture().join().exception().orElseThrow());

        List<? extends CompletionStage<AclDeleteResult>> deleteResults = authorizer.deleteAcls(TestRequestContext.of(CONTROLPLANE_LISTENER, KAFKA_USER), List.of(AclBindingFilter.ANY));
        assertEquals(1, deleteResults.size());
        assertInstanceOf(SecurityDisabledException.class, deleteResults.get(0).toCompletableFuture().join().exception().orElseThrow());

        assertTrue(((Collection<?>) authorizer.acls(AclBindingFilter.ANY)).isEmpty());
        assertEquals(-1, authorizer.aclCount());

        // The ClusterMetadataAuthorizer methods are ignored
        authorizer.setAclMutator(mock(AclMutator.class));
        authorizer.loadSnapshot(Map.of());
        authorizer.completeInitialLoad();
        assertThrows(NotControllerException.class, authorizer::aclMutatorOrException);
    }

    @Test
    public void testNoDelegateStart() {
        MetaAuthorizer authorizer = metaAuthorizer(null);

        Endpoint endpoint1 = new Endpoint(REPLICATION_LISTENER, SecurityProtocol.SSL, "localhost", 9091);
        Endpoint endpoint2 = new Endpoint(USER_LISTENER, SecurityProtocol.SSL, "localhost", 9093);

        Map<Endpoint, ? extends CompletionStage<Void>> futures = authorizer.start(serverInfo(List.of(endpoint1, endpoint2)));

        assertEquals(Set.of(endpoint1, endpoint2), futures.keySet());
        futures.values().forEach(f -> assertTrue(f.toCompletableFuture().isDone()));
    }

    //////////////////////////////////////////////////
    // Delegation of other methods
    //////////////////////////////////////////////////

    @Test
    public void testAclManagementIsDelegated() throws Exception {
        Authorizer delegate = mock(Authorizer.class);
        MetaAuthorizer authorizer = metaAuthorizer(delegate);

        TestRequestContext context = TestRequestContext.of(CONTROLPLANE_LISTENER, REGULAR_USER);
        AclBinding binding = new AclBinding(new ResourcePattern(ResourceType.TOPIC, "my-topic", PatternType.LITERAL),
                new AccessControlEntry(REGULAR_USER, "*", AclOperation.READ, AclPermissionType.ALLOW));

        authorizer.createAcls(context, List.of(binding));
        verify(delegate).createAcls(context, List.of(binding));

        authorizer.deleteAcls(context, List.of(AclBindingFilter.ANY));
        verify(delegate).deleteAcls(context, List.of(AclBindingFilter.ANY));

        authorizer.acls(AclBindingFilter.ANY);
        verify(delegate).acls(AclBindingFilter.ANY);

        when(delegate.aclCount()).thenReturn(42);
        assertEquals(42, authorizer.aclCount());

        AuthorizerServerInfo serverInfo = serverInfo(List.of());
        authorizer.start(serverInfo);
        verify(delegate).start(serverInfo);

        authorizer.close();
        verify(delegate).close();
    }

    @Test
    public void testClusterMetadataAuthorizerIsForwarded() {
        ClusterMetadataAuthorizer delegate = mock(ClusterMetadataAuthorizer.class);
        MetaAuthorizer authorizer = metaAuthorizer(delegate);

        AclMutator mutator = mock(AclMutator.class);
        authorizer.setAclMutator(mutator);
        verify(delegate).setAclMutator(mutator);

        when(delegate.aclMutatorOrException()).thenReturn(mutator);
        assertSame(mutator, authorizer.aclMutatorOrException());

        Map<Uuid, StandardAcl> snapshot = Map.of(Uuid.randomUuid(), new StandardAcl(ResourceType.TOPIC, "my-topic", PatternType.LITERAL, REGULAR_USER, "*", AclOperation.READ, AclPermissionType.ALLOW));
        authorizer.loadSnapshot(snapshot);
        verify(delegate).loadSnapshot(snapshot);

        Uuid id = Uuid.randomUuid();
        StandardAcl acl = new StandardAcl(ResourceType.GROUP, "my-group", PatternType.LITERAL, REGULAR_USER, "*", AclOperation.READ, AclPermissionType.ALLOW);
        authorizer.addAcl(id, acl);
        verify(delegate).addAcl(id, acl);

        authorizer.removeAcl(id);
        verify(delegate).removeAcl(id);

        authorizer.completeInitialLoad();
        verify(delegate).completeInitialLoad();

        Exception e = new RuntimeException("failed");
        authorizer.completeInitialLoad(e);
        verify(delegate).completeInitialLoad(e);
    }

    @Test
    public void testPluginMetricsAreForwarded() {
        Authorizer delegate = mock(Authorizer.class, withSettings().extraInterfaces(Monitorable.class));
        PluginMetrics metrics = mock(PluginMetrics.class);

        // Delegate configured before the metrics are set
        MetaAuthorizer authorizer = metaAuthorizer(delegate);
        authorizer.withPluginMetrics(metrics);
        verify((Monitorable) delegate).withPluginMetrics(metrics);

        // Metrics set before the delegate is configured
        Authorizer delegate2 = mock(Authorizer.class, withSettings().extraInterfaces(Monitorable.class));
        MetaAuthorizer authorizer2 = new MetaAuthorizer();
        authorizer2.withPluginMetrics(metrics);
        authorizer2.configure(new MetaAuthorizerConfig(baseConfig()), delegate2);
        verify((Monitorable) delegate2).withPluginMetrics(metrics);
    }

    //////////////////////////////////////////////////
    // Configuration through the Kafka configuration map
    //////////////////////////////////////////////////

    @Test
    public void testConfigureWithStandardAuthorizer() throws Exception {
        Map<String, Object> config = baseConfig();
        config.put("strimzi.metaauthorizer.authorizer.class.name", StandardAuthorizer.class.getName());
        config.put("super.users", "User:admin");
        config.put("node.id", "0");

        try (MetaAuthorizer authorizer = new MetaAuthorizer(); Metrics metrics = new Metrics()) {
            authorizer.configure(config);
            authorizer.withPluginMetrics(new PluginMetricsImpl(metrics, Map.of()));
            assertInstanceOf(StandardAuthorizer.class, authorizer.delegate());

            // The ACLs are passed to the delegate through the ClusterMetadataAuthorizer interface
            authorizer.loadSnapshot(Map.of(Uuid.randomUuid(), new StandardAcl(ResourceType.TOPIC, "my-", PatternType.PREFIXED, REGULAR_USER, "*", AclOperation.READ, AclPermissionType.ALLOW)));
            authorizer.completeInitialLoad();
            assertEquals(1, authorizer.aclCount());

            // Regular user with ACL
            assertEquals(List.of(AuthorizationResult.ALLOWED, AuthorizationResult.DENIED),
                    authorizer.authorize(TestRequestContext.of(USER_LISTENER, REGULAR_USER), List.of(READ_TOPIC, CREATE_TOPIC)));
            // User-configured super-user (passed to the delegate)
            assertEquals(List.of(AuthorizationResult.ALLOWED, AuthorizationResult.ALLOWED),
                    authorizer.authorize(TestRequestContext.of(USER_LISTENER, "User:admin"), List.of(READ_TOPIC, CREATE_TOPIC)));
            // Internal user on user listener is not a super-user
            assertEquals(List.of(AuthorizationResult.DENIED),
                    authorizer.authorize(TestRequestContext.of(USER_LISTENER, KAFKA_USER), List.of(READ_TOPIC)));
            // Internal user on internal listener
            assertEquals(List.of(AuthorizationResult.ALLOWED),
                    authorizer.authorize(TestRequestContext.of(REPLICATION_LISTENER, KAFKA_USER), List.of(READ_TOPIC)));
        }
    }

    @Test
    public void testInternalUsersWorkBeforeInitialAclLoad() throws Exception {
        Map<String, Object> config = baseConfig();
        config.put("strimzi.metaauthorizer.authorizer.class.name", StandardAuthorizer.class.getName());

        try (MetaAuthorizer authorizer = new MetaAuthorizer(); Metrics metrics = new Metrics()) {
            authorizer.configure(config);
            authorizer.withPluginMetrics(new PluginMetricsImpl(metrics, Map.of()));

            // The StandardAuthorizer is not ready yet, but the internal users do not depend on it
            assertEquals(List.of(AuthorizationResult.ALLOWED),
                    authorizer.authorize(TestRequestContext.of(CONTROLPLANE_LISTENER, KAFKA_USER), List.of(action(AclOperation.CLUSTER_ACTION, ResourceType.CLUSTER, "kafka-cluster"))));
        }
    }

    @Test
    public void testUnknownDelegateClass() {
        Map<String, Object> config = baseConfig();
        config.put("strimzi.metaauthorizer.authorizer.class.name", "io.strimzi.DoesNotExist");

        assertThrows(ConfigException.class, () -> new MetaAuthorizer().configure(config));
    }

    private static AuthorizerServerInfo serverInfo(Collection<Endpoint> endpoints) {
        return new AuthorizerServerInfo() {
            @Override
            public ClusterResource clusterResource() {
                return new ClusterResource("my-cluster");
            }

            @Override
            public int brokerId() {
                return 0;
            }

            @Override
            public Collection<Endpoint> endpoints() {
                return endpoints;
            }

            @Override
            public Endpoint interBrokerEndpoint() {
                return null;
            }

            @Override
            public Collection<String> earlyStartListeners() {
                return List.of();
            }
        };
    }
}
