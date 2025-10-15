package de.cjdev.renderra.subtitle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SRTLoader {

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(\\d+):(\\d+):(\\d+),(\\d+)");

    public static NavigableMap<Long, Subtitle> parseSRT(Path path) throws IOException {
        NavigableMap<Long, Subtitle> subtitles = new TreeMap<>();
        List<String> lines = Files.readAllLines(path);
        int i = 0;
        while (i < lines.size()) {
            String indexLine = lines.get(i++).trim();
            if (indexLine.isEmpty()) continue;

            String timeLine = lines.get(i++).trim();
            String[] parts = timeLine.split(" --> ");
            long start = parseTime(parts[0]);
            long end = parseTime(parts[1]);

            StringBuilder text = new StringBuilder();
            while (i < lines.size() && !lines.get(i).trim().isEmpty()) {
                text.append(lines.get(i++)).append("\n");
            }

            String plain = text.toString().trim();

            boolean renderAtTop = plain.startsWith("{\\an8}");
            if (renderAtTop) plain = plain.substring(6);
            subtitles.put(start, new Subtitle(new SimpleMiniMessage(plain).parse()));
            subtitles.put(end, null);
            i++; // skip empty line
        }
        return subtitles;
    }

    private static long parseTime(String time) {
        Matcher m = TIME_PATTERN.matcher(time);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int m_ = Integer.parseInt(m.group(2));
            int s = Integer.parseInt(m.group(3));
            int ms = Integer.parseInt(m.group(4));
            return h * 3600000L + m_ * 60000L + s * 1000L + ms;
        }
        throw new IllegalArgumentException("Invalid time format: " + time);
    }

    public static Map.Entry<Long, Subtitle> getSubtitleAt(NavigableMap<Long, Subtitle> map, long timeMs) {
        var entry = map.floorEntry(timeMs);
        return entry == null ? null : entry.getValue() == null ? null : entry;
    }
}
