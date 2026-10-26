package com.carhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Counts match the mapbox-vector-tile Python decoder on the same OpenFreeMap tile of Padang. */
public class VectorTileTest {

    private static VectorTile padang() throws IOException {
        try (InputStream in = VectorTileTest.class.getResourceAsStream("/padang_14_12759_8233.pbf")) {
            assertNotNull(in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return VectorTile.decode(out.toByteArray());
        }
    }

    private static int count(VectorTile.Layer layer, String kind) {
        int n = 0;
        for (VectorTile.Feature f : layer.features) if (kind.equals(f.kind)) n++;
        return n;
    }

    @Test
    public void readsRoadsWithTheirClass() throws IOException {
        VectorTile.Layer roads = padang().layers.get("transportation");
        assertEquals(4096, roads.extent);
        assertEquals(95, roads.features.size());
        assertEquals(28, count(roads, "primary"));
        assertEquals(10, count(roads, "secondary"));
        assertEquals(28, count(roads, "service"));
        for (VectorTile.Feature f : roads.features) {
            assertEquals(VectorTile.LINESTRING, f.type);
            for (int[] part : f.parts) assertTrue(part.length >= 4 && part.length % 2 == 0);
        }
    }

    @Test
    public void readsWaterPolygons() throws IOException {
        VectorTile.Layer water = padang().layers.get("water");
        assertEquals(11, water.features.size());
        assertEquals(1, count(water, "ocean"));
        for (VectorTile.Feature f : water.features) assertEquals(VectorTile.POLYGON, f.type);
    }
}
