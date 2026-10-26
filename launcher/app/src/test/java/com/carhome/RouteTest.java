package com.carhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A real Valhalla answer for 5.4 km across central Jakarta; the expected values come from decoding
 * the same answer in Python.
 */
public class RouteTest {

    private static Route jakarta() throws Exception {
        try (InputStream in = RouteTest.class.getResourceAsStream("/valhalla_jakarta.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return Router.parse(new String(out.toByteArray(), StandardCharsets.UTF_8), "Monas");
        }
    }

    @Test
    public void decodesTheShape() throws Exception {
        Route r = jakarta();
        assertEquals(239, r.pointCount());
        assertEquals(-6.208629, r.lat(0), 1e-9);
        assertEquals(106.845653, r.lon(0), 1e-9);
        assertEquals(-6.175404, r.lat(238), 1e-9);
        assertEquals(13, r.maneuvers.size());
        assertEquals(5413, r.totalMetres, 60);
    }

    @Test
    public void atTheStartTheNextTurnIsTheFirstManeuverAhead() throws Exception {
        Route r = jakarta();
        Route.Progress p = r.locate(r.lat(0), r.lon(0), -1);
        assertEquals(0, p.offRoute, 0.5);
        assertEquals(1, p.next);
        assertEquals("Keep left to stay on Jalan Sultan Agung.", r.maneuvers.get(p.next).instruction);
        assertEquals(73, p.toNext, 10);
        assertEquals(1047, p.remainingSeconds, 5);
    }

    @Test
    public void justBeforeATurnItIsTheNextOne() throws Exception {
        Route r = jakarta();
        int turn = r.maneuvers.get(2).begin;
        Route.Progress p = r.locate(r.lat(turn - 1), r.lon(turn - 1), turn - 3);
        assertEquals("Turn left onto Jalan Proklamasi.", r.maneuvers.get(p.next).instruction);
        assertTrue(p.toNext < 200);
    }

    @Test
    public void farFromTheRouteReadsAsOffRoute() throws Exception {
        Route r = jakarta();
        Route.Progress p = r.locate(r.lat(0) + 0.002, r.lon(0), 0);
        assertTrue("off route by " + p.offRoute, p.offRoute > 150);
    }

    /** A small route on the equator from lat, lon pairs. */
    private static Route corner(double... points) {
        return new Route(points, Arrays.asList(
                new Route.Maneuver(1, "Drive east.", 0, 60),
                new Route.Maneuver(4, "You have arrived.", points.length / 2 - 1, 0)), "Corner");
    }

    @Test
    public void theCarSnapsToTheRouteAndPointsAlongIt() {
        Route r = corner(0, 0, 0, 0.01, 0.01, 0.01);
        Route.Progress p = r.locate(0.0002, 0.005, -1);
        assertEquals(0, p.lat, 1e-9);
        assertEquals(0.005, p.lon, 1e-9);
        assertEquals(90, p.bearing, 0.01);

        p = r.locate(0.005, 0.0102, -1);
        assertEquals(0.01, p.lon, 1e-9);
        assertEquals(0, p.bearing, 0.01);
    }

    @Test
    public void aRepeatedPointTakesTheDirectionOfTheRoadAfterIt() {
        Route r = corner(0, 0, 0, 0, 0, 0.01);
        assertEquals(90, r.locate(0, 0, -1).bearing, 0.01);
    }

    @Test
    public void atTheEndNothingRemains() throws Exception {
        Route r = jakarta();
        int last = r.pointCount() - 1;
        Route.Progress p = r.locate(r.lat(last), r.lon(last), last - 2);
        assertEquals(0, p.remainingMetres, 1);
        assertEquals(12, p.next);
    }
}
