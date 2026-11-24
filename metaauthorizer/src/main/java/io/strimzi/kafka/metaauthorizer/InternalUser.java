/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.server.authorizer.AuthorizationResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Internal user with the precomputed rules of all the components using it. The same principal might be used by
 * multiple components (for example, the Topic and User Operators share the Service Account of the Entity Operator).
 * In such case, the user gets the union of the rules of all its components.
 */
final class InternalUser {
    private static final Rule[] NO_RULES = new Rule[0];

    private final String principal;
    private final Set<Component> components;
    private final boolean allowAll;
    private final Map<ResourceType, Rule[]> rulesByResourceType;

    /**
     * Constructs the internal user
     *
     * @param principal             The principal of the user (used for logging)
     * @param components            Components using this principal
     * @param cruiseControlTopics   Names of the Cruise Control topics
     */
    InternalUser(String principal, Set<Component> components, Set<String> cruiseControlTopics) {
        this.principal = principal;
        this.components = Collections.unmodifiableSet(EnumSet.copyOf(components));
        this.allowAll = components.stream().anyMatch(Component::allowAll);

        Map<ResourceType, List<Rule>> grouped = new EnumMap<>(ResourceType.class);
        for (Component component : components) {
            for (Rule rule : component.rules(cruiseControlTopics)) {
                grouped.computeIfAbsent(rule.resourceType(), k -> new ArrayList<>()).add(rule);
            }
        }

        Map<ResourceType, Rule[]> rules = new EnumMap<>(ResourceType.class);
        grouped.forEach((type, list) -> rules.put(type, list.toArray(NO_RULES)));
        this.rulesByResourceType = rules;
    }

    /**
     * Returns the principal of this user
     *
     * @return  The principal of this user
     */
    String principal() {
        return principal;
    }

    /**
     * Returns the components using this user
     *
     * @return  The components using this user
     */
    Set<Component> components() {
        return components;
    }

    /**
     * Authorizes a single operation on a literal resource.
     *
     * @param operation     Requested operation
     * @param resourceType  Type of the resource
     * @param resourceName  Name of the resource
     *
     * @return  ALLOWED if one of the rules allows it. DENIED otherwise.
     */
    AuthorizationResult authorize(AclOperation operation, ResourceType resourceType, String resourceName) {
        if (allowAll) {
            return AuthorizationResult.ALLOWED;
        }

        Rule[] rules = rulesByResourceType.getOrDefault(resourceType, NO_RULES);
        for (Rule rule : rules) {
            if (rule.allows(operation, resourceType, resourceName)) {
                return AuthorizationResult.ALLOWED;
            }
        }

        return AuthorizationResult.DENIED;
    }

    /**
     * Checks if the user is allowed to do the operation on at least some resource of given type. This follows the
     * semantics of the Authorizer#authorizeByResourceType method.
     *
     * @param operation     Requested operation
     * @param resourceType  Type of the resource
     *
     * @return  ALLOWED if one of the rules allows it. DENIED otherwise.
     */
    AuthorizationResult authorizeByResourceType(AclOperation operation, ResourceType resourceType) {
        if (allowAll) {
            return AuthorizationResult.ALLOWED;
        }

        Rule[] rules = rulesByResourceType.getOrDefault(resourceType, NO_RULES);
        for (Rule rule : rules) {
            if (rule.allowsAny(operation, resourceType)) {
                return AuthorizationResult.ALLOWED;
            }
        }

        return AuthorizationResult.DENIED;
    }
}
