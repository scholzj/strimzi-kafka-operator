/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.plugins.securitycontext;

import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.SecurityContext;

public interface SecurityContextPlugin {
    void configure();

    PodSecurityContext zooKeeperPodSecurityContext(PodSecurityContext userSecurityContext);
    SecurityContext zooKeeperContainerSecurityContext(SecurityContext userSecurityContext);

    PodSecurityContext kafkaPodSecurityContext(PodSecurityContext userSecurityContext);
    SecurityContext kafkaContainerSecurityContext(SecurityContext userSecurityContext);

    PodSecurityContext kafkaInitPodSecurityContext(PodSecurityContext userSecurityContext);
    SecurityContext kafkaInitContainerSecurityContext(SecurityContext userSecurityContext);


}
