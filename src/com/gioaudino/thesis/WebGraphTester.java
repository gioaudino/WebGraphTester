package com.gioaudino.thesis;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.sux4j.bits.SparseRank;
import it.unimi.dsi.util.XoRoShiRo128PlusRandomGenerator;
import it.unimi.dsi.webgraph.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;

public class WebGraphTester {
    private static final String RESULT_EXTENSION = ".secresult";
    private static final XoRoShiRo128PlusRandomGenerator random = new XoRoShiRo128PlusRandomGenerator(0);
    private static final int[] samples = {100000};
    private final static DecimalFormatSymbols formatSymbols;
    private final static DecimalFormat df3;
    private static final int WARMUP_RUNS = 3;
    private static String GRAPH;

    static {
        formatSymbols = new DecimalFormatSymbols();
        formatSymbols.setDecimalSeparator('.');
        formatSymbols.setGroupingSeparator(',');
        df3 = new DecimalFormat("#,###.##", formatSymbols);
    }

    public static void main(String... args) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {

        if (args.length < 3) {
            System.err.println("USAGE: " + WebGraphTester.class.getName() + " <class> <basename> <destination>");
            System.exit(1);
        }
        final String className = "it.unimi.dsi.webgraph." + args[0];
        final Class classToRun = Class.forName(className);

        final String basename = args[1];
        GRAPH = new File(basename).getName();
        final String dest = args[2];
        final String suffix = Suffix.getSuffix(className);
        final Instant start = Instant.now();
        long time_0, time_1;

        final PrintWriter resultPrintWriter = new PrintWriter(new FileWriter(dest + suffix + RESULT_EXTENSION));
        resultPrintWriter.println("class=" + className);

//        log("Testing compression using " + classToRun.getSimpleName(), classToRun);
//
//        log("Loading " + basename + " graph", classToRun);
//        ImmutableGraph sourceGraph = ImmutableGraph.loadOffline(basename);
//        ArrayListMutableGraph sourceMutableGraph = new ArrayListMutableGraph(sourceGraph);
//        log("Graph loaded", classToRun);
//        log();
//
//        resultPrintWriter.println("nodes=" + sourceGraph.numNodes());
//        resultPrintWriter.println("arcs=" + sourceGraph.numArcs());
//
//        log("Testing compression time", classToRun);
//        Method store = classToRun.getMethod("store", ImmutableGraph.class, CharSequence.class);
//
//        time_0 = System.nanoTime();
//        store.invoke(null, sourceMutableGraph.immutableView(), dest + suffix);
//        time_1 = System.nanoTime();
//
//        log("Compression completed: " + df3.format((double) (time_1 - time_0) / 1000000) + "ms", classToRun);
//        resultPrintWriter.println("compression_time=" + df3.format((double) (time_1 - time_0) / 1000000) + "ms");
//        log();

        log("Reloading graph in " + Class.forName(className).getSimpleName() + " format", classToRun);
        ImmutableGraph graph = ImmutableGraph.load(dest + suffix);
        log("Graph loaded", classToRun);
        double bpe = new File(dest + suffix + ".graph").length() * 8;
        try {
            bpe += new File(dest + suffix + ".fst").length() * 8;
        } catch (NullPointerException e) {
        }
        bpe /= (double) graph.numArcs();
        resultPrintWriter.println("bitsperlink=" + df3.format(bpe));
        log();
        log("Analysing sequential scan", classToRun);
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

        log("Analysis completed: " + df3.format((double) (time_1 - time_0) / graph.numArcs()) + "ns/link", classToRun);

        resultPrintWriter.println("sequential_scan_time=" + df3.format((double) (time_1 - time_0) / graph.numArcs()));

        log();
        log("Analysing list scan with randomly selected nodes", classToRun);
        Random warmup = new Random(0);

        // WARM-UP RUNS
        for (int run = 0; run < WARMUP_RUNS; run++) {
            final int sample = Arrays.stream(samples).max().getAsInt();
            int nextIndex = 0;
            int useless = 0;
            long time = 0;
            double numarcs = 0;
            for (int i = 0; i < sample; i++) {
                int node = warmup.nextInt(graph.numNodes()), next;
                LazyIntIterator successors;
                time -= System.nanoTime();
                successors = graph.successors(node);
                while ((next = successors.nextInt()) != -1)
                    useless ^= next;
                time += System.nanoTime();
                numarcs += graph.outdegree(node);
            }
            final double average = time / numarcs;
        }
        //END OF WARM-UP
        log("End of warm-up runs for random access test", classToRun);

        for (int sample : samples) {
            int useless = 0;
            long time = 0;
            double numarcs = 0;
            for (int i = 0; i < sample; i++) {
                int node = (int) ((random.nextLong() & -1L >>> 1) % graph.numNodes()), next;
                LazyIntIterator successors;
                time -= System.nanoTime();
                successors = graph.successors(node);
                while ((next = successors.nextInt()) != -1)
                    useless ^= next;
                time += System.nanoTime();
                numarcs += graph.outdegree(node);
            }
            final double avg = time / numarcs;
            resultPrintWriter.println("random_" + sample + "_avg=" + df3.format(avg));

            System.out.println("random_" + sample + "_avg: " + df3.format(avg) + " ns/link");
        }
        log("Analysis completed", classToRun);
        log();
        log("Analysing list scan with proportionally selected random nodes", classToRun);
        ProportionalSelector selector = new ProportionalSelector(graph.outdegrees(), graph.numNodes(), graph.numArcs(), warmup);

        // WARM-UP RUNS
        for (int run = 0; run < WARMUP_RUNS; run++) {
            final int sample = Arrays.stream(samples).max().getAsInt();
            IntIterator randomNodes = selector.generateRandom(sample, true);

            double numarcs = 0;
            long time = 0;
            int useless = 0;
            for (int i = 0; i < sample; i++) {
                int node = randomNodes.nextInt(), next;
                LazyIntIterator successors;
                time -= System.nanoTime();
                successors = graph.successors(node);
                while ((next = successors.nextInt()) != -1)
                    useless ^= next;
                time += System.nanoTime();
                numarcs += graph.outdegree(node);
            }
            final double average = time / numarcs;
        }
        // END OF WARM-UP

        log("End of warm-up runs for proportionally selected random access test", classToRun);

        for (int sample : samples) {
            IntIterator randomNodes = selector.generateRandom(sample, false);

            double numarcs = 0;
            long time = 0;
            int useless = 0;
            for (int i = 0; i < sample; i++) {
                int node = randomNodes.nextInt(), next;
                LazyIntIterator successors;
                time -= System.nanoTime();
                successors = graph.successors(node);
                while ((next = successors.nextInt()) != -1)
                    useless ^= next;
                time += System.nanoTime();
                numarcs += graph.outdegree(node);
            }
            final double avg = time / numarcs;
            resultPrintWriter.println("proportionally_random_" + sample + "_avg=" + df3.format(avg));

            System.out.println("proportionally_random_" + sample + "_avg: " + df3.format(avg) + " ns/link");
        }

        log("Analysis completed", classToRun);
        log();
        log("Analysing adjacency", classToRun);

        //WARM-UP RUNS
        for (int run = 0; run < WARMUP_RUNS; run++) {
            final int sample = Arrays.stream(samples).max().getAsInt();
            int[] randomNodes = new int[2 * sample];
            for (int i = 0; i < sample; i++) {
                randomNodes[i] = warmup.nextInt(graph.numNodes());
            }
            long time = 0;
            boolean useless = false;
            for (int i = 0; i < 2 * sample; i += 2) {
                time -= System.nanoTime();
                useless ^= adj(graph, randomNodes[i], randomNodes[1 + i]);
                time += System.nanoTime();
            }
            final double avg = time / (double) sample;
        }
        // END OF WARM-UP
        log("End of warm-up runs for adjacency test", classToRun);

        for (int sample : samples) {
            int[] randomNodes = new int[2 * sample];
            for (int i = 0; i < sample; i++) {
                randomNodes[i] = (int) ((random.nextLong() & -1L >>> 1) % graph.numNodes());
            }
            long time = 0;
            boolean useless = false;
            for (int i = 0; i < 2 * sample; i += 2) {
                time -= System.nanoTime();
                useless ^= adj(graph, randomNodes[i], randomNodes[1 + i]);
                time += System.nanoTime();
            }
            final double avg = time / (double) sample;
            resultPrintWriter.println("adjacency_" + sample + "_avg=" + df3.format(avg));

            System.out.println("adjacency_" + sample + "_avg: " + df3.format(avg) + " ns/link");
        }
        log("Analysis completed", classToRun);
        log();
        log("Completed compression analysis of graph " + basename + " into format " + Class.forName(className).getSimpleName(), classToRun);
        log("Started at: " + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.ITALY).withZone(ZoneId.systemDefault()).format(start), classToRun);
        log();
        log();
        resultPrintWriter.close();
    }

    private static boolean adj(ImmutableGraph graph, int x, int y) {
        if (graph instanceof PEFGraph) {
            return ((PEFGraph) graph).adj(x, y);
        }
        LazyIntIterator iterator = graph.successors(x);
        if (iterator instanceof LazyIntSkippableIterator) return ((LazyIntSkippableIterator) iterator).skipTo(y) == y;
        int n;
        while ((n = iterator.nextInt()) != -1) {
            if (n == y) return true;
            if (n > y) return false;
        }
        return false;
    }

    private static void log() {
        System.out.println();
    }

    private static void log(String message, Class classToRun) {
        System.out.println(getReadableInstant() + " | " + (GRAPH != null ? (GRAPH + " | ") : "") + classToRun.getSimpleName() + " | " + message);
    }

    private static String getReadableInstant() {
        Instant now = Instant.now();
        DateTimeFormatter dtf = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.ITALY).withZone(ZoneId.systemDefault());
        return dtf.format(now);
    }

    private static class ProportionalSelector {
        private final long[] prefixedSum;
        private final long arcs;
        private final int nodes;
        private IntList list;
        SparseRank rank;
        Random warmup;

        ProportionalSelector(IntIterator values, int nodes, long arcs, Random generator) {
            this.arcs = arcs;
            this.prefixedSum = new long[nodes + 1];
            this.prefixedSum[0] = 0;
            int next = 1;
            while (values.hasNext()) {
                this.prefixedSum[next] = this.prefixedSum[next - 1] + values.nextInt();
                next++;
            }
            this.warmup = generator;
            this.rank = new SparseRank(arcs + 1, nodes + 1, LongArrayList.wrap(this.prefixedSum).iterator());
            this.nodes = nodes;
        }

        IntIterator generateRandom(int length, boolean warmup) {
            list = new IntArrayList(length);
            long[] randomValues = new long[length];
            for (int i = 0; i < length; i++) {
                randomValues[i] = warmup ? this.warmup.nextInt(nodes) : ((random.nextLong() & -1L >>> 1) % this.arcs);
            }

            for (int i = 0; i < length; i++) {
                list.add((int) rank.rank(randomValues[i]));
            }
            return list.iterator();
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
