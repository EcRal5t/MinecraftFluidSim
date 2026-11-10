package mmm.client;

/** Viewport allocation and paging for the diagnostic HUD. */
final class DebugLayout {
    record Page(int start, int end, int scroll) { }
    static java.util.List<String> wrap(String text, int width, java.util.function.BiFunction<String, Integer, String> fit) {
        var lines = new java.util.ArrayList<String>();
        for (String paragraph : text.split("\\R", -1)) {
            while (!paragraph.isEmpty()) {
                String part = fit.apply(paragraph, Math.max(1, width));
                if (part.isEmpty()) break;
                if (part.length() < paragraph.length() && !Character.isWhitespace(paragraph.charAt(part.length()))) {
                    int lastSpace = part.lastIndexOf(' ');
                    if (lastSpace > 0) part = part.substring(0, lastSpace);
                }
                lines.add(part);
                paragraph = paragraph.substring(part.length()).stripLeading();
            }
        }
        return lines;
    }
    static int readingLimit(int max, int localCount, boolean columns, boolean expanded, boolean hasEvents) {
        int reserve = hasEvents ? Math.min(3, Math.max(0, max - localCount)) : 0;
        int available = Math.max(0, max - reserve - (columns ? 0 : localCount));
        return expanded ? available : Math.min(4, available);
    }
    static Page page(int size, int capacity, int scroll) {
        int bounded = Math.min(Math.max(0, scroll), Math.max(0, size - capacity));
        int end = size - bounded;
        return new Page(Math.max(0, end - capacity), end, bounded);
    }
}
