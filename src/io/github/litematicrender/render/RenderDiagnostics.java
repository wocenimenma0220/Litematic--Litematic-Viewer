package io.github.litematicrender.render;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects everything the renderer could not resolve, so a render can report its own gaps
 * instead of silently drawing a magenta "missing" texture.
 *
 * <p>Counters are keyed by a short reason plus the offending id, which makes both the totals
 * (is this build mostly broken?) and the exact culprits (which block?) visible in one pass.
 */
public final class RenderDiagnostics {
    private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();

    private RenderDiagnostics() {}

    /** Records one occurrence of {@code reason} for {@code id}. */
    public static void note(String reason, String id) {
        COUNTS.computeIfAbsent(reason + " | " + id, k -> new AtomicLong()).incrementAndGet();
    }

    /** Snapshot of all counters, ordered by descending count. */
    public static Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        COUNTS.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
            .forEach(e -> out.put(e.getKey(), e.getValue().get()));
        return out;
    }

    public static long total() {
        return COUNTS.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public static void reset() {
        COUNTS.clear();
    }

    /** Prints the snapshot; returns true when nothing was recorded. */
    public static boolean report(java.io.PrintStream out) {
        Map<String, Long> snap = snapshot();
        if (snap.isEmpty()) return true;
        out.println("!! unresolved render inputs (" + total() + " occurrences)");
        snap.forEach((k, v) -> out.println("   " + v + " x  " + k));
        return false;
    }
}
