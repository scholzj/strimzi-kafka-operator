/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RuleTest {
    @Test
    public void testImpliedOperations() {
        assertEquals(Set.of(AclOperation.READ, AclOperation.DESCRIBE), Rule.withImpliedOperations(Set.of(AclOperation.READ)));
        assertEquals(Set.of(AclOperation.WRITE, AclOperation.DESCRIBE), Rule.withImpliedOperations(Set.of(AclOperation.WRITE)));
        assertEquals(Set.of(AclOperation.DELETE, AclOperation.DESCRIBE), Rule.withImpliedOperations(Set.of(AclOperation.DELETE)));
        assertEquals(Set.of(AclOperation.ALTER, AclOperation.DESCRIBE), Rule.withImpliedOperations(Set.of(AclOperation.ALTER)));
        assertEquals(Set.of(AclOperation.ALTER_CONFIGS, AclOperation.DESCRIBE_CONFIGS), Rule.withImpliedOperations(Set.of(AclOperation.ALTER_CONFIGS)));
        assertEquals(Set.of(AclOperation.CREATE), Rule.withImpliedOperations(Set.of(AclOperation.CREATE)));
        assertEquals(Set.of(AclOperation.DESCRIBE), Rule.withImpliedOperations(Set.of(AclOperation.DESCRIBE)));
        assertEquals(Set.of(AclOperation.CLUSTER_ACTION), Rule.withImpliedOperations(Set.of(AclOperation.CLUSTER_ACTION)));
    }

    @Test
    public void testWildcardRule() {
        Rule rule = Rule.all(ResourceType.TOPIC, AclOperation.READ);

        assertTrue(rule.allows(AclOperation.READ, ResourceType.TOPIC, "my-topic"));
        assertTrue(rule.allows(AclOperation.DESCRIBE, ResourceType.TOPIC, "my-topic"));
        assertFalse(rule.allows(AclOperation.WRITE, ResourceType.TOPIC, "my-topic"));
        assertFalse(rule.allows(AclOperation.READ, ResourceType.GROUP, "my-topic"));
    }

    @Test
    public void testLiteralRule() {
        Rule rule = Rule.literal(ResourceType.TOPIC, "my-topic", AclOperation.WRITE);

        assertTrue(rule.allows(AclOperation.WRITE, ResourceType.TOPIC, "my-topic"));
        assertFalse(rule.allows(AclOperation.WRITE, ResourceType.TOPIC, "my-topic2"));
        assertFalse(rule.allows(AclOperation.WRITE, ResourceType.TOPIC, "my-"));
        assertFalse(rule.allows(AclOperation.WRITE, ResourceType.TOPIC, "*"));
    }

    @Test
    public void testPrefixedRule() {
        Rule rule = Rule.prefixed(ResourceType.GROUP, "my-", AclOperation.READ);

        assertTrue(rule.allows(AclOperation.READ, ResourceType.GROUP, "my-group"));
        assertTrue(rule.allows(AclOperation.READ, ResourceType.GROUP, "my-"));
        assertFalse(rule.allows(AclOperation.READ, ResourceType.GROUP, "your-group"));
        assertFalse(rule.allows(AclOperation.READ, ResourceType.GROUP, null));
    }

    @Test
    public void testAllowsAny() {
        Rule rule = Rule.literal(ResourceType.TOPIC, "my-topic", AclOperation.WRITE);

        assertTrue(rule.allowsAny(AclOperation.WRITE, ResourceType.TOPIC));
        assertTrue(rule.allowsAny(AclOperation.DESCRIBE, ResourceType.TOPIC));
        assertFalse(rule.allowsAny(AclOperation.READ, ResourceType.TOPIC));
        assertFalse(rule.allowsAny(AclOperation.WRITE, ResourceType.GROUP));
    }

    @Test
    public void testInvalidRules() {
        assertThrows(IllegalArgumentException.class, () -> Rule.all(ResourceType.ANY, AclOperation.READ));
        assertThrows(IllegalArgumentException.class, () -> Rule.all(ResourceType.UNKNOWN, AclOperation.READ));
        assertThrows(IllegalArgumentException.class, () -> Rule.all(ResourceType.TOPIC));
        assertThrows(IllegalArgumentException.class, () -> Rule.all(ResourceType.TOPIC, AclOperation.ALL));
        assertThrows(IllegalArgumentException.class, () -> Rule.all(ResourceType.TOPIC, AclOperation.ANY));
        assertThrows(IllegalArgumentException.class, () -> new Rule(ResourceType.TOPIC, PatternType.MATCH, "*", Set.of(AclOperation.READ)));
    }
}
