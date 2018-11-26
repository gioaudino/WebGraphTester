package com.gioaudino.thesis;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.webgraph.ArrayListMutableGraph;
import it.unimi.dsi.webgraph.ImmutableGraph;
import it.unimi.dsi.webgraph.LazyIntIterator;
import it.unimi.dsi.webgraph.NodeIterator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.stream.LongStream;

public class WebGraphTester {

    private static final String RESULT_EXTENSION = ".result";

    private static final int[] samples = {10, 100, 1000, 10000};
    private final static DecimalFormatSymbols formatSymbols;
    private final static DecimalFormat df3;
    private static String GRAPH;

    static {
        formatSymbols = new DecimalFormatSymbols();
        formatSymbols.setDecimalSeparator('.');
        formatSymbols.setGroupingSeparator(',');
        df3 = new DecimalFormat("#,###.###", formatSymbols);
    }

    public static void main(String... args) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (args.length < 3) {
            System.err.println("USAGE: " + WebGraphTester.class.getName() + " <class> <basename> <destination>");
            System.exit(1);
        }
        final String className = "it.unimi.dsi.webgraph." + args[0];

        try{
            Class.forName(className);
        }catch(ClassNotFoundException e){
            System.err.println("Cannot find class " + args[0]);
            System.exit(2);
        }

        final String basename = args[1];
        GRAPH = new File(basename).getName();
        final String dest = args[2];
        final String suffix = Suffix.getSuffix(className);
        final Instant start = Instant.now();
        long time_0, time_1;

        final PrintWriter resultPrintWriter = new PrintWriter(new FileWriter(dest + suffix + RESULT_EXTENSION));
        resultPrintWriter.println("class=" + className);

        log("Testing compression using " + Class.forName(className).getSimpleName());

        log("Loading " + basename + " graph");
        ImmutableGraph sourceGraph = ImmutableGraph.loadOffline(basename);
        ArrayListMutableGraph sourceMutableGraph = new ArrayListMutableGraph(sourceGraph);
        log("Graph loaded");
        log();

        resultPrintWriter.println("nodes=" + sourceGraph.numNodes());
        resultPrintWriter.println("arcs=" + sourceGraph.numArcs());

        log("Testing compression time");
        Method store = Class.forName(className).getMethod("store", ImmutableGraph.class, CharSequence.class);

        time_0 = System.nanoTime();
        store.invoke(null, sourceMutableGraph.immutableView(), dest + suffix);
        time_1 = System.nanoTime();

        log("Compression completed: " + df3.format((double) (time_1 - time_0) / 1000000) + "ms");
        resultPrintWriter.println("compression_time=" + df3.format((double) (time_1 - time_0) / 1000000) + "ms");
        log();
        log("Reloading graph in " + Class.forName(className).getSimpleName() + " format");
        ImmutableGraph graph = ImmutableGraph.load(dest + suffix);
        log("Graph loaded");
        log();
        log("Analysing sequential scan");
        NodeIterator nodeIterator = graph.nodeIterator();
        int keep = 0;

        time_0 = System.nanoTime();

        while (nodeIterator.hasNext()) {
            nodeIterator.nextInt();
            LazyIntIterator successorsIterator = nodeIterator.successors();
            int s;
            while ((s = successorsIterator.nextInt()) != -1)
                keep ^= s;
        }

        time_1 = System.nanoTime();
        final int ignored = keep;

        log("Analysis completed: " + df3.format((double) (time_1 - time_0) / 1000000) + "ms");
        log("Analysis completed: " + df3.format((double) (time_1 - time_0) / graph.numArcs()) + "ns/link");
        log("Analysis completed: " + df3.format((double) graph.numArcs() / (1000000 * (time_1 - time_0))) + "link/s");

        resultPrintWriter.println("sequential_scan_time_abs=" + df3.format((double) (time_1 - time_0) / 1000000) + "ms");
        resultPrintWriter.println("sequential_scan_time_nspl=" + df3.format((double) (time_1 - time_0) / graph.numArcs()) + "ns/link");
        resultPrintWriter.println("sequential_scan_time_abs=" + df3.format((double) graph.numArcs() / (1000000 * (time_1 - time_0))) + "link/s");

        log();
        log("Analysing list scan with randomly selected nodes");
        for (int sample : samples) {
            long[] times = new long[sample];
            int[] randomNodes = new Random().ints(sample, 0, graph.numNodes()).toArray();
            for (int i = 0; i < sample; i++) {
                int node = randomNodes[i], next;
                LazyIntIterator successors;
                time_0 = System.nanoTime();
                successors = graph.successors(node);
                while ((next = successors.nextInt()) != -1)
                    keep ^= next;
                time_1 = System.nanoTime();
                final int useless = keep;
                times[i] = time_1 - time_0;
            }
            log(analyseLongArray("random_" + sample, times, resultPrintWriter));
        }
        log("Analysis completed");
        log();
        log("Analysing list scan with proportionally selected random nodes");
        ProportionalSelector selector = new ProportionalSelector(graph.outdegrees(), graph.numNodes(), graph.numArcs());

        for (int sample : samples) {
            IntIterator randomNodes = selector.generateRandom(sample);
            long[] times = new long[sample];
            for (int i = 0; i < sample; i++) {
                int node = randomNodes.nextInt(), next;
                LazyIntIterator successors;
                time_0 = System.nanoTime();
                successors = graph.successors(node);
                while ((next = successors.nextInt()) != -1)
                    keep ^= next;
                time_1 = System.nanoTime();
                final int useless = keep;
                times[i] = time_1 - time_0;
            }
            log(analyseLongArray("proportionally_random_" + sample, times, resultPrintWriter));
        }

        log("Analysis completed");
        log();
        log("Analysing adjacency");


        for (int sample : samples) {
            int[] randomNodes = new Random().ints(2 * sample, 0, graph.numNodes()).toArray();
            long[] times = new long[sample];
            for (int i = 0; i < sample; i++) {
                time_0 = System.nanoTime();
                final boolean res = adj(graph, randomNodes[i], randomNodes[sample + i]);
                time_1 = System.nanoTime();
                times[i] = time_1 - time_0;
            }
            log(analyseLongArray("adjacency_" + sample, times, resultPrintWriter));
        }
        log("Analysis completed");
        log();
        log("Completed compression analysis of graph " + basename + " into format " + Class.forName(className).getSimpleName());
        log("Started at: " + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.ITALY).withZone(ZoneId.systemDefault()).format(start));
        log();
        log();
        resultPrintWriter.close();
    }

    private static boolean adj(ImmutableGraph graph, int x, int y) {
        LazyIntIterator iterator = graph.successors(x);
        int n;
        while ((n = iterator.nextInt()) != -1) {
            if (n == y) return true;
        }
        return false;
    }

    private static String analyseLongArray(String header, long[] times, PrintWriter writer) {
        LongStream timesStream = Arrays.stream(times);
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        double sum = 0.0;
        for (long time : times) {
            sum += time;
            if (time < min) min = time;
            if (time > max) max = time;
        }
        final double mean = sum / times.length;
        double variance = timesStream.mapToDouble(el -> (mean - el) * (mean - el)).sum() / (times.length - 1);
        double stddev = Math.sqrt(variance);

        writer.println(header + "_avg=" + df3.format(mean) + "ns");
        writer.println(header + "_min=" + df3.format(min) + "ns");
        writer.println(header + "_max=" + df3.format(max) + "ns");
        writer.println(header + "_variance=" + df3.format(variance));
        writer.println(header + "_stddev=" + df3.format(stddev));

        return header + " avg: " + df3.format(mean) + " ns | stddev: " + df3.format(stddev);

    }

    private static void log() {
        System.out.println();
    }

    private static void log(String message) {
        System.out.println(getReadableInstant() + " | " + (GRAPH != null ? (GRAPH + " | ") : "") + PEFGraphTester.class.getSimpleName() + " | " + message);
    }

    private static String getReadableInstant() {
        Instant now = Instant.now();
        DateTimeFormatter dtf = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.ITALY).withZone(ZoneId.systemDefault());
        return dtf.format(now);
    }

    private static class ProportionalSelector {
        private final long[] prefixedSum;
        private final long arcs;
        private IntList list;

        ProportionalSelector(IntIterator values, int nodes, long arcs) {
            this.arcs = arcs;
            this.prefixedSum = new long[nodes];
            this.prefixedSum[0] = values.nextInt();
            int next = 1;
            while (values.hasNext())
                this.prefixedSum[next] = this.prefixedSum[next++ - 1] + values.nextInt();
        }

        IntIterator generateRandom(int length) {
            list = new IntArrayList(length);
            long[] randomValues = new Random().longs(length, 0, this.arcs).toArray();
            Arrays.stream(randomValues).forEach(value -> list.add(getIndex(value)));
            return list.iterator();
        }

        private int getIndex(long value) {
            return getIndex(value, 0, prefixedSum.length);
        }

        private int getIndex(long value, int left, int right) {
            int mid = (right + left) / 2;
            if (prefixedSum[mid] == value || prefixedSum[mid] > value && prefixedSum[mid - 1] < value) return mid;
            if (value < prefixedSum[mid]) return getIndex(value, left, mid);
            return getIndex(value, mid, right);
        }
    }

    private static class Suffix {
        private final static String BVGRAPH_SUFFIX = "-bv";
        private final static String EFGRAPH_SUFFIX = "-ef";
        private final static String PEFGRAPH_SUFFIX = "-pef";
        private final static Map<String, String> suffixMap;

        static {
            suffixMap = new HashMap<>();
            suffixMap.put("BVGraph", BVGRAPH_SUFFIX);
            suffixMap.put("EFGraph", EFGRAPH_SUFFIX);
            suffixMap.put("PEFGraph", PEFGRAPH_SUFFIX);
        }

        public static String getSuffix(String className) {
            try {
                return suffixMap.getOrDefault(Class.forName(className).getSimpleName(), "-na");
            } catch (ClassNotFoundException e) {
                return "-na";
            }
        }
    }
}
