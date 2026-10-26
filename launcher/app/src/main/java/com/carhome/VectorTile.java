package com.carhome;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts of a Mapbox Vector Tile the map draws: geometry and the "class" attribute. The protobuf
 * wire format is read by hand, without a protobuf library.
 */
final class VectorTile {

    static final int POINT = 1;
    static final int LINESTRING = 2;
    static final int POLYGON = 3;

    static final class Feature {
        final int type;
        final String kind;
        /** Rings or lines; each holds x0, y0, x1, y1, ... in tile units (0..extent). */
        final List<int[]> parts;

        Feature(int type, String kind, List<int[]> parts) {
            this.type = type;
            this.kind = kind;
            this.parts = parts;
        }
    }

    static final class Layer {
        final String name;
        final int extent;
        final List<Feature> features;

        Layer(String name, int extent, List<Feature> features) {
            this.name = name;
            this.extent = extent;
            this.features = features;
        }
    }

    final Map<String, Layer> layers;

    private VectorTile(Map<String, Layer> layers) {
        this.layers = layers;
    }

    static VectorTile decode(byte[] data) {
        Map<String, Layer> layers = new HashMap<>();
        Reader r = new Reader(data, 0, data.length);
        while (r.more()) {
            int tag = r.varint32();
            if (tag >>> 3 == 3 && (tag & 7) == 2) {
                int len = r.varint32();
                Layer layer = layer(new Reader(data, r.pos, r.pos + len));
                layers.put(layer.name, layer);
                r.pos += len;
            } else {
                r.skip(tag & 7);
            }
        }
        return new VectorTile(layers);
    }

    private static Layer layer(Reader r) {
        String name = "";
        int extent = 4096;
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        List<int[]> rawFeatures = new ArrayList<>(); // start, end of each feature message
        while (r.more()) {
            int tag = r.varint32();
            int field = tag >>> 3;
            if (field == 1 && (tag & 7) == 2) {
                name = r.string();
            } else if (field == 2 && (tag & 7) == 2) {
                int len = r.varint32();
                rawFeatures.add(new int[] {r.pos, r.pos + len});
                r.pos += len;
            } else if (field == 3 && (tag & 7) == 2) {
                keys.add(r.string());
            } else if (field == 4 && (tag & 7) == 2) {
                int len = r.varint32();
                values.add(stringValue(new Reader(r.buf, r.pos, r.pos + len)));
                r.pos += len;
            } else if (field == 5 && (tag & 7) == 0) {
                extent = r.varint32();
            } else {
                r.skip(tag & 7);
            }
        }
        int classKey = keys.indexOf("class");
        List<Feature> features = new ArrayList<>(rawFeatures.size());
        for (int[] span : rawFeatures) {
            Feature f = feature(new Reader(r.buf, span[0], span[1]), classKey, values);
            if (f != null) features.add(f);
        }
        return new Layer(name, extent, features);
    }

    /** Only string values matter here; numbers and booleans read as null. */
    private static String stringValue(Reader r) {
        String s = null;
        while (r.more()) {
            int tag = r.varint32();
            if (tag >>> 3 == 1 && (tag & 7) == 2) s = r.string();
            else r.skip(tag & 7);
        }
        return s;
    }

    private static Feature feature(Reader r, int classKey, List<String> values) {
        int type = 0;
        String kind = null;
        List<int[]> parts = null;
        while (r.more()) {
            int tag = r.varint32();
            int field = tag >>> 3;
            if (field == 2 && (tag & 7) == 2) {
                int end = r.varint32() + r.pos;
                while (r.pos < end) {
                    int k = r.varint32();
                    int v = r.varint32();
                    if (k == classKey && v < values.size()) kind = values.get(v);
                }
            } else if (field == 3 && (tag & 7) == 0) {
                type = r.varint32();
            } else if (field == 4 && (tag & 7) == 2) {
                int end = r.varint32() + r.pos;
                parts = geometry(r, end);
            } else {
                r.skip(tag & 7);
            }
        }
        if (parts == null || type == POINT) return null;
        return new Feature(type, kind, parts);
    }

    /** MoveTo starts a part, LineTo extends it, ClosePath ends a ring; coordinates are zigzag deltas. */
    private static List<int[]> geometry(Reader r, int end) {
        List<int[]> parts = new ArrayList<>();
        int x = 0;
        int y = 0;
        int[] current = null;
        int n = 0;
        while (r.pos < end) {
            int cmd = r.varint32();
            int id = cmd & 7;
            int count = cmd >>> 3;
            if (id == 1 || id == 2) {
                for (int i = 0; i < count; i++) {
                    x += zigzag(r.varint32());
                    y += zigzag(r.varint32());
                    if (id == 1) {
                        if (current != null) parts.add(trim(current, n));
                        current = new int[16];
                        n = 0;
                    }
                    if (current == null) continue;
                    if (n + 2 > current.length) current = Arrays.copyOf(current, current.length * 2);
                    current[n++] = x;
                    current[n++] = y;
                }
            }
        }
        if (current != null) parts.add(trim(current, n));
        return parts;
    }

    private static int[] trim(int[] a, int n) {
        return n == a.length ? a : Arrays.copyOf(a, n);
    }

    private static int zigzag(int n) {
        return (n >>> 1) ^ -(n & 1);
    }

    private static final class Reader {
        final byte[] buf;
        int pos;
        final int end;

        Reader(byte[] buf, int pos, int end) {
            this.buf = buf;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        int varint32() {
            return (int) varint64();
        }

        long varint64() {
            long result = 0;
            int shift = 0;
            while (true) {
                byte b = buf[pos++];
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
            }
        }

        String string() {
            int len = varint32();
            String s = new String(buf, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        void skip(int wireType) {
            switch (wireType) {
                case 0: varint64(); break;
                case 1: pos += 8; break;
                case 2: pos += varint32(); break;
                case 5: pos += 4; break;
                default: throw new IllegalStateException("wire type " + wireType);
            }
        }
    }
}
