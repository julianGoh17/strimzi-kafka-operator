/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.common.AdminClientProvider;
import io.strimzi.operator.common.BackOff;
import io.strimzi.operator.common.DefaultAdminClientProvider;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.resource.PodOperator;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * <p>Manages the rolling restart of a Kafka cluster.</p>
 *
 * <p>The following algorithm is used:</p>
 *
 * <pre>
 *   0. Start with a list of all the pods
 *   1. While the list is non-empty:
 *     2. Take the next pod from the list.
 *     3. Test whether the pod needs to be restarted.
 *         If not then:
 *           i.  Wait for it to be ready.
 *           ii. Continue from 1.
 *     4. Otherwise, check whether the pod is the controller
 *         If so, and there are still pods to be maybe-restarted then:
 *           i.  Reschedule the restart of this pod by appending it the list
 *           ii. Continue from 1.
 *     5. Otherwise, check whether the pod can be restarted without "impacting availability"
 *         If not then:
 *           i.  Reschedule the restart of this pod by appending it the list
 *           ii. Continue from 1.
 *     6. Otherwise:
 *         i.   Restart the pod
 *         ii.  Wait for it to become ready (in the kube sense)
 *         iii. Continue from 1.
 * </pre>
 *
 * <p>Where "impacting availability" is defined by {@link KafkaAvailability}.</p>
 *
 * <p>Note the following important properties of this algorithm:</p>
 * <ul>
 *     <li>if there is a spontaneous change in controller while the rolling restart is happening, any new
 *     controller is still the last pod to be rolled, thus avoid unnecessary controller elections.</li>
 *     <li>rolling should happen without impacting any topic's min.isr.</li>
 *     <li>even pods which aren't candidates for rolling are checked for readiness which partly avoids
 *     successive reconciliations each restarting a pod which never becomes ready</li>
 * </ul>
 */
public class KafkaRoller {

    private static final Logger log = LogManager.getLogger(KafkaRoller.class);

    private final PodOperator podOperations;
    private final long pollingIntervalMs;
    protected final long operationTimeoutMs;
    protected final Vertx vertx;
    private final String cluster;
    private final Secret clusterCaCertSecret;
    private final Secret coKeySecret;
    private final Integer numPods;
    private final Supplier<BackOff> backoffSupplier;
    protected String namespace;
    private final AdminClientProvider adminClientProvider;

    KafkaRoller(Vertx vertx, PodOperator podOperations,
                long pollingIntervalMs, long operationTimeoutMs, Supplier<BackOff> backOffSupplier,
                StatefulSet sts, Secret clusterCaCertSecret, Secret coKeySecret) {
        this(vertx, podOperations, pollingIntervalMs, operationTimeoutMs, backOffSupplier,
                sts, clusterCaCertSecret, coKeySecret, new DefaultAdminClientProvider());
    }

    KafkaRoller(Vertx vertx, PodOperator podOperations,
                long pollingIntervalMs, long operationTimeoutMs, Supplier<BackOff> backOffSupplier,
                StatefulSet sts, Secret clusterCaCertSecret, Secret coKeySecret,
                AdminClientProvider adminClientProvider) {
        this.namespace = sts.getMetadata().getNamespace();
        this.cluster = Labels.cluster(sts);
        this.numPods = sts.getSpec().getReplicas();
        this.backoffSupplier = backOffSupplier;
        this.clusterCaCertSecret = clusterCaCertSecret;
        this.coKeySecret = coKeySecret;
        this.vertx = vertx;
        this.operationTimeoutMs = operationTimeoutMs;
        this.podOperations = podOperations;
        this.pollingIntervalMs = pollingIntervalMs;
        this.adminClientProvider = adminClientProvider;
    }

    /**
     * Returns a Future which completed with the actual pod corresponding to the abstract representation
     * of the given {@code pod}.
     */
    protected Future<Pod> pod(Integer podId) {
        return podOperations.getAsync(namespace, KafkaCluster.kafkaPodName(cluster, podId));
    }

    private final ScheduledExecutorService singleExecutor = Executors.newSingleThreadScheduledExecutor(
        runnable -> new Thread(runnable, "kafka-roller"));

    private ConcurrentHashMap<Integer, RestartContext> podToContext = new ConcurrentHashMap<>();
    private Function<Pod, String> podNeedsRestart;

    /**
     * Asynchronously perform a rolling restart of some subset of the pods,
     * completing the returned Future when rolling is complete.
     * Which pods get rolled is determined by {@code podNeedsRestart}.
     * The pods may not be rolled in id order, due to the {@linkplain KafkaRoller rolling algorithm}.
     * @param podNeedsRestart Predicate for determining whether a pod should be rolled.
     * @return A Future completed when rolling is complete.
     */
    Future<Void> rollingRestart(Function<Pod, String> podNeedsRestart) {
        this.podNeedsRestart = podNeedsRestart;
        List<Future> futures = new ArrayList<>(numPods);
        List<Integer> podIds = new ArrayList<>(numPods);
        for (int podId = 0; podId < numPods; podId++) {
            // Order the podIds unready first otherwise repeated reconciliations might each restart a pod
            // only for it not to become ready and thus drive the cluster to a worse state.
            podIds.add(podOperations.isReady(namespace, podName(podId)) ? podIds.size() : 0, podId);
        }
        log.debug("Initial order for rolling restart {}", podIds);
        for (Integer podId: podIds) {
            futures.add(schedule(podId, 0, TimeUnit.MILLISECONDS));
        }
        Promise<Void> result = Promise.promise();
        CompositeFuture.join(futures).setHandler(ar -> {
            singleExecutor.shutdown();
            vertx.runOnContext(ignored -> result.handle(ar.map((Void) null)));
        });
        return result.future();
    }

    protected static class RestartContext {
        final Promise<Void> promise;
        final BackOff backOff;
        private long connectionErrorStart = 0L;

        RestartContext(Supplier<BackOff> backOffSupplier) {
            promise = Promise.promise();
            backOff = backOffSupplier.get();
            backOff.delayMs();
        }

        public void clearConnectionError() {
            connectionErrorStart = 0L;
        }

        long connectionError() {
            return connectionErrorStart;
        }

        void noteConnectionError() {
            if (connectionErrorStart == 0L) {
                connectionErrorStart = System.currentTimeMillis();
            }
        }

        @Override
        public String toString() {
            return "RestartContext{" +
                    "promise=" + promise +
                    ", backOff=" + backOff +
                    '}';
        }
    }

    /**
     * Schedule the rolling of the given pod at or after the given delay,
     * completed the returned Future when the pod is rolled.
     * When called multiple times with the same podId this method will return the same Future instance.
     * Pods will be rolled one-at-a-time so the delay may be overrun.
     * @param podId The pod to roll.
     * @param delay The delay.
     * @param unit The unit of the delay.
     * @return A future which completes when the pod has been rolled.
     */
    private Future<Void> schedule(int podId, long delay, TimeUnit unit) {
        RestartContext ctx = podToContext.computeIfAbsent(podId,
            k -> new RestartContext(backoffSupplier));
        singleExecutor.schedule(() -> {
            log.debug("Considering restart of pod {} after delay of {} {}", podId, delay, unit);
            try {
                restartIfNecessary(podId, ctx);
                ctx.promise.complete();
            } catch (InterruptedException e) {
                // Let the executor deal with interruption.
                Thread.currentThread().interrupt();
            } catch (FatalProblem e) {
                log.info("Could not restart pod {}, giving up after {} attempts/{}ms",
                        podId, ctx.backOff.maxAttempts(), ctx.backOff.totalDelayMs(), e);
                ctx.promise.fail(e);
                singleExecutor.shutdownNow();
                podToContext.forEachValue(Integer.MAX_VALUE, f -> {
                    f.promise.tryFail(e);
                });
            } catch (Exception e) {
                if (ctx.backOff.done()) {
                    log.info("Could not roll pod {}, giving up after {} attempts/{}ms",
                            podId, ctx.backOff.maxAttempts(), ctx.backOff.totalDelayMs(), e);
                    ctx.promise.fail(e instanceof TimeoutException ?
                            new io.strimzi.operator.common.operator.resource.TimeoutException() :
                            e);
                } else {
                    long delay1 = ctx.backOff.delayMs();
                    log.info("Could not roll pod {} due to {}, retrying after at least {}ms",
                            podId, e, delay1);
                    schedule(podId, delay1, TimeUnit.MILLISECONDS);
                }
            }
        }, delay, unit);
        return ctx.promise.future();
    }

    /**
     * Restart the given pod now if necessary according to {@link #podNeedsRestart}.
     * This method blocks.
     * @param podId The id of the pod to roll.
     * @param restartContext
     * @throws InterruptedException Interrupted while waiting.
     * @throws ForceableProblem Some error. Not thrown when finalAttempt==true.
     * @throws UnforceableProblem Some error, still thrown when finalAttempt==true.
     */
    private void restartIfNecessary(int podId, RestartContext restartContext)
            throws Exception {
        Pod pod;
        try {
            pod = podOperations.get(namespace, KafkaCluster.kafkaPodName(cluster, podId));
        } catch (KubernetesClientException e) {
            throw new UnforceableProblem("Error getting pod " + podName(podId), e);
        }

        String reasonToRestartPod = podNeedsRestart.apply(pod);
        if (reasonToRestartPod != null && !reasonToRestartPod.isEmpty()) {
            log.info("Pod {} needs to be restarted. Reason: {}", podId, reasonToRestartPod);
            Admin adminClient = null;
            try {
                try {
                    adminClient = adminClient(podId);
                    Integer controller = controller(podId, adminClient, operationTimeoutMs, TimeUnit.MILLISECONDS, restartContext);
                    int stillRunning = podToContext.reduceValuesToInt(100, v -> v.promise.future().isComplete() ? 0 : 1,
                            0, Integer::sum);
                    if (controller == podId && stillRunning > 1) {
                        log.debug("Pod {} is controller and there are other pods to roll", podId);
                        throw new ForceableProblem("Pod " + podName(podId) + " is currently the controller and there are other pods still to roll");
                    } else {
                        if (canRoll(adminClient, podId, 60_000, TimeUnit.MILLISECONDS)) {
                            log.debug("Pod {} can be rolled now", podId);
                            restartAndAwaitReadiness(pod, operationTimeoutMs, TimeUnit.MILLISECONDS);
                        } else {
                            log.debug("Pod {} cannot be rolled right now", podId);
                            throw new UnforceableProblem("Pod " + podName(podId) + " is currently not rollable");
                        }
                    }
                } finally {
                    closeLoggingAnyError(adminClient);
                }
            } catch (ForceableProblem e) {
                if (restartContext.backOff.done() || e.forceNow) {
                    log.warn("Pod {} will be force-rolled", podName(podId));
                    restartAndAwaitReadiness(pod, operationTimeoutMs, TimeUnit.MILLISECONDS);
                } else {
                    throw e;
                }
            }
        } else {
            // By testing even pods which don't need restart for readiness we prevent successive reconciliations
            // from taking out a pod each time (due, e.g. to a configuration error).
            // We rely on Kube to try restarting such pods.
            log.debug("Pod {} does not need to be restarted", podId);
            log.debug("Waiting for non-restarted pod {} to become ready", podId);
            await(isReady(namespace, KafkaCluster.kafkaPodName(cluster, podId)), operationTimeoutMs, TimeUnit.MILLISECONDS, e -> new FatalProblem("Error while waiting for non-restarted pod " + podName(podId) + " to become ready", e));
            log.debug("Pod {} is now ready", podId);
        }
    }

    private void closeLoggingAnyError(Admin adminClient) {
        if (adminClient != null) {
            try {
                adminClient.close(Duration.ofMinutes(2));
            } catch (Exception e) {
                log.warn("Ignoring exception when closing admin client", e);
            }
        }
    }

    /** Exceptions which we're prepared to ignore (thus forcing a restart) in some circumstances. */
    static final class ForceableProblem extends Exception {
        final boolean forceNow;
        ForceableProblem(String msg) {
            this(msg, null);
        }

        ForceableProblem(String msg, Throwable cause) {
            this(msg, cause, false);
        }

        ForceableProblem(String msg, boolean forceNow) {
            this(msg, null, forceNow);
        }

        ForceableProblem(String msg, Throwable cause, boolean forceNow) {
            super(msg, cause);
            this.forceNow = forceNow;
        }
    }

    /** Exceptions which we're prepared to ignore in the final attempt */
    static final class UnforceableProblem extends Exception {
        UnforceableProblem(String msg) {
            this(msg, null);
        }
        UnforceableProblem(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /** Immediately aborts rolling */
    static final class FatalProblem extends Exception {
        FatalProblem(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    private boolean canRoll(Admin adminClient, int podId, long timeout, TimeUnit unit)
            throws ForceableProblem, InterruptedException {
        return await(availability(adminClient).canRoll(podId), timeout, unit,
            t -> new ForceableProblem("An error while trying to determine rollability", t));
    }

    /**
     * Synchronously restart the given pod
     * by deleting it and letting it be recreated by K8s, then synchronously wait for it to be ready.
     * @param pod The Pod to restart.
     * @param timeout The timeout.
     * @param unit The timeout unit.
     */
    private void restartAndAwaitReadiness(Pod pod, long timeout, TimeUnit unit)
            throws InterruptedException, UnforceableProblem, FatalProblem {
        String podName = pod.getMetadata().getName();
        log.debug("Rolling pod {}", podName);
        await(restart(pod), timeout, unit, e -> new UnforceableProblem("Error while trying to restart pod " + podName + " to become ready", e));
        log.debug("Waiting for restarted pod {} to become ready", podName);
        await(isReady(pod), timeout, unit, e -> new FatalProblem("Error while waiting for restarted pod " + podName + " to become ready", e));
        log.debug("Pod {} is now ready", podName);
    }

    /**
     * Block waiting for up to the given timeout for the given Future to complete, returning its result.
     * @param future The future to wait for.
     * @param timeout The timeout
     * @param unit The timeout unit
     * @param exceptionMapper A function for rethrowing exceptions.
     * @param <T> The result type
     * @param <E> The exception type
     * @return The result of of the future
     * @throws E The exception type returned from {@code exceptionMapper}.
     * @throws TimeoutException If the given future is not completed before the timeout.
     * @throws InterruptedException If the waiting was interrupted.
     */
    private static <T, E extends Exception> T await(Future<T> future, long timeout, TimeUnit unit,
                                            Function<Throwable, E> exceptionMapper)
            throws E, InterruptedException {
        CompletableFuture<T> cf = new CompletableFuture<>();
        future.setHandler(ar -> {
            if (ar.succeeded()) {
                cf.complete(ar.result());
            } else {
                cf.completeExceptionally(ar.cause());
            }
        });
        try {
            return cf.get(timeout, unit);
        } catch (ExecutionException e) {
            throw exceptionMapper.apply(e.getCause());
        } catch (TimeoutException e) {
            throw exceptionMapper.apply(e);
        }
    }

    /**
     * Asynchronously delete the given pod, return a Future which completes when the Pod has been recreated.
     * Note: The pod might not be "ready" when the returned Future completes.
     * @param pod The pod to be restarted
     * @return a Future which completes when the Pod has been recreated
     */
    protected Future<Void> restart(Pod pod) {
        return podOperations.restart("Rolling update of " + namespace + "/" + KafkaCluster.kafkaClusterName(cluster), pod, operationTimeoutMs);
    }

    /**
     * Returns an AdminClient instance bootstrapped from the given pod.
     */
    protected Admin adminClient(Integer podId) throws ForceableProblem {
        try {
            String hostname = KafkaCluster.podDnsName(this.namespace, this.cluster, podName(podId)) + ":" + KafkaCluster.REPLICATION_PORT;
            log.debug("Creating AdminClient for {}", hostname);
            return adminClientProvider.createAdminClient(hostname, this.clusterCaCertSecret, this.coKeySecret, "cluster-operator");
        } catch (RuntimeException e) {
            throw new ForceableProblem("An error while try to create an admin client for pod " + podName(podId), e);
        }
    }

    protected KafkaAvailability availability(Admin ac) {
        return new KafkaAvailability(ac);
    }

    String podName(Integer podId) {
        return KafkaCluster.kafkaPodName(this.cluster, podId);
    }

    /**
     * Completes the returned future <strong>on the context thread</strong> with the id of the controller of the cluster.
     * This will be -1 if there is not currently a controller.
     * @param ac The AdminClient
     * @return A future which completes the the node id of the controller of the cluster,
     * or -1 if there is not currently a controller.
     */
    int controller(int podId, Admin ac, long timeout, TimeUnit unit, RestartContext restartContext) throws Exception {
        Node controllerNode = null;
        try {
            DescribeClusterResult describeClusterResult = ac.describeCluster();
            KafkaFuture<Node> controller = describeClusterResult.controller();
            controllerNode = controller.get(timeout, unit);
            restartContext.clearConnectionError();
        } catch (ExecutionException e) {
            maybeTcpProbe(podId, e, restartContext);
        } catch (TimeoutException e) {
            maybeTcpProbe(podId, e, restartContext);
        }
        int id = controllerNode == null || Node.noNode().equals(controllerNode) ? -1 : controllerNode.id();
        log.debug("controller is {}", id);
        return id;
    }

    /**
     * If we've already had trouble connecting to this broker try to probe whether the connection is
     * open on the broker; if it's not then maybe throw a ForceableProblem to immediately force a restart.
     * This is an optimization for brokers which don't seem to be running.
     */
    private void maybeTcpProbe(int podId, Exception executionException, RestartContext restartContext) throws Exception {
        if (restartContext.connectionError() + numPods * 120_000L >= System.currentTimeMillis()) {
            try {
                log.debug("Probing TCP port due to previous problems connecting to pod {}", podId);
                // do a tcp connect and close (with a short connect timeout)
                tcpProbe(podName(podId), KafkaCluster.REPLICATION_PORT);
            } catch (IOException connectionException) {
                throw new ForceableProblem("Unable to connect to " + podName(podId) + ":" + KafkaCluster.REPLICATION_PORT, executionException.getCause(), true);
            }
            throw executionException;
        } else {
            restartContext.noteConnectionError();
            throw new ForceableProblem("Error while trying to determine the cluster controller from pod " + podName(podId), executionException.getCause());
        }
    }
    /**
     * Tries to open and close a TCP connection to the given host and port.
     * @param hostname The host
     * @param port The port
     * @throws IOException if anything went wrong.
     */
    void tcpProbe(String hostname, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(hostname, port), 5_000);
        } finally {
            socket.close();
        }
    }

    @Override
    public String toString() {
        return podToContext.toString();
    }

    protected Future<Void> isReady(Pod pod) {
        return isReady(pod.getMetadata().getNamespace(), pod.getMetadata().getName());
    }

    protected Future<Void> isReady(String namespace, String podName) {
        return podOperations.readiness(namespace, podName, pollingIntervalMs, operationTimeoutMs)
            .recover(error -> {
                log.warn("Error waiting for pod {}/{} to become ready: {}", namespace, podName, error);
                return Future.failedFuture(error);
            });
    }

}
