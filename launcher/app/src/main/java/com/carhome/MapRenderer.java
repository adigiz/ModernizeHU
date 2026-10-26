package com.carhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** Draws one vector tile in the dark Home style. */
final class MapRenderer {

    static final int BACKGROUND = 0xFF181C2A;
    private static final int WATER = 0xFF0E111C;
    private static final int GREEN = 0xFF1B242A;

    /** Road classes from minor to major, drawn in this order so major roads stay on top. */
    private static final String[] ROAD_CLASSES = {"service", "minor", "tertiary", "secondary", "primary", "trunk", "motorway"};
    /** Main roads are a pale blue-grey so the saturated blue route line stays the brightest thing. */
    private static final int[] ROAD_COLORS = {0xFF50566A, 0xFF707686, 0xFFAAB0BE, 0xFFC8CCD6, 0xFF9DB3D6, 0xFFB2C6E8, 0xFFB2C6E8};
    private static final int MAIN_ROAD_OVERVIEW = 0xFF6A7FA3;
    /** Stroke widths for a 512 px tile (zoom 14 drawn at zoom 15 size). */
    private static final float[] ROAD_WIDTHS = {1f, 1.4f, 2.2f, 3f, 4f, 5f, 5f};

    private MapRenderer() {
    }

    /** Below zoom 14 roads thin out and minor ones drop, so a route overview stays readable. */
    static Bitmap render(VectorTile tile, int size, int zoom) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(BACKGROUND);
        float scale = size / 512f;
        float widthScale = zoom >= 14 ? 1f : zoom == 13 ? 0.7f : 0.5f;
        // Road classes below this index are left out: service and minor from 12, tertiary from 11.
        int firstClass = zoom >= 13 ? 0 : zoom == 12 ? 2 : 3;

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(GREEN);
        fillLayer(canvas, tile.layers.get("landcover"), size, fill, "wood", "grass");
        fillLayer(canvas, tile.layers.get("park"), size, fill);
        fill.setColor(WATER);
        fillLayer(canvas, tile.layers.get("water"), size, fill);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(WATER);
        line.setStrokeWidth(2.5f * scale);
        strokeLayer(canvas, tile.layers.get("waterway"), size, line, "river", "canal");

        VectorTile.Layer roads = tile.layers.get("transportation");
        for (int i = firstClass; i < ROAD_CLASSES.length; i++) {
            int color = ROAD_COLORS[i];
            if (zoom < 14 && i >= 4) color = MAIN_ROAD_OVERVIEW;
            line.setColor(color);
            line.setStrokeWidth(Math.max(0.8f, ROAD_WIDTHS[i] * widthScale) * scale);
            strokeLayer(canvas, roads, size, line, ROAD_CLASSES[i]);
        }
        return bitmap;
    }

    /** Polygons of the given classes (all when none are named), with holes from ring winding. */
    private static void fillLayer(Canvas canvas, VectorTile.Layer layer, int size, Paint paint, String... kinds) {
        Path path = layerPath(layer, size, VectorTile.POLYGON, kinds);
        if (path != null) canvas.drawPath(path, paint);
    }

    private static void strokeLayer(Canvas canvas, VectorTile.Layer layer, int size, Paint paint, String... kinds) {
        Path path = layerPath(layer, size, VectorTile.LINESTRING, kinds);
        if (path != null) canvas.drawPath(path, paint);
    }

    private static Path layerPath(VectorTile.Layer layer, int size, int type, String[] kinds) {
        if (layer == null) return null;
        float s = (float) size / layer.extent;
        Path path = new Path();
        boolean any = false;
        for (VectorTile.Feature f : layer.features) {
            if (f.type != type || !matches(f.kind, kinds)) continue;
            for (int[] part : f.parts) {
                if (part.length < 4) continue;
                path.moveTo(part[0] * s, part[1] * s);
                for (int i = 2; i < part.length; i += 2) path.lineTo(part[i] * s, part[i + 1] * s);
                if (type == VectorTile.POLYGON) path.close();
                any = true;
            }
        }
        return any ? path : null;
    }

    private static boolean matches(String kind, String[] kinds) {
        if (kinds.length == 0) return true;
        for (String k : kinds) if (k.equals(kind)) return true;
        return false;
    }
}
