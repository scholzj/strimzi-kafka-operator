/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Single hardcoded authorization rule. It allows a set of operations on resources of a given type whose name matches
 * the rule's pattern. The rules only allow operations. There are no deny rules. Anything not allowed by a rule is
 * denied.
 *
 * The operations implied by Kafka are added automatically when the rule is created: READ, WRITE, DELETE and ALTER
 * imply DESCRIBE, and ALTER_CONFIGS implies DESCRIBE_CONFIGS. This mirrors the behavior of the Kafka ACLs.
 *
 * @param resourceType  Type of the resource
 * @param patternType   Type of the pattern (LITERAL or PREFIXED). The LITERAL pattern with name '*' matches all
 *                      resources of given type.
 * @param name          Name of the resource or the prefix
 * @param operations    Operations allowed by this rule (including the implied operations)
 */
public record Rule(ResourceType resourceType, PatternType patternType, String name, Set<AclOperation> operations) {
    /**
     * Constructs the rule and validates it
     */
    public Rule {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(patternType, "patternType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(operations, "operations");

        if (resourceType == ResourceType.ANY || resourceType == ResourceType.UNKNOWN) {
            throw new IllegalArgumentException("Rule must use a specific resource type, not " + resourceType);
        }

        if (patternType != PatternType.LITERAL && patternType != PatternType.PREFIXED) {
            throw new IllegalArgumentException("Rule must use LITERAL or PREFIXED pattern type, not " + patternType);
        }

        if (operations.isEmpty()) {
            throw new IllegalArgumentException("Rule must allow at least one operation");
        }

        for (AclOperation operation : operations) {
            if (operation == AclOperation.ANY || operation == AclOperation.UNKNOWN || operation == AclOperation.ALL) {
                throw new IllegalArgumentException("Rule must use specific operations, not " + operation);
            }
        }

        operations = Collections.unmodifiableSet(withImpliedOperations(operations));
    }

    /**
     * Creates a rule matching all resources of given type
     *
     * @param resourceType  Type of the resource
     * @param operations    Allowed operations
     *
     * @return  New rule
     */
    public static Rule all(ResourceType resourceType, AclOperation... operations) {
        return new Rule(resourceType, PatternType.LITERAL, ResourcePattern.WILDCARD_RESOURCE, Set.of(operations));
    }

    /**
     * Creates a rule matching a single resource with given name
     *
     * @param resourceType  Type of the resource
     * @param name          Name of the resource
     * @param operations    Allowed operations
     *
     * @return  New rule
     */
    public static Rule literal(ResourceType resourceType, String name, AclOperation... operations) {
        return new Rule(resourceType, PatternType.LITERAL, name, Set.of(operations));
    }

    /**
     * Creates a rule matching all resources with names starting with the given prefix
     *
     * @param resourceType  Type of the resource
     * @param prefix        Prefix of the resource name
     * @param operations    Allowed operations
     *
     * @return  New rule
     */
    public static Rule prefixed(ResourceType resourceType, String prefix, AclOperation... operations) {
        return new Rule(resourceType, PatternType.PREFIXED, prefix, Set.of(operations));
    }

    /**
     * Checks if the rule allows the operation on the resource. The resource is expected to be a literal resource as
     * used by the Kafka authorization requests.
     *
     * @param operation         Requested operation
     * @param resourceType      Type of the requested resource
     * @param resourceName      Name of the requested resource
     *
     * @return  True if this rule allows the operation on the resource. False otherwise.
     */
    public boolean allows(AclOperation operation, ResourceType resourceType, String resourceName) {
        return this.resourceType == resourceType
                && operations.contains(operation)
                && matchesName(resourceName);
    }

    /**
     * Checks if this rule allows the operation on at least some resources of given type.
     *
     * @param operation     Requested operation
     * @param resourceType  Type of the requested resource
     *
     * @return  True if this rule allows the operation on at least some resource of given type. False otherwise.
     */
    public boolean allowsAny(AclOperation operation, ResourceType resourceType) {
        return this.resourceType == resourceType
                && operations.contains(operation);
    }

    private boolean matchesName(String resourceName) {
        if (patternType == PatternType.LITERAL) {
            return ResourcePattern.WILDCARD_RESOURCE.equals(name) || name.equals(resourceName);
        } else {
            return resourceName != null && resourceName.startsWith(name);
        }
    }

    /**
     * Adds the operations implied by Kafka.
     *
     * @param operations    Set of operations
     *
     * @return  Set of operations with the implied operations included
     */
    /* test */ static Set<AclOperation> withImpliedOperations(Set<AclOperation> operations) {
        EnumSet<AclOperation> result = EnumSet.copyOf(operations);

        if (result.contains(AclOperation.READ)
                || result.contains(AclOperation.WRITE)
                || result.contains(AclOperation.DELETE)
                || result.contains(AclOperation.ALTER)) {
            result.add(AclOperation.DESCRIBE);
        }

        if (result.contains(AclOperation.ALTER_CONFIGS)) {
            result.add(AclOperation.DESCRIBE_CONFIGS);
        }

        return result;
    }
}
