/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer;

import org.apache.kafka.common.Endpoint;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.NotControllerException;
import org.apache.kafka.common.errors.SecurityDisabledException;
import org.apache.kafka.common.metrics.Monitorable;
import org.apache.kafka.common.metrics.PluginMetrics;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.metadata.authorizer.AclMutator;
import org.apache.kafka.metadata.authorizer.ClusterMetadataAuthorizer;
import org.apache.kafka.metadata.authorizer.StandardAcl;
import org.apache.kafka.server.authorizer.AclCreateResult;
import org.apache.kafka.server.authorizer.AclDeleteResult;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.apache.kafka.server.authorizer.Authorizer;
import org.apache.kafka.server.authorizer.AuthorizerServerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Apache Kafka authorizer tailored for Strimzi.
 *
 * <p>It authorizes the requests as follows:</p>
 * <ul>
 *     <li>Requests on the user listeners are delegated to the user-configured authorizer.</li>
 *     <li>Requests from the internal Strimzi components (Kafka nodes, Cluster Operator, Topic and User Operators,
 *         Cruise Control, Kafka Exporter) on the internal listeners are authorized using hardcoded rules tailored to
 *         the needs of the given component. Anything not allowed by the rules is denied.</li>
 *     <li>Requests from any other principals on the internal listeners are delegated to the user-configured
 *         authorizer as well. These are the requests forwarded by the brokers to the controllers. The controllers
 *         authorize them using the principal of the original client but with the name of the control plane listener
 *         through which the forwarded request arrived.</li>
 * </ul>
 *
 * <p>When no user-configured authorizer is set, the requests which would be delegated to it are allowed.</p>
 *
 * <p>The Metaauthorizer implements the {@link ClusterMetadataAuthorizer} interface and forwards its calls to the
 * user-configured authorizer if it implements the interface as well (e.g. the Kafka StandardAuthorizer). This is
 * required for the user-configured authorizer to receive the ACLs stored in the KRaft metadata log.</p>
 */
public class MetaAuthorizer implements ClusterMetadataAuthorizer, Monitorable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MetaAuthorizer.class);

    // These fields are set in configure() before the authorizer is started and are not changed afterward
    private MetaAuthorizerConfig config;
    private Set<String> internalListeners = Set.of();
    private InternalUsers internalUsers;
    private Authorizer delegate;
    private ClusterMetadataAuthorizer clusterMetadataDelegate;
    private PluginMetrics pluginMetrics;

    /**
     * Constructs the Metaauthorizer. Kafka creates it using the default constructor.
     */
    public MetaAuthorizer() {
    }

    //////////////////////////////////////////////////
    // Configuration and lifecycle
    //////////////////////////////////////////////////

    @Override
    public void configure(Map<String, ?> configs) {
        MetaAuthorizerConfig config = new MetaAuthorizerConfig(configs);
        Authorizer delegate = createDelegate(config.authorizerClassName());

        if (delegate != null) {
            delegate.configure(configs);
        }

        configure(config, delegate);
    }

    /**
     * Configures the Metaauthorizer with an existing delegate authorizer. The delegate is expected to be already
     * configured.
     *
     * @param config    Metaauthorizer configuration
     * @param delegate  The delegate authorizer or null if no delegate should be used
     */
    /* test */ void configure(MetaAuthorizerConfig config, Authorizer delegate) {
        this.config = config;
        this.internalListeners = config.internalListeners();
        this.internalUsers = new InternalUsers(config);
        this.delegate = delegate;
        this.clusterMetadataDelegate = delegate instanceof ClusterMetadataAuthorizer cma ? cma : null;

        if (pluginMetrics != null) {
            maybeForwardPluginMetrics();
        }

        LOGGER.info("Strimzi Metaauthorizer configured with {} and delegate {}", config,
                delegate != null ? delegate.getClass().getName() : "none (requests not handled by the hardcoded rules are allowed)");
    }

    private static Authorizer createDelegate(String className) {
        if (className == null) {
            return null;
        }

        try {
            return Utils.newInstance(className, Authorizer.class);
        } catch (ClassNotFoundException e) {
            throw new ConfigException(MetaAuthorizerConfig.AUTHORIZER_CLASS_NAME, className, "Authorizer class not found");
        }
    }

    @Override
    public void withPluginMetrics(PluginMetrics metrics) {
        this.pluginMetrics = metrics;

        if (delegate != null) {
            maybeForwardPluginMetrics();
        }
    }

    private void maybeForwardPluginMetrics() {
        if (delegate instanceof Monitorable monitorable) {
            monitorable.withPluginMetrics(pluginMetrics);
        }
    }

    @Override
    public Map<Endpoint, ? extends CompletionStage<Void>> start(AuthorizerServerInfo serverInfo) {
        if (delegate != null) {
            return delegate.start(serverInfo);
        } else {
            Map<Endpoint, CompletableFuture<Void>> result = new HashMap<>();
            for (Endpoint endpoint : serverInfo.endpoints()) {
                result.put(endpoint, CompletableFuture.completedFuture(null));
            }
            return result;
        }
    }

    @Override
    public void close() throws IOException {
        if (delegate != null) {
            delegate.close();
        }
    }

    //////////////////////////////////////////////////
    // Authorization
    //////////////////////////////////////////////////

    @Override
    public List<AuthorizationResult> authorize(AuthorizableRequestContext requestContext, List<Action> actions) {
        if (internalListeners.contains(requestContext.listenerName())) {
            InternalUser user = internalUsers.find(requestContext.principal());

            if (user != null) {
                return authorizeInternalUser(user, requestContext, actions);
            }
        }

        if (delegate != null) {
            return delegate.authorize(requestContext, actions);
        } else {
            return Collections.nCopies(actions.size(), AuthorizationResult.ALLOWED);
        }
    }

    @Override
    public AuthorizationResult authorizeByResourceType(AuthorizableRequestContext requestContext, AclOperation op, ResourceType resourceType) {
        if (internalListeners.contains(requestContext.listenerName())) {
            InternalUser user = internalUsers.find(requestContext.principal());

            if (user != null) {
                checkAuthorizeByResourceTypeArgs(op, resourceType);
                return user.authorizeByResourceType(op, resourceType);
            }
        }

        if (delegate != null) {
            return delegate.authorizeByResourceType(requestContext, op, resourceType);
        } else {
            checkAuthorizeByResourceTypeArgs(op, resourceType);
            return AuthorizationResult.ALLOWED;
        }
    }

    private static List<AuthorizationResult> authorizeInternalUser(InternalUser user, AuthorizableRequestContext requestContext, List<Action> actions) {
        List<AuthorizationResult> results = new ArrayList<>(actions.size());

        for (Action action : actions) {
            ResourcePattern resource = action.resourcePattern();
            AuthorizationResult result = user.authorize(action.operation(), resource.resourceType(), resource.name());

            logDecision(user, requestContext, action, result);
            results.add(result);
        }

        return results;
    }

    private static void logDecision(InternalUser user, AuthorizableRequestContext requestContext, Action action, AuthorizationResult result) {
        if (result == AuthorizationResult.DENIED) {
            if (action.logIfDenied() && LOGGER.isInfoEnabled()) {
                LOGGER.info(decisionMessage(user, requestContext, action, result));
            }
        } else if (action.logIfAllowed() && LOGGER.isDebugEnabled()) {
            LOGGER.debug(decisionMessage(user, requestContext, action, result));
        }
    }

    private static String decisionMessage(InternalUser user, AuthorizableRequestContext requestContext, Action action, AuthorizationResult result) {
        return "Principal = " + user.principal()
                + " is " + (result == AuthorizationResult.ALLOWED ? "Allowed" : "Denied")
                + " operation = " + action.operation()
                + " from host = " + (requestContext.clientAddress() != null ? requestContext.clientAddress().getHostAddress() : "unknown")
                + " on resource = " + action.resourcePattern().resourceType() + ":" + action.resourcePattern().patternType() + ":" + action.resourcePattern().name()
                + " for request = " + requestName(requestContext.requestType())
                + " on listener = " + requestContext.listenerName()
                + " based on Strimzi Metaauthorizer rules for " + user.components();
    }

    private static String requestName(int requestType) {
        try {
            return ApiKeys.forId(requestType).name;
        } catch (IllegalArgumentException e) {
            return String.valueOf(requestType);
        }
    }

    private static void checkAuthorizeByResourceTypeArgs(AclOperation op, ResourceType resourceType) {
        if (op == AclOperation.ANY || op == AclOperation.UNKNOWN) {
            throw new IllegalArgumentException("Must specify a non-filter operation type for authorizeByResourceType, got " + op);
        }

        if (resourceType == ResourceType.ANY || resourceType == ResourceType.UNKNOWN) {
            throw new IllegalArgumentException("Must specify a non-filter resource type for authorizeByResourceType, got " + resourceType);
        }
    }

    //////////////////////////////////////////////////
    // ACL management (delegated)
    //////////////////////////////////////////////////

    @Override
    public List<? extends CompletionStage<AclCreateResult>> createAcls(AuthorizableRequestContext requestContext, List<AclBinding> aclBindings) {
        if (delegate != null) {
            return delegate.createAcls(requestContext, aclBindings);
        } else {
            AclCreateResult result = new AclCreateResult(new SecurityDisabledException("No authorizer is configured in the Strimzi Metaauthorizer"));
            return aclBindings.stream().map(binding -> CompletableFuture.completedFuture(result)).toList();
        }
    }

    @Override
    public List<? extends CompletionStage<AclDeleteResult>> deleteAcls(AuthorizableRequestContext requestContext, List<AclBindingFilter> aclBindingFilters) {
        if (delegate != null) {
            return delegate.deleteAcls(requestContext, aclBindingFilters);
        } else {
            AclDeleteResult result = new AclDeleteResult(new SecurityDisabledException("No authorizer is configured in the Strimzi Metaauthorizer"));
            return aclBindingFilters.stream().map(filter -> CompletableFuture.completedFuture(result)).toList();
        }
    }

    @Override
    public Iterable<AclBinding> acls(AclBindingFilter filter) {
        if (delegate != null) {
            return delegate.acls(filter);
        } else {
            return List.of();
        }
    }

    @Override
    public int aclCount() {
        if (delegate != null) {
            return delegate.aclCount();
        } else {
            return -1;
        }
    }

    //////////////////////////////////////////////////
    // ClusterMetadataAuthorizer (forwarded to the delegate if it supports it)
    //////////////////////////////////////////////////

    @Override
    public void setAclMutator(AclMutator aclMutator) {
        if (clusterMetadataDelegate != null) {
            clusterMetadataDelegate.setAclMutator(aclMutator);
        }
    }

    @Override
    public AclMutator aclMutatorOrException() {
        if (clusterMetadataDelegate != null) {
            return clusterMetadataDelegate.aclMutatorOrException();
        } else {
            throw new NotControllerException("The delegate authorizer does not store the ACLs in the KRaft metadata");
        }
    }

    @Override
    public void completeInitialLoad() {
        if (clusterMetadataDelegate != null) {
            clusterMetadataDelegate.completeInitialLoad();
        }
    }

    @Override
    public void completeInitialLoad(Exception e) {
        if (clusterMetadataDelegate != null) {
            clusterMetadataDelegate.completeInitialLoad(e);
        }
    }

    @Override
    public void loadSnapshot(Map<Uuid, StandardAcl> acls) {
        if (clusterMetadataDelegate != null) {
            clusterMetadataDelegate.loadSnapshot(acls);
        }
    }

    @Override
    public void addAcl(Uuid id, StandardAcl acl) {
        if (clusterMetadataDelegate != null) {
            clusterMetadataDelegate.addAcl(id, acl);
        }
    }

    @Override
    public void removeAcl(Uuid id) {
        if (clusterMetadataDelegate != null) {
            clusterMetadataDelegate.removeAcl(id);
        }
    }

    //////////////////////////////////////////////////
    // Accessors used in tests
    //////////////////////////////////////////////////

    /* test */ MetaAuthorizerConfig config() {
        return config;
    }

    /* test */ Authorizer delegate() {
        return delegate;
    }
}
