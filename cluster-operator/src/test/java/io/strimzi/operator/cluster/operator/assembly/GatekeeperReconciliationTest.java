/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeBuilder;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeStatus;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationException;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Unit test for the {@link GatekeeperReconciliation} helper which wraps the create-or-update reconciliation of the
 * KafkaBridge, KafkaMirrorMaker2 and KafkaRebalance operators with the Gatekeeper entry and exit phases. Because the
 * helper takes the entry, reconcile and exit steps as plain functions, the whole mechanism can be exercised directly
 * without the operators or the plugin factory. Every step here completes synchronously, so the returned Future is
 * already completed when the call returns.
 */
public class GatekeeperReconciliationTest {
    private static final Reconciliation RECONCILIATION = new Reconciliation("test", "KafkaBridge", "my-namespace", "my-resource");

    private static KafkaBridge resource(String name) {
        return new KafkaBridgeBuilder()
                .withNewMetadata()
                    .withNamespace("my-namespace")
                    .withName(name)
                .endMetadata()
                .build();
    }

    @Test
    public void testEntryMutationIsPassedToTheReconciliation() {
        KafkaBridge original = resource("original");
        AtomicReference<KafkaBridge> reconciledWith = new AtomicReference<>();

        Future<KafkaBridgeStatus> result = GatekeeperReconciliation.createOrUpdate(
                RECONCILIATION,
                original,
                KafkaBridgeStatus::new,
                r -> CompletableFuture.completedFuture(new KafkaBridgeBuilder(r).editMetadata().withName("mutated-by-entry").endMetadata().build()),
                r -> {
                    reconciledWith.set(r);
                    return Future.succeededFuture(new KafkaBridgeStatus());
                },
                (r, status) -> CompletableFuture.completedFuture(status));

        assertThat(result.succeeded(), is(true));
        // The reconciliation must have received the resource mutated by the entry phase, not the original
        assertThat(reconciledWith.get().getMetadata().getName(), is("mutated-by-entry"));
    }

    @Test
    public void testExitMutatesTheStatusOnSuccess() {
        Future<KafkaBridgeStatus> result = GatekeeperReconciliation.createOrUpdate(
                RECONCILIATION,
                resource("original"),
                KafkaBridgeStatus::new,
                CompletableFuture::completedFuture,
                r -> Future.succeededFuture(new KafkaBridgeStatus()),
                (r, status) -> {
                    status.setUrl("set-by-exit");
                    return CompletableFuture.completedFuture(status);
                });

        assertThat(result.succeeded(), is(true));
        assertThat(result.result().getUrl(), is("set-by-exit"));
    }

    @Test
    public void testExitReceivesTheOriginalResourceNotTheEntryMutatedOne() {
        KafkaBridge original = resource("original");
        AtomicReference<KafkaBridge> exitReceived = new AtomicReference<>();

        GatekeeperReconciliation.createOrUpdate(
                RECONCILIATION,
                original,
                KafkaBridgeStatus::new,
                r -> CompletableFuture.completedFuture(new KafkaBridgeBuilder(r).editMetadata().withName("mutated-by-entry").endMetadata().build()),
                r -> Future.succeededFuture(new KafkaBridgeStatus()),
                (r, status) -> {
                    exitReceived.set(r);
                    return CompletableFuture.completedFuture(status);
                });

        assertThat(exitReceived.get(), is(sameInstance(original)));
    }

    @Test
    public void testExitRunsOnFailureAndTheReconciliationRemainsFailed() {
        RuntimeException cause = new RuntimeException("boom");
        KafkaBridgeStatus failedStatus = new KafkaBridgeStatus();

        Future<KafkaBridgeStatus> result = GatekeeperReconciliation.createOrUpdate(
                RECONCILIATION,
                resource("original"),
                KafkaBridgeStatus::new,
                CompletableFuture::completedFuture,
                r -> Future.failedFuture(new ReconciliationException(failedStatus, cause)),
                (r, status) -> {
                    status.setUrl("set-by-exit-on-failure");
                    return CompletableFuture.completedFuture(status);
                });

        // The reconciliation stays failed, but the exit phase still mutated the status carried by the exception and the
        // original cause is preserved
        assertThat(result.failed(), is(true));
        assertThat(result.cause(), is(instanceOf(ReconciliationException.class)));
        ReconciliationException e = (ReconciliationException) result.cause();
        assertThat(((KafkaBridgeStatus) e.getStatus()).getUrl(), is("set-by-exit-on-failure"));
        assertThat(e.getCause(), is(sameInstance(cause)));
    }

    @Test
    public void testExitPluginFailureKeepsThePreExitStatus() {
        KafkaBridgeStatus computedStatus = new KafkaBridgeStatus();
        computedStatus.setUrl("computed");

        Future<KafkaBridgeStatus> result = GatekeeperReconciliation.createOrUpdate(
                RECONCILIATION,
                resource("original"),
                KafkaBridgeStatus::new,
                CompletableFuture::completedFuture,
                r -> Future.succeededFuture(computedStatus),
                (r, status) -> CompletableFuture.failedFuture(new RuntimeException("exit plugin failed")));

        // The exit phase failed => the reconciliation still succeeds and the status computed before the exit phase is used
        assertThat(result.succeeded(), is(true));
        assertThat(result.result(), is(sameInstance(computedStatus)));
        assertThat(result.result().getUrl(), is("computed"));
    }

    @Test
    public void testRejectingEntryPluginFailsTheReconciliationButStillRunsExit() {
        RuntimeException rejection = new RuntimeException("rejected");
        AtomicBoolean reconcileInvoked = new AtomicBoolean(false);
        AtomicBoolean exitInvoked = new AtomicBoolean(false);

        Future<KafkaBridgeStatus> result = GatekeeperReconciliation.createOrUpdate(
                RECONCILIATION,
                resource("original"),
                KafkaBridgeStatus::new,
                r -> CompletableFuture.failedFuture(rejection),
                r -> {
                    reconcileInvoked.set(true);
                    return Future.succeededFuture(new KafkaBridgeStatus());
                },
                (r, status) -> {
                    exitInvoked.set(true);
                    status.setUrl("set-by-exit-on-entry-failure");
                    return CompletableFuture.completedFuture(status);
                });

        // The entry rejection is treated as a reconciliation failure: the reconciliation is skipped, but the exit phase
        // still runs on a status carrying the error and the reconciliation fails with a ReconciliationException so that
        // the status is persisted
        assertThat("reconciliation must not run when the entry phase rejects", reconcileInvoked.get(), is(false));
        assertThat("exit phase must still run on an entry rejection", exitInvoked.get(), is(true));
        assertThat(result.failed(), is(true));
        assertThat(result.cause(), is(instanceOf(ReconciliationException.class)));
        ReconciliationException e = (ReconciliationException) result.cause();
        assertThat(((KafkaBridgeStatus) e.getStatus()).getUrl(), is("set-by-exit-on-entry-failure"));
        assertThat(e.getCause(), is(sameInstance(rejection)));
    }

    @Test
    public void testFailureWithoutAStatusBuildsAStatusAndRunsExit() {
        RuntimeException cause = new RuntimeException("unexpected");
        AtomicBoolean exitInvoked = new AtomicBoolean(false);

        Future<KafkaBridgeStatus> result = GatekeeperReconciliation.createOrUpdate(
                RECONCILIATION,
                resource("original"),
                KafkaBridgeStatus::new,
                CompletableFuture::completedFuture,
                r -> Future.failedFuture(cause),
                (r, status) -> {
                    exitInvoked.set(true);
                    return CompletableFuture.completedFuture(status);
                });

        // A failure which does not carry a status (not a ReconciliationException) is still treated as a reconciliation
        // failure: a status is built, the exit phase runs on it, and the reconciliation fails with a ReconciliationException
        assertThat(exitInvoked.get(), is(true));
        assertThat(result.failed(), is(true));
        assertThat(result.cause(), is(instanceOf(ReconciliationException.class)));
        ReconciliationException e = (ReconciliationException) result.cause();
        assertThat(e.getStatus(), is(not(nullValue())));
        assertThat(e.getCause(), is(sameInstance(cause)));
    }
}
