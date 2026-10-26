package com.carhome;

import java.util.Arrays;

/** Google's encoded polyline format, as Valhalla returns route shapes (6 decimal places). */
final class Polyline {

    private Polyline() {
    }

    /** lat0, lon0, lat1, lon1, ... */
    static double[] decode(String encoded, int precision) {
        double factor = Math.pow(10, precision);
        double[] out = new double[encoded.length()];
        int n = 0;
        int index = 0;
        long lat = 0;
        long lon = 0;
        while (index < encoded.length()) {
            long[] value = new long[2];
            for (int which = 0; which < 2; which++) {
                int shift = 0;
                long result = 0;
                int b;
                do {
                    b = encoded.charAt(index++) - 63;
                    result |= (long) (b & 0x1f) << shift;
                    shift += 5;
                } while (b >= 0x20);
                value[which] = (result & 1) != 0 ? ~(result >> 1) : result >> 1;
            }
            lat += value[0];
            lon += value[1];
            if (n + 2 > out.length) out = Arrays.copyOf(out, out.length * 2);
            out[n++] = lat / factor;
            out[n++] = lon / factor;
        }
        return Arrays.copyOf(out, n);
    }
}
