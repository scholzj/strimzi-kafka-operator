/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.network.ListenerName;
import org.apache.kafka.common.security.auth.KafkaPrincipal;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration of the Strimzi Metaauthorizer. The options are read from the Kafka broker configuration and use the
 * {@code strimzi.metaauthorizer.} prefix.
 */
public final class MetaAuthorizerConfig {
    /**
     * Prefix used by all Metaauthorizer configuration options
     */
    public static final String PREFIX = "strimzi.metaauthorizer.";

    /**
     * Comma-separated list of the names of the internal listeners
     */
    public static final String LISTENERS = PREFIX + "listeners";

    /**
     * Class name of the user-configured authorizer to which the decisions are delegated
     */
    public static final String AUTHORIZER_CLASS_NAME = PREFIX + "authorizer.class.name";

    /**
     * Comma-separated list of the topics used by Cruise Control
     */
    public static final String CRUISE_CONTROL_TOPICS = PREFIX + "cruisecontrol.topics";

    /**
     * Default topics used by Cruise Control in Strimzi
     */
    public static final String DEFAULT_CRUISE_CONTROL_TOPICS = "strimzi.cruisecontrol.metrics,strimzi.cruisecontrol.modeltrainingsamples,strimzi.cruisecontrol.partitionmetricsamples";

    /* test */ static final ConfigDef CONFIG_DEF = createConfigDef();

    private final Set<String> internalListeners;
    private final String authorizerClassName;
    private final Map<Component, KafkaPrincipal> users;
    private final Set<String> cruiseControlTopics;

    /**
     * Parses and validates the Metaauthorizer configuration
     *
     * @param configs   Kafka broker configuration
     *
     * @throws ConfigException  When the configuration is invalid
     */
    public MetaAuthorizerConfig(Map<String, ?> configs) {
        Map<String, Object> parsed = CONFIG_DEF.parse(configs);

        this.internalListeners = parseListeners(parsed);
        this.authorizerClassName = parseAuthorizerClassName(parsed);
        this.users = parseUsers(parsed);
        this.cruiseControlTopics = parseList(parsed, CRUISE_CONTROL_TOPICS);
    }

    private static ConfigDef createConfigDef() {
        ConfigDef def = new ConfigDef()
                .define(LISTENERS,
                        ConfigDef.Type.LIST,
                        ConfigDef.Importance.HIGH,
                        "Comma-separated list of the names of the internal listeners (e.g. CONTROLPLANE-9090,REPLICATION-9091). " +
                                "On these listeners, the internal users are authorized using the hardcoded rules.")
                .define(AUTHORIZER_CLASS_NAME,
                        ConfigDef.Type.STRING,
                        null,
                        ConfigDef.Importance.HIGH,
                        "Class name of the authorizer to which the requests of all other users are delegated. When not " +
                                "set, these requests are allowed.")
                .define(CRUISE_CONTROL_TOPICS,
                        ConfigDef.Type.LIST,
                        DEFAULT_CRUISE_CONTROL_TOPICS,
                        ConfigDef.Importance.LOW,
                        "Comma-separated list of the topics used by Cruise Control.");

        for (Component component : Component.values()) {
            def.define(component.userConfigKey(),
                    ConfigDef.Type.STRING,
                    null,
                    ConfigDef.Importance.HIGH,
                    "Principal of the " + component + " component (e.g. User:CN=my-cluster-kafka,O=io.strimzi). When " +
                            "not set, the component is not authorized using the hardcoded rules.");
        }

        return def;
    }

    private static Set<String> parseListeners(Map<String, Object> parsed) {
        Set<String> listeners = new LinkedHashSet<>();

        for (String listener : parseList(parsed, LISTENERS)) {
            // Kafka normalizes the listener names to upper case. We do the same so that the names can be compared directly.
            listeners.add(ListenerName.normalised(listener).value());
        }

        if (listeners.isEmpty()) {
            throw new ConfigException(LISTENERS, parsed.get(LISTENERS), "At least one internal listener has to be configured");
        }

        return Collections.unmodifiableSet(listeners);
    }

    private static String parseAuthorizerClassName(Map<String, Object> parsed) {
        String className = (String) parsed.get(AUTHORIZER_CLASS_NAME);

        if (className == null || className.isBlank()) {
            return null;
        }

        className = className.trim();

        if (MetaAuthorizer.class.getName().equals(className)) {
            throw new ConfigException(AUTHORIZER_CLASS_NAME, className, "The Metaauthorizer cannot delegate to itself");
        }

        return className;
    }

    private static Map<Component, KafkaPrincipal> parseUsers(Map<String, Object> parsed) {
        Map<Component, KafkaPrincipal> users = new EnumMap<>(Component.class);

        for (Component component : Component.values()) {
            String value = (String) parsed.get(component.userConfigKey());

            if (value != null && !value.isBlank()) {
                users.put(component, parsePrincipal(component.userConfigKey(), value.trim()));
            }
        }

        return Collections.unmodifiableMap(users);
    }

    private static Set<String> parseList(Map<String, Object> parsed, String key) {
        Set<String> result = new LinkedHashSet<>();

        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) parsed.get(key);
        if (values != null) {
            for (String value : values) {
                if (!value.isBlank()) {
                    result.add(value.trim());
                }
            }
        }

        return Collections.unmodifiableSet(result);
    }

    /**
     * Parses the principal in the format used by Kafka ({@code <type>:<name>}, e.g. {@code User:my-user})
     *
     * @param key       Name of the configuration option (used in the error message)
     * @param value     The principal
     *
     * @return  Parsed principal
     */
    /* test */ static KafkaPrincipal parsePrincipal(String key, String value) {
        int separator = value.indexOf(':');

        if (separator <= 0 || separator == value.length() - 1) {
            throw new ConfigException(key, value, "The principal has to be in the format <type>:<name> (e.g. User:my-user)");
        }

        return new KafkaPrincipal(value.substring(0, separator), value.substring(separator + 1));
    }

    /**
     * Returns set with the names of the internal listeners (normalized to upper case)
     *
     * @return  Set with the names of the internal listeners (normalized to upper case)
     */
    public Set<String> internalListeners() {
        return internalListeners;
    }

    /**
     * Returns class name of the user-configured authorizer or null if not configured
     *
     * @return  Class name of the user-configured authorizer or null if not configured
     */
    public String authorizerClassName() {
        return authorizerClassName;
    }

    /**
     * Returns map with the principals of the configured internal components
     *
     * @return  Map with the principals of the configured internal components
     */
    public Map<Component, KafkaPrincipal> users() {
        return users;
    }

    /**
     * Returns set with the names of the Cruise Control topics
     *
     * @return  Set with the names of the Cruise Control topics
     */
    public Set<String> cruiseControlTopics() {
        return cruiseControlTopics;
    }

    @Override
    public String toString() {
        return "MetaAuthorizerConfig(" +
                "internalListeners=" + internalListeners +
                ", authorizerClassName=" + authorizerClassName +
                ", users=" + users +
                ", cruiseControlTopics=" + cruiseControlTopics +
                ")";
    }
}
