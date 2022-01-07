/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.SecurityContextBuilder;

/**
 * Shared methods for managing Pod and Container security contexts
 */
public class SecurityContextUtils {
    private static final Long DEFAULT_FS_GROUPID = 0L;

    /**
     * Builds PodSecurityContext.
     *  - If user configured custom pod security context, it will be always prefered
     *  - On OpenShift, we expect OpenShift to inject its own security context
     *  - If persistent storage is used, we set the filesystem group
     *  - If ephemeral storage is used, we do not need to configure anything special
     *
     * @param userSecurityContext   User-configured pod security context. Should be null if user didn't configured it
     * @param usesPersistentStorage Flag indicating if persistent storage is used
     * @param isOpenShift           Flag indicating if we are on OpenShift
     *
     * @return                      Pod Security Context which should be used
     */
    public static PodSecurityContext podSecurityContext(PodSecurityContext userSecurityContext, boolean usesPersistentStorage, boolean isOpenShift)    {
        if (userSecurityContext == null
                && usesPersistentStorage
                && !isOpenShift) {
            // Persistent storage is used, we are not on OpenShift and user didn't configure pod-security-context
            return new PodSecurityContextBuilder()
                    .withFsGroup(DEFAULT_FS_GROUPID)
                    .build();
        } else {
            // Persistent storage is not used, or we are on OpenShift, or user configured a custom context
            // We can always return the user security context since it is either set and is preferred or it is null
            return userSecurityContext;
        }
    }

    public static SecurityContext containerSecurityContext(SecurityContext userSecurityContext)    {
        if (userSecurityContext == null)    {
            return new SecurityContextBuilder()
                    .withAllowPrivilegeEscalation(false)
                    .withRunAsNonRoot(true)
                    .withNewSeccompProfile()
                        .withType("RuntimeDefault")
                    .endSeccompProfile()
                    .withNewCapabilities()
                        .withDrop("ALL")
                    .endCapabilities()
                    .build();
        } else {
            return userSecurityContext;
        }
    }
}
