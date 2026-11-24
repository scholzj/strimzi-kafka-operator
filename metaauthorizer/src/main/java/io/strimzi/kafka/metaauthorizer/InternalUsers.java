/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer;

import org.apache.kafka.common.security.auth.KafkaPrincipal;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Lookup of the internal users based on the principal of the request. The lookup is done by the principal type and
 * name, so that it works also with KafkaPrincipal subclasses created by custom principal builders. It does not
 * allocate any objects.
 */
final class InternalUsers {
    // Principal type -> Principal name -> Internal user
    private final Map<String, Map<String, InternalUser>> users;

    /**
     * Creates the internal users from the configuration
     *
     * @param config    Metaauthorizer configuration
     */
    InternalUsers(MetaAuthorizerConfig config) {
        // Group the components by principal first, so that the principals shared by multiple components get the union
        // of their rules.
        Map<KafkaPrincipal, Set<Component>> componentsByPrincipal = new LinkedHashMap<>();
        config.users().forEach((component, principal) ->
                componentsByPrincipal.computeIfAbsent(principal, p -> EnumSet.noneOf(Component.class)).add(component));

        Map<String, Map<String, InternalUser>> lookup = new HashMap<>();
        componentsByPrincipal.forEach((principal, components) ->
                lookup.computeIfAbsent(principal.getPrincipalType(), t -> new HashMap<>())
                        .put(principal.getName(), new InternalUser(principal.toString(), components, config.cruiseControlTopics())));

        this.users = lookup;
    }

    /**
     * Finds the internal user for the principal
     *
     * @param principal     Principal of the request
     *
     * @return  The internal user or null if the principal does not belong to any internal user
     */
    InternalUser find(KafkaPrincipal principal) {
        if (principal == null) {
            return null;
        }

        Map<String, InternalUser> byName = users.get(principal.getPrincipalType());
        return byName != null ? byName.get(principal.getName()) : null;
    }
}
