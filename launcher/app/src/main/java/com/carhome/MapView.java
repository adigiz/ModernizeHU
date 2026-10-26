package com.carhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * The map behind Home, centred on the car right of centre so the panel on the left never covers it.
 * Turns with the car while navigating; zooms out to show a whole route.
 */
public class MapView extends View {

    private static final long RETRY_MS = 30_000;
    private static final int MIN_ZOOM = 8;
    /** A 512 px RGB_565 tile is 0.5 MB; this keeps a screen and its neighbours. */
    private static final int CACHE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_FAILED = 200;

    private final int tileSize;
    private final float density;
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint destCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routeCasing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routeLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final Path routePath = new Path();
    private final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Map<String, Long> failedAt = new HashMap<>();

    private MapTiles tiles;

    private boolean hasCar;
    private double carLat;
    private double carLon;
    /** Degrees clockwise from north, or NaN when the car's heading is unknown. */
    private float carBearing = Float.NaN;

    private boolean follow = true;
    private boolean headingUp;
    private int zoom = MapTiles.MAX_ZOOM;
    private double centerLat;
    private double centerLon;
    private float focusX = 0.62f;
    private float focusY = 0.52f;
    private float mapBearing;

    /** Route points as fractions of the world (Web Mercator), x0, y0, x1, y1, ... */
    private double[] route;
    /** The route is drawn from this point on, starting at (aheadX, aheadY) when that is set. */
    private int aheadFrom;
    private double aheadX = Double.NaN;
    private double aheadY;
    private double destX = Double.NaN;
    private double destY;

    public MapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        tileSize = Math.round(512 * density);
        halo.setColor(0x403E8BFF);
        ring.setColor(0xFFFFFFFF);
        core.setColor(0xFF3E8BFF);
        arrowEdge.setStyle(Paint.Style.STROKE);
        arrowEdge.setStrokeJoin(Paint.Join.ROUND);
        arrowEdge.setStrokeWidth(2.5f * density);
        arrowEdge.setColor(0xFFFFFFFF);
        routeCasing.setStyle(Paint.Style.STROKE);
        routeCasing.setStrokeCap(Paint.Cap.ROUND);
        routeCasing.setStrokeJoin(Paint.Join.ROUND);
        routeCasing.setColor(0xFF0B1E3D);
        routeCasing.setStrokeWidth(10 * density);
        routeLine.set(routeCasing);
        routeLine.setColor(0xFF3E8BFF);
        routeLine.setStrokeWidth(6 * density);
        pin.setColor(0xFFFF5A5F);
        destCore.setColor(MapRenderer.BACKGROUND);
        arrow.moveTo(0, -14 * density);
        arrow.lineTo(10 * density, 11 * density);
        arrow.lineTo(0, 5 * density);
        arrow.lineTo(-10 * density, 11 * density);
        arrow.close();
    }

    void setTiles(MapTiles tiles) {
        this.tiles = tiles;
    }

    void setCar(double lat, double lon, float bearing) {
        carLat = lat;
        carLon = lon;
        if (!Float.isNaN(bearing)) carBearing = bearing;
        hasCar = true;
        if (follow) {
            centerLat = lat;
            centerLon = lon;
            if (headingUp && !Float.isNaN(carBearing)) mapBearing = carBearing;
        }
        invalidate();
    }

    void followNorthUp() {
        follow = true;
        headingUp = false;
        mapBearing = 0;
        zoom = MapTiles.MAX_ZOOM;
        focusX = 0.62f;
        focusY = 0.52f;
        if (hasCar) setCar(carLat, carLon, Float.NaN);
    }

    /** The car sits low on the screen so more road ahead shows. */
    void followHeadingUp() {
        follow = true;
        headingUp = true;
        zoom = MapTiles.MAX_ZOOM;
        focusX = 0.66f;
        focusY = 0.74f;
        if (hasCar) setCar(carLat, carLon, Float.NaN);
    }

    /** The whole route in the part of the screen the info panel leaves free. */
    void showOverview(Route r) {
        follow = false;
        mapBearing = 0;
        double minX = 1;
        double maxX = 0;
        double minY = 1;
        double maxY = 0;
        for (int i = 0; i < r.pointCount(); i++) {
            double x = worldX(r.lon(i));
            double y = worldY(r.lat(i));
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        // The free area: right of the music card, above the route card at the bottom right.
        double spanW = getWidth() * 0.5;
        double spanH = getHeight() * 0.5;
        zoom = MapTiles.MAX_ZOOM;
        while (zoom > MIN_ZOOM && ((maxX - minX) * worldSize(zoom) > spanW || (maxY - minY) * worldSize(zoom) > spanH)) zoom--;
        double cx = (minX + maxX) / 2;
        double cy = (minY + maxY) / 2;
        centerLon = cx * 360 - 180;
        centerLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * cy))));
        focusX = 0.66f;
        focusY = 0.34f;
        invalidate();
    }

    void setRoute(Route r) {
        aheadFrom = 0;
        aheadX = Double.NaN;
        if (r == null) {
            route = null;
            destX = Double.NaN;
        } else {
            route = new double[r.pointCount() * 2];
            for (int i = 0; i < r.pointCount(); i++) {
                route[2 * i] = worldX(r.lon(i));
                route[2 * i + 1] = worldY(r.lat(i));
            }
            destX = route[route.length - 2];
            destY = route[route.length - 1];
        }
        invalidate();
    }

    /** Hides the part already driven: the car is at (lat, lon) on the segment starting at point segment. */
    void setRouteAhead(int segment, double lat, double lon) {
        if (route == null) return;
        aheadFrom = Math.min(segment + 1, route.length / 2 - 1);
        aheadX = worldX(lon);
        aheadY = worldY(lat);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(MapRenderer.BACKGROUND);
        if ((!hasCar && follow) || tiles == null) return;

        double size = worldSize(zoom);
        double centerX = worldX(centerLon) * size;
        double centerY = worldY(centerLat) * size;
        int width = getWidth();
        int height = getHeight();
        float fx = width * focusX;
        float fy = height * focusY;

        canvas.save();
        canvas.rotate(-mapBearing, fx, fy);
        // Tiles under the whole screen, whichever way it is turned.
        double reach = Math.hypot(Math.max(fx, width - fx), Math.max(fy, height - fy));
        int x0 = (int) Math.floor((centerX - reach) / tileSize);
        int x1 = (int) Math.floor((centerX + reach) / tileSize);
        int y0 = (int) Math.floor((centerY - reach) / tileSize);
        int y1 = (int) Math.floor((centerY + reach) / tileSize);
        long now = SystemClock.elapsedRealtime();
        for (int tx = x0; tx <= x1; tx++) {
            for (int ty = y0; ty <= y1; ty++) {
                String key = MapTiles.key(zoom, tx, ty);
                Bitmap b = bitmaps.get(key);
                if (b != null) {
                    canvas.drawBitmap(b, (float) (tx * (double) tileSize - centerX + fx),
                            (float) (ty * (double) tileSize - centerY + fy), tilePaint);
                } else {
                    Long failed = failedAt.get(key);
                    if (failed == null || now - failed > RETRY_MS) request(zoom, tx, ty);
                }
            }
        }

        if (route != null) {
            routePath.rewind();
            boolean started = !Double.isNaN(aheadX);
            if (started) routePath.moveTo((float) (aheadX * size - centerX + fx), (float) (aheadY * size - centerY + fy));
            for (int i = 2 * aheadFrom; i < route.length; i += 2) {
                float px = (float) (route[i] * size - centerX + fx);
                float py = (float) (route[i + 1] * size - centerY + fy);
                if (started) {
                    routePath.lineTo(px, py);
                } else {
                    routePath.moveTo(px, py);
                    started = true;
                }
            }
            canvas.drawPath(routePath, routeCasing);
            canvas.drawPath(routePath, routeLine);
        }
        if (!Double.isNaN(destX)) {
            float px = (float) (destX * size - centerX + fx);
            float py = (float) (destY * size - centerY + fy);
            canvas.drawCircle(px, py, 11 * density, pin);
            canvas.drawCircle(px, py, 4 * density, destCore);
        }

        if (hasCar) {
            float px = (float) (worldX(carLon) * size - centerX + fx);
            float py = (float) (worldY(carLat) * size - centerY + fy);
            if (route != null && !Float.isNaN(carBearing)) {
                canvas.save();
                canvas.translate(px, py);
                canvas.rotate(carBearing);
                canvas.drawCircle(0, 0, 22 * density, halo);
                canvas.drawPath(arrow, core);
                canvas.drawPath(arrow, arrowEdge);
                canvas.restore();
            } else {
                canvas.drawCircle(px, py, 22 * density, halo);
                canvas.drawCircle(px, py, 10 * density, ring);
                canvas.drawCircle(px, py, 7.5f * density, core);
            }
        }
        canvas.restore();
    }

    private double worldSize(int z) {
        return (double) tileSize * (1 << z);
    }

    private static double worldX(double lon) {
        return (lon + 180) / 360;
    }

    private static double worldY(double lat) {
        double r = Math.toRadians(lat);
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2;
    }

    private void request(int z, int tx, int ty) {
        tiles.request(z, tx, ty, tileSize, (key, bitmap) -> {
            if (bitmap != null) {
                bitmaps.put(key, bitmap);
                failedAt.remove(key);
            } else {
                if (failedAt.size() >= MAX_FAILED) failedAt.clear();
                failedAt.put(key, SystemClock.elapsedRealtime());
            }
            invalidate();
        });
    }
}
