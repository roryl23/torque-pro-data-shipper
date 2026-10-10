package com.roryl23.torqueshipper.torque;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Decodes only well-framed OBD-II DTC bytes; the caller retains the original ECU responses. */
final class DtcParser {
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");

    private DtcParser() { }

    static List<String> decode(String[] responses, int mode) {
        return parse(responses, mode).codes;
    }

    static boolean hasModeResponse(String[] responses, int mode) {
        return parse(responses, mode).hasResponse;
    }

    static final class Result {
        final List<String> codes;
        final boolean hasResponse;
        final boolean ambiguous;

        Result(List<String> codes, boolean hasResponse, boolean ambiguous) {
            this.codes = codes;
            this.hasResponse = hasResponse;
            this.ambiguous = ambiguous;
        }
    }

    static Result parse(String[] responses, int mode) {
        Set<String> codes = new LinkedHashSet<>();
        boolean found = false;
        boolean ambiguous = false;
        if (responses == null) return new Result(new ArrayList<>(codes), false, false);
        Map<String, Assembly> pending = new HashMap<>();
        int marker = mode + 0x40;
        for (String response : responses) {
            if (response == null) continue;
            for (String line : response.split("[\\r\\n]+")) {
                Frame frame = frame(line);
                List<Integer> data = frame.data;
                if (data.isEmpty()) continue;
                int first = data.get(0);
                if ((first >= 0x40 && first <= 0x5f)
                        || (first <= 0x0f && data.size() > 1
                        && data.get(1) >= 0x40 && data.get(1) <= 0x5f)
                        || (first >= 0x10 && first <= 0x1f)) {
                    if (pending.remove(frame.ecu) != null) ambiguous = true;
                }
                if (first == marker) {
                    found = true;
                    ambiguous |= addCodes(data.subList(1, data.size()), codes, mode, false);
                } else if (first <= 0x0f && data.size() > 1 && data.get(1) == marker
                        && first > 0 && data.size() - 1 >= first) {
                    // ISO-TP single frame: PCI length includes the response mode byte.
                    found = true;
                    ambiguous |= addCodes(data.subList(2, first + 1), codes, mode, true);
                } else if (first >= 0x10 && first <= 0x1f && data.size() > 2
                        && data.get(2) == marker) {
                    if (frame.ecu.isEmpty()) {
                        ambiguous = true; // Cannot associate continuation frames with an ECU.
                        continue;
                    }
                    int expected = ((first & 15) << 8) | data.get(1);
                    if (expected < 2) continue;
                    Assembly assembly = new Assembly(expected);
                    assembly.append(data, 2);
                    if (assembly.complete()) {
                        found = true;
                        ambiguous |= addCodes(assembly.payload(), codes, mode, true);
                    } else pending.put(frame.ecu, assembly);
                } else if (first >= 0x20 && first <= 0x2f) {
                    Assembly assembly = pending.get(frame.ecu);
                    if (assembly == null) continue;
                    if (first != assembly.nextFrame) {
                        pending.remove(frame.ecu); // Incomplete/misaligned: do not invent DTCs.
                        continue;
                    }
                    assembly.nextFrame = first == 0x2f ? 0x20 : first + 1;
                    assembly.append(data, 1);
                    if (assembly.complete()) {
                        found = true;
                        ambiguous |= addCodes(assembly.payload(), codes, mode, true);
                        pending.remove(frame.ecu);
                    }
                }
                // A different service (e.g. 0x47) cannot be a mode-03 response even
                // if its payload contains byte 0x43.
            }
        }
        return new Result(new ArrayList<>(codes), found, ambiguous || !pending.isEmpty());
    }

    private static final class Assembly {
        private final int expected;
        private final List<Integer> bytes = new ArrayList<>();
        private int nextFrame = 0x21;

        Assembly(int expected) { this.expected = expected; }

        void append(List<Integer> source, int from) {
            for (int i = from; i < source.size() && bytes.size() < expected; i++) {
                bytes.add(source.get(i));
            }
        }

        boolean complete() { return bytes.size() == expected; }

        List<Integer> payload() { return bytes.subList(1, bytes.size()); }
    }

    private static final class Frame {
        final String ecu;
        final List<Integer> data;

        Frame(String ecu, List<Integer> data) {
            this.ecu = ecu;
            this.data = data;
        }
    }

    private static Frame frame(String line) {
        List<Integer> bytes = new ArrayList<>();
        String ecu = "";
        for (String part : line.trim().split("[\\s:,;]+")) {
            if (!HEX.matcher(part).matches()) continue;
            int from = 0;
            if (bytes.isEmpty() && ecu.isEmpty()) {
                // 11-bit CAN address, with or without a space before the ISO-TP bytes.
                if (part.length() >= 3 && part.regionMatches(true, 0, "7E", 0, 2)
                        && ((part.length() & 1) != 0)) {
                    ecu = part.substring(0, 3);
                    from = 3;
                } else if (part.length() >= 8 && part.regionMatches(true, 0, "18DA", 0, 4)) {
                    ecu = part.substring(0, 8);
                    from = 8;
                }
            }
            if (((part.length() - from) & 1) != 0) continue;
            for (int i = from; i < part.length(); i += 2) {
                bytes.add(Integer.parseInt(part.substring(i, i + 2), 16));
            }
        }
        return new Frame(ecu, bytes);
    }

    /** Returns true if the DTC layout was ambiguous and no bytes were decoded. */
    private static boolean addCodes(List<Integer> bytes, Set<String> codes, int mode, boolean framed) {
        int offset = 0;
        int size = bytes.size();
        if (size == 1 && bytes.get(0) == 0) return false; // Empty DTC response.
        if ((size & 1) != 0) {
            // ISO-15765 Mode 03 includes a count byte; legacy protocols do not.
            // Modes 07/0A and headerless odd-length replies are left raw.
            if (!framed || mode != 3 || size < 1 || bytes.get(0) * 2 != size - 1) {
                return true;
            }
            offset = 1;
        }
        for (int i = offset; i + 1 < size; i += 2) {
            int first = bytes.get(i);
            int second = bytes.get(i + 1);
            if (first == 0 && second == 0) continue;
            char family = "PCBU".charAt(first >>> 6);
            codes.add(String.format(java.util.Locale.ROOT, "%c%d%X%02X", family,
                    (first >>> 4) & 3, first & 15, second));
        }
        return false;
    }
}
