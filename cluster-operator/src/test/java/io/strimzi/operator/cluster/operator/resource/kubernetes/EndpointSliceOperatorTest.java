/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource.kubernetes;

import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.DiscoveryAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.V1DiscoveryAPIGroupDSL;
import io.strimzi.operator.common.operator.resource.kubernetes.AbstractReadyResourceOperatorTest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EndpointSliceOperatorTest extends AbstractReadyResourceOperatorTest<KubernetesClient, EndpointSlice, EndpointSliceList, Resource<EndpointSlice>> {
    @Override
    protected Class<KubernetesClient> clientType() {
        return KubernetesClient.class;
    }

    @Override
    protected Class<Resource> resourceType() {
        return Resource.class;
    }

    @Override
    protected EndpointSlice resource(String name) {
        return new EndpointSliceBuilder()
                .withNewMetadata()
                    .withNamespace(NAMESPACE)
                    .withName(name)
                .endMetadata()
                .build();
    }

    @Override
    protected EndpointSlice modifiedResource(String name) {
        return new EndpointSliceBuilder()
                .withNewMetadata()
                    .withNamespace(NAMESPACE)
                    .withName(name)
                    .addToLabels("foo", "bar")
                .endMetadata()
                .build();
    }

    @Override
    protected void mocker(KubernetesClient mockClient, MixedOperation<EndpointSlice, EndpointSliceList, Resource<EndpointSlice>> op) {
        V1DiscoveryAPIGroupDSL mockV1 = mock(V1DiscoveryAPIGroupDSL.class);
        when(mockV1.endpointSlices()).thenReturn(op);

        DiscoveryAPIGroupDSL mockDiscovery = mock(DiscoveryAPIGroupDSL.class);
        when(mockDiscovery.v1()).thenReturn(mockV1);

        when(mockClient.discovery()).thenReturn(mockDiscovery);
    }

    @Override
    protected EndpointSliceOperator createResourceOperations(KubernetesClient mockClient) {
        return new EndpointSliceOperator(asyncExecutor, mockClient);
    }
}
