package com.carhome;

import java.util.List;

/**
 * A driving route and where the car is on it. Distances use a flat projection around the route,
 * which is accurate to well under a metre over a city drive.
 */
final class Route {

    static final class Maneuver {
        /** Valhalla maneuver type, see {@link #icon}. */
        final int type;
        final String instruction;
        /** Index into the route's points where this maneuver starts. */
        final int begin;
        final double seconds;

        Maneuver(int type, String instruction, int begin, double seconds) {
            this.type = type;
            this.instruction = instruction;
            this.begin = begin;
            this.seconds = seconds;
        }
    }

    static final class Progress {
        /** The route segment (from point i to i + 1) the car is on. */
        int segment;
        /** Metres from the car to the route; large means it has left the route. */
        double offRoute;
        /** The nearest point on the route. */
        double lat;
        double lon;
        /** Direction of the route there, degrees clockwise from north. */
        float bearing;
        /** The maneuver ahead, an index into {@link #maneuvers}. */
        int next;
        double toNext;
        double remainingMetres;
        double remainingSeconds;
    }

    private static final double EARTH_RADIUS = 6_371_000;

    final double[] points;
    final List<Maneuver> maneuvers;
    final String destination;
    final double totalMetres;
    /** Metres along the route at each point. */
    private final double[] along;
    private final double cosLat;

    Route(double[] points, List<Maneuver> maneuvers, String destination) {
        this.points = points;
        this.maneuvers = maneuvers;
        this.destination = destination;
        cosLat = Math.cos(Math.toRadians(points[0]));
        int n = points.length / 2;
        along = new double[n];
        for (int i = 1; i < n; i++) along[i] = along[i - 1] + distance(i - 1, i);
        totalMetres = along[n - 1];
    }

    int pointCount() {
        return along.length;
    }

    double lat(int i) {
        return points[2 * i];
    }

    double lon(int i) {
        return points[2 * i + 1];
    }

    /**
     * Where the car is. hint is the segment found last time, or -1; the search then stays near it,
     * so a route that passes the same street twice does not jump back.
     */
    Progress locate(double lat, double lon, int hint) {
        int last = along.length - 2;
        int from = hint < 0 ? 0 : Math.max(0, hint - 5);
        int to = hint < 0 ? last : Math.min(last, hint + 80);
        double px = x(lon);
        double py = y(lat);
        double best = Double.MAX_VALUE;
        int bestSegment = from;
        double bestT = 0;
        for (int i = from; i <= to; i++) {
            double ax = x(lon(i));
            double ay = y(lat(i));
            double dx = x(lon(i + 1)) - ax;
            double dy = y(lat(i + 1)) - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
            double ex = ax + t * dx - px;
            double ey = ay + t * dy - py;
            double d = ex * ex + ey * ey;
            if (d < best) {
                best = d;
                bestSegment = i;
                bestT = t;
            }
        }
        Progress p = new Progress();
        p.segment = bestSegment;
        p.offRoute = Math.sqrt(best);
        p.lat = lat(bestSegment) + bestT * (lat(bestSegment + 1) - lat(bestSegment));
        p.lon = lon(bestSegment) + bestT * (lon(bestSegment + 1) - lon(bestSegment));
        p.bearing = bearing(bestSegment);
        double here = along[bestSegment] + bestT * (along[bestSegment + 1] - along[bestSegment]);
        p.remainingMetres = Math.max(0, totalMetres - here);

        p.next = maneuvers.size() - 1;
        for (int m = 0; m < maneuvers.size(); m++) {
            if (along[maneuvers.get(m).begin] > here + 1) {
                p.next = m;
                break;
            }
        }
        p.toNext = Math.max(0, along[maneuvers.get(p.next).begin] - here);

        double seconds = 0;
        for (int m = 0; m < maneuvers.size(); m++) {
            double start = along[maneuvers.get(m).begin];
            double end = m + 1 < maneuvers.size() ? along[maneuvers.get(m + 1).begin] : totalMetres;
            if (end <= here) continue;
            double length = end - start;
            double share = length <= 0 ? 0 : (end - Math.max(start, here)) / length;
            seconds += maneuvers.get(m).seconds * share;
        }
        p.remainingSeconds = seconds;
        return p;
    }

    /** Arrow for a Valhalla maneuver type. */
    static int icon(int type) {
        switch (type) {
            case 4: case 5: case 6: return R.drawable.t_destination;
            case 2: case 10: case 11: return R.drawable.t_right;
            case 3: case 14: case 15: return R.drawable.t_left;
            case 9: case 18: case 20: case 23: case 37: return R.drawable.t_slight_right;
            case 16: case 19: case 21: case 24: case 38: return R.drawable.t_slight_left;
            case 12: case 13: return R.drawable.t_uturn;
            case 26: case 27: return R.drawable.t_roundabout;
            default: return R.drawable.t_straight;
        }
    }

    /** Shapes repeat a point now and then; those empty segments have no direction of their own. */
    private float bearing(int segment) {
        int i = segment;
        while (i < along.length - 2 && along[i + 1] == along[i]) i++;
        double dx = x(lon(i + 1)) - x(lon(i));
        double dy = y(lat(i + 1)) - y(lat(i));
        return (float) ((Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360);
    }

    private double distance(int i, int j) {
        double dx = x(lon(j)) - x(lon(i));
        double dy = y(lat(j)) - y(lat(i));
        return Math.sqrt(dx * dx + dy * dy);
    }

    private double x(double lon) {
        return Math.toRadians(lon) * EARTH_RADIUS * cosLat;
    }

    private double y(double lat) {
        return Math.toRadians(lat) * EARTH_RADIUS;
    }
}
