package com.blacktunnel;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

public final class SimpleLog {
    private static final int MAX = 300;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private SimpleLog() {}

    public static synchronized void clear() {
        LINES.clear();
    }

    public static synchronized void i(String msg) {
        String line = TS.format(new Date()) + "  " + msg;
        LINES.addLast(line);
        while (LINES.size() > MAX) {
            LINES.removeFirst();
        }
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : LINES) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
