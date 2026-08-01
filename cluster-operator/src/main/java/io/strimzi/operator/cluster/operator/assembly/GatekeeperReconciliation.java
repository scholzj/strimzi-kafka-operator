/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.client.CustomResource;
import io.strimzi.api.kafka.model.common.Spec;
import io.strimzi.api.kafka.model.kafka.Status;
import io.strimzi.operator.cluster.operator.VertxUtil;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationException;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.model.StatusUtils;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Helper which wraps a create-or-update reconciliation of a cluster-operator custom resource with the Gatekeeper entry
 * and exit phases. It is used by the individual assembly operators, each of which passes the typed Gatekeeper invoker
 * methods for its resource kind.
 * <p>
 * The entry phase runs before the reconciliation and can mutate the resource or reject the reconciliation by completing
 * its stage exceptionally; the mutated resource is used only within this reconciliation and is never persisted. A
 * rejection is treated as a reconciliation failure: a status carrying the error is built, the exit phase still runs on
 * it and the reconciliation fails so that the status is persisted. The exit phase runs after the reconciliation, whether
 * it succeeded or failed, and can mutate the computed status before it is persisted. If the exit plugins themselves fail
 * (for example a validating plugin rejects the status), the failure is logged and the status computed before the exit
 * phase is used so that it can still be persisted. The exit phase always receives the original (non-mutated) resource,
 * matching the behaviour of the User Operator.
 */
class GatekeeperReconciliation {
    private static final ReconciliationLogger LOGGER = ReconciliationLogger.create(GatekeeperReconciliation.class.getName());

    private GatekeeperReconciliation() { }

    /**
     * Runs a create-or-update reconciliation surrounded by the Gatekeeper entry and exit phases.
     *
     * @param reconciliation    Reconciliation identifier used for logging
     * @param resource          The custom resource being reconciled
     * @param statusSupplier    Supplier of a fresh status, used to build the status carrying the error when the
     *                          reconciliation fails without producing one (for example when the entry phase rejects it)
     * @param entry             The entry phase invoker for the resource kind. It receives the resource and returns the
     *                          possibly mutated resource.
     * @param reconcile         The actual reconciliation logic. It receives the resource returned by the entry phase and
     *                          returns the computed status. On failure it should fail with a {@link ReconciliationException}
     *                          carrying the status which should be persisted.
     * @param exit              The exit phase invoker for the resource kind. It receives the original resource and the
     *                          computed status and returns the possibly mutated status.
     *
     * @param <T>   Type of the custom resource
     * @param <P>   Type of the custom resource spec
     * @param <S>   Type of the custom resource status
     *
     * @return  Future which completes with the status after it passed through the exit phase
     */
    static <T extends CustomResource<P, S>, P extends Spec, S extends Status> Future<S> createOrUpdate(
            Reconciliation reconciliation,
            T resource,
            Supplier<S> statusSupplier,
            Function<T, CompletionStage<T>> entry,
            Function<T, Future<S>> reconcile,
            BiFunction<T, S, CompletionStage<S>> exit
    ) {
        Promise<S> promise = Promise.promise();

        VertxUtil.toFuture(entry.apply(resource))
                .compose(reconcile)
                .onComplete(reconcileResult -> {
                    if (reconcileResult.succeeded()) {
                        // The reconciliation succeeded => run the exit phase on the computed status and complete with it
                        runExit(reconciliation, resource, reconcileResult.result(), exit)
                                .onComplete(promise);
                    } else if (reconcileResult.cause() instanceof ReconciliationException e) {
                        // The reconciliation failed but produced a status => run the exit phase on it, but keep the
                        // reconciliation failure so that the status is persisted and the reconciliation is still failed
                        @SuppressWarnings("unchecked")
                        S status = (S) e.getStatus();
                        runExit(reconciliation, resource, status, exit)
                                .onComplete(exitResult -> promise.fail(new ReconciliationException(exitResult.result(), e.getCause())));
                    } else {
                        // The reconciliation failed without producing a status (for example the entry phase rejected it).
                        // Following the proposal, this is treated as a reconciliation failure: a status carrying the error
                        // is built, the exit phase still runs on it, and the reconciliation fails with a
                        // ReconciliationException so that the status is persisted.
                        S status = statusSupplier.get();
                        StatusUtils.setStatusConditionAndObservedGeneration(resource, status, reconcileResult.cause());
                        runExit(reconciliation, resource, status, exit)
                                .onComplete(exitResult -> promise.fail(new ReconciliationException(exitResult.result(), reconcileResult.cause())));
                    }
                });

        return promise.future();
    }

    /**
     * Runs the exit phase and, if it fails, logs the failure and falls back to the status computed before the exit
     * phase. The returned Future therefore always succeeds.
     */
    private static <T, S extends Status> Future<S> runExit(
            Reconciliation reconciliation,
            T resource,
            S status,
            BiFunction<T, S, CompletionStage<S>> exit
    ) {
        return VertxUtil.toFuture(exit.apply(resource, status))
                .recover(error -> {
                    LOGGER.errorCr(reconciliation, "Gatekeeper exit plugins failed", error);
                    return Future.succeededFuture(status);
                });
    }
}
