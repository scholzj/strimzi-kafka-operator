/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer.benchmarks;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the JMH benchmarks and prints a table comparing the baseline (StandardAuthorizer with super-users) with the
 * Metaauthorizer. It accepts the regular JMH command line options (e.g. {@code -p aclCount=1000 -t 8}). When not
 * specified, it uses 1 fork, 3 warmup and 5 measurement iterations of 1 second each, and enables the GC profiler to
 * measure the allocations.
 */
public class BenchmarkRunner {
    private static final String ALLOCATION_RESULT = "gc.alloc.rate.norm";

    /**
     * Runs the benchmarks
     *
     * @param args  JMH command line arguments
     *
     * @throws Exception    When the benchmark fails
     */
    public static void main(String[] args) throws Exception {
        CommandLineOptions cmd = new CommandLineOptions(args);
        ChainedOptionsBuilder builder = new OptionsBuilder().parent(cmd);

        if (cmd.getIncludes().isEmpty()) {
            builder.include(BenchmarkRunner.class.getPackageName() + ".");
        }

        if (!cmd.getForkCount().hasValue()) {
            builder.forks(1);
        }

        if (!cmd.getWarmupIterations().hasValue()) {
            builder.warmupIterations(3);
        }

        if (!cmd.getWarmupTime().hasValue()) {
            builder.warmupTime(TimeValue.seconds(1));
        }

        if (!cmd.getMeasurementIterations().hasValue()) {
            builder.measurementIterations(5);
        }

        if (!cmd.getMeasurementTime().hasValue()) {
            builder.measurementTime(TimeValue.seconds(1));
        }

        boolean gcProfilerEnabled = cmd.getProfilers().stream()
                .anyMatch(p -> "gc".equals(p.getKlass()) || GCProfiler.class.getName().equals(p.getKlass()));
        if (!gcProfilerEnabled) {
            builder.addProfiler(GCProfiler.class);
        }

        Collection<RunResult> results = new Runner(builder.build()).run();

        System.out.println();
        System.out.println(comparisonTable(results));
    }

    /**
     * Comparison of the baseline and Metaauthorizer results with the same benchmark and parameters
     */
    private static final class Comparison {
        RunResult baseline;
        RunResult metaauthorizer;
    }

    /**
     * Creates the comparison table
     *
     * @param results   Benchmark results
     *
     * @return  The comparison table as a String
     */
    /* test */ static String comparisonTable(Collection<RunResult> results) {
        Map<String, Comparison> comparisons = new LinkedHashMap<>();

        for (RunResult result : results) {
            BenchmarkParams params = result.getParams();
            String key = key(params);
            Comparison comparison = comparisons.computeIfAbsent(key, k -> new Comparison());

            if (AuthorizerSetup.BASELINE.equals(params.getParam("authorizer"))) {
                comparison.baseline = result;
            } else {
                comparison.metaauthorizer = result;
            }
        }

        List<String> lines = new ArrayList<>();
        String format = "%-75s %14s %14s %9s %12s %12s";
        lines.add("Comparison of the StandardAuthorizer with super-users (baseline) and the Metaauthorizer delegating to the StandardAuthorizer");
        lines.add(String.format(format, "Benchmark (parameters, threads)", "Baseline ns/op", "Meta ns/op", "Diff", "Base B/op", "Meta B/op"));

        comparisons.forEach((key, comparison) -> {
            if (comparison.baseline != null && comparison.metaauthorizer != null) {
                double baseline = comparison.baseline.getPrimaryResult().getScore();
                double meta = comparison.metaauthorizer.getPrimaryResult().getScore();

                lines.add(String.format(format,
                        key,
                        String.format("%.1f ± %.1f", baseline, comparison.baseline.getPrimaryResult().getScoreError()),
                        String.format("%.1f ± %.1f", meta, comparison.metaauthorizer.getPrimaryResult().getScoreError()),
                        String.format("%+.1f%%", (meta - baseline) / baseline * 100),
                        allocation(comparison.baseline),
                        allocation(comparison.metaauthorizer)));
            }
        });

        lines.add("Diff is the change of the average time per operation (negative = Metaauthorizer is faster).");
        lines.add("Throughput per thread (ops/s) = 1e9 / (ns/op).");

        return String.join(System.lineSeparator(), lines);
    }

    private static String key(BenchmarkParams params) {
        String benchmark = params.getBenchmark();
        // Use only ClassName.method
        String[] parts = benchmark.split("\\.");
        StringBuilder key = new StringBuilder(parts[parts.length - 2] + "." + parts[parts.length - 1]);

        List<String> otherParams = new ArrayList<>();
        for (String param : params.getParamsKeys()) {
            if (!"authorizer".equals(param)) {
                otherParams.add(param + "=" + params.getParam(param));
            }
        }

        key.append(" (").append(String.join(", ", otherParams)).append(", t=").append(params.getThreads()).append(")");
        return key.toString();
    }

    private static String allocation(RunResult result) {
        for (Map.Entry<String, Result> entry : result.getSecondaryResults().entrySet()) {
            if (entry.getKey().endsWith(ALLOCATION_RESULT)) {
                return String.format("%.1f", entry.getValue().getScore());
            }
        }

        return "n/a";
    }
}
