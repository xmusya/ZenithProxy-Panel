package dev.zenith.md3panel.web;

import com.zenith.event.console.ConsoleLogEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.EVENT_BUS;

/**
 * Ring buffer of console log output for the panel's log viewer.
 * Mirrors the official WebAPI's CircularLogQueue.
 */
public class LogBuffer {
    public record LogLine(long index, String ansi) { }
    public record LogsResponse(long baseIndex, long nextIndex, List<LogLine> lines) { }

    private final ArrayDeque<LogLine> lines = new ArrayDeque<>();
    private final int maxEntries;
    private long totalAppended = 0;
    private volatile BiConsumer<Long, String> sink;

    public LogBuffer(final int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        EVENT_BUS.subscribe(
            this,
            of(ConsoleLogEvent.class, this::onConsoleLog)
        );
    }

    /**
     * Called for every new log line; wired to the WebSocket broadcaster.
     */
    public void setSink(final BiConsumer<Long, String> sink) {
        this.sink = sink;
    }

    private void onConsoleLog(final ConsoleLogEvent event) {
        long index;
        synchronized (this) {
            index = totalAppended++;
            lines.addLast(new LogLine(index, event.ansi()));
            while (lines.size() > maxEntries) {
                lines.removeFirst();
            }
        }
        var s = sink;
        if (s != null) {
            try {
                s.accept(index, event.ansi());
            } catch (final Exception ignored) {
                // never break the logback appender thread
            }
        }
    }

    /**
     * Returns all retained lines with index >= max(from, baseIndex).
     */
    public synchronized LogsResponse snapshot(final long from) {
        long baseIndex = totalAppended - lines.size();
        long safeFrom = Math.max(from, baseIndex);
        var collected = new ArrayList<LogLine>();
        for (LogLine line : lines) {
            if (line.index() >= safeFrom) {
                collected.add(line);
            }
        }
        return new LogsResponse(baseIndex, totalAppended, collected);
    }
}
