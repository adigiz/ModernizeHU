package com.carhome;

import android.app.Activity;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Visual turn-by-turn navigation on the map Home: search, route preview, then a next-turn banner. */
final class Navigation {

    interface Listener {
        /** True while a route is being previewed or driven; Home hides its clock and dock then. */
        void onNavigationShown(boolean shown);
    }

    private enum State { IDLE, PREVIEW, DRIVING, ARRIVED }

    private static final long SEARCH_DELAY_MS = 600;
    private static final double OFF_ROUTE_METRES = 45;
    private static final int OFF_ROUTE_FIXES = 3;
    private static final long REROUTE_EVERY_MS = 15_000;
    private static final double ARRIVED_METRES = 30;
    private static final long ARRIVED_SHOWN_MS = 20_000;
    private static final float MOVING_MS = 2f;

    private final Activity activity;
    private final MapView map;
    private final Router router = new Router();
    private final Places places;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final View search;
    private final EditText field;
    private final TextView status;
    private final PlaceAdapter adapter = new PlaceAdapter();

    private final View preview;
    private final TextView previewName;
    private final TextView previewSummary;
    private final View previewGo;

    private final View banner;
    private final ImageView turn;
    private final TextView distance;
    private final TextView instruction;
    private final TextView summary;

    private State state = State.IDLE;
    private Location here;
    private float bearing = Float.NaN;
    private Places.Place destination;
    private Route route;
    private int hint = -1;
    private int offRouteFixes;
    private long lastRouteRequest;
    private int routeRequests;
    private boolean routing;
    private Runnable pendingSearch;

    Navigation(Activity activity, MapView map, Listener listener) {
        this.activity = activity;
        this.map = map;
        this.listener = listener;
        places = new Places(activity);

        search = activity.findViewById(R.id.nav_search);
        field = activity.findViewById(R.id.search_field);
        status = activity.findViewById(R.id.search_status);
        ListView results = activity.findViewById(R.id.search_results);
        results.setAdapter(adapter);
        results.setOnItemClickListener((parent, view, position, id) -> choose(adapter.places.get(position)));
        activity.findViewById(R.id.search_close).setOnClickListener(v -> closeSearch());
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                scheduleSearch(SEARCH_DELAY_MS);
            }
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            scheduleSearch(0);
            hideKeyboard();
            return true;
        });

        preview = activity.findViewById(R.id.nav_preview);
        previewName = activity.findViewById(R.id.preview_name);
        previewSummary = activity.findViewById(R.id.preview_summary);
        previewGo = activity.findViewById(R.id.preview_go);
        previewGo.setOnClickListener(v -> start());
        activity.findViewById(R.id.preview_cancel).setOnClickListener(v -> end());

        banner = activity.findViewById(R.id.nav_banner);
        turn = activity.findViewById(R.id.nav_turn);
        distance = activity.findViewById(R.id.nav_distance);
        instruction = activity.findViewById(R.id.nav_instruction);
        summary = activity.findViewById(R.id.nav_summary);
        activity.findViewById(R.id.nav_end).setOnClickListener(v -> end());
    }

    void openSearch() {
        search.setVisibility(View.VISIBLE);
        field.setText("");
        showRecent();
        field.requestFocus();
        handler.postDelayed(() -> activity.getSystemService(InputMethodManager.class)
                .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT), 150);
    }

    boolean isSearchShowing() {
        return search.getVisibility() == View.VISIBLE;
    }

    /** Back closes search or a preview; while driving it leaves the route alone. */
    boolean onBack() {
        if (isSearchShowing()) {
            closeSearch();
            return true;
        }
        if (state == State.PREVIEW) {
            end();
            return true;
        }
        return false;
    }

    /**
     * Places the car on the map. On the route it sits on the line and points along the road; GPS has
     * no heading while the car stands still, so off the route it points the way the route goes.
     */
    void onLocation(Location location) {
        here = location;
        boolean moving = location.hasBearing() && location.getSpeed() > MOVING_MS;
        if (moving) bearing = location.getBearing();
        if (state != State.DRIVING || route == null) {
            map.setCar(location.getLatitude(), location.getLongitude(), bearing);
            return;
        }

        Route.Progress p = route.locate(location.getLatitude(), location.getLongitude(), hint);
        hint = p.segment;
        if (p.offRoute > OFF_ROUTE_METRES) {
            map.setCar(location.getLatitude(), location.getLongitude(), moving ? bearing : p.bearing);
        } else {
            map.setCar(p.lat, p.lon, p.bearing);
            map.setRouteAhead(p.segment, p.lat, p.lon);
        }
        if (p.remainingMetres < ARRIVED_METRES) {
            arrive();
            return;
        }
        offRouteFixes = p.offRoute > OFF_ROUTE_METRES ? offRouteFixes + 1 : 0;
        if (offRouteFixes >= OFF_ROUTE_FIXES && !routing
                && SystemClock.elapsedRealtime() - lastRouteRequest > REROUTE_EVERY_MS) {
            requestRoute(false);
        }
        showProgress(p, location);
    }

    void close() {
        handler.removeCallbacksAndMessages(null);
        router.close();
        places.close();
    }

    private void scheduleSearch(long delay) {
        handler.removeCallbacks(pendingSearch);
        String query = field.getText().toString().trim();
        if (query.length() < 3) {
            showRecent();
            return;
        }
        pendingSearch = () -> {
            status.setText(R.string.search_typing);
            double lat = here != null ? here.getLatitude() : 0;
            double lon = here != null ? here.getLongitude() : 0;
            places.search(query, lat, lon, (q, found, failed) -> {
                if (!q.equals(field.getText().toString().trim())) return;
                if (failed) status.setText(R.string.search_failed);
                else if (found.isEmpty()) status.setText(R.string.search_none);
                else status.setText("");
                adapter.show(found, false);
            });
        };
        handler.postDelayed(pendingSearch, delay);
    }

    private void showRecent() {
        List<Places.Place> recent = places.recent();
        status.setText(recent.isEmpty() ? "" : activity.getString(R.string.search_recent));
        adapter.show(recent, true);
    }

    private void closeSearch() {
        handler.removeCallbacks(pendingSearch);
        hideKeyboard();
        search.setVisibility(View.GONE);
    }

    private void hideKeyboard() {
        activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    private void choose(Places.Place place) {
        places.remember(place);
        closeSearch();
        destination = place;
        state = State.PREVIEW;
        listener.onNavigationShown(true);
        previewName.setText(place.name);
        previewSummary.setText(R.string.routing);
        previewGo.setEnabled(false);
        previewGo.setAlpha(0.4f);
        preview.setVisibility(View.VISIBLE);
        requestRoute(true);
    }

    private void requestRoute(boolean forPreview) {
        if (here == null || destination == null) {
            previewSummary.setText(R.string.waiting_for_gps);
            return;
        }
        routing = true;
        lastRouteRequest = SystemClock.elapsedRealtime();
        int request = ++routeRequests;
        if (!forPreview) instruction.setText(R.string.rerouting);
        router.route(here.getLatitude(), here.getLongitude(), bearing, destination.lat, destination.lon, destination.name,
                newRoute -> {
                    // A late answer for a destination since cancelled or replaced.
                    if (request != routeRequests || state == State.IDLE) return;
                    routing = false;
                    if (newRoute == null) {
                        if (state == State.PREVIEW) previewSummary.setText(R.string.route_failed);
                        return;
                    }
                    route = newRoute;
                    hint = -1;
                    offRouteFixes = 0;
                    map.setRoute(route);
                    if (state == State.PREVIEW) {
                        Route.Progress p = route.locate(here.getLatitude(), here.getLongitude(), -1);
                        previewSummary.setText(activity.getString(R.string.route_summary,
                                formatDuration(p.remainingSeconds), formatDistance(p.remainingMetres)));
                        previewGo.setEnabled(true);
                        previewGo.setAlpha(1f);
                        map.showOverview(route);
                    } else if (state == State.DRIVING) {
                        onLocation(here);
                    }
                });
    }

    private void start() {
        if (route == null) return;
        state = State.DRIVING;
        preview.setVisibility(View.GONE);
        banner.setVisibility(View.VISIBLE);
        map.followHeadingUp();
        if (here != null) onLocation(here);
    }

    private void arrive() {
        state = State.ARRIVED;
        turn.setImageResource(R.drawable.t_destination);
        distance.setText(R.string.arrived);
        instruction.setText(destination != null ? destination.name : "");
        summary.setText("");
        handler.postDelayed(this::end, ARRIVED_SHOWN_MS);
    }

    private void end() {
        state = State.IDLE;
        route = null;
        destination = null;
        routing = false;
        routeRequests++;
        handler.removeCallbacksAndMessages(null);
        preview.setVisibility(View.GONE);
        banner.setVisibility(View.GONE);
        map.setRoute(null);
        map.followNorthUp();
        listener.onNavigationShown(false);
    }

    private void showProgress(Route.Progress p, Location location) {
        Route.Maneuver next = route.maneuvers.get(p.next);
        turn.setImageResource(Route.icon(next.type));
        distance.setText(formatDistance(p.toNext));
        if (!routing) instruction.setText(next.instruction);
        String eta = DateFormat.getTimeFormat(activity).format(new Date(System.currentTimeMillis() + (long) (p.remainingSeconds * 1000)));
        summary.setText(activity.getString(R.string.drive_summary, CarLocation.speedKmh(location), eta,
                formatDuration(p.remainingSeconds), formatDistance(p.remainingMetres)));
    }

    static String formatDistance(double metres) {
        if (metres >= 10_000) return String.format(Locale.US, "%.0f km", metres / 1000);
        if (metres >= 1000) return String.format(Locale.US, "%.1f km", metres / 1000);
        if (metres >= 100) return Math.round(metres / 50) * 50 + " m";
        return Math.round(metres / 10) * 10 + " m";
    }

    static String formatDuration(double seconds) {
        long minutes = Math.max(1, Math.round(seconds / 60));
        if (minutes < 60) return minutes + " min";
        return minutes / 60 + " h " + minutes % 60 + " min";
    }

    private final class PlaceAdapter extends BaseAdapter {
        List<Places.Place> places = new ArrayList<>();
        boolean recent;

        void show(List<Places.Place> list, boolean fromHistory) {
            places = list;
            recent = fromHistory;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return places.size();
        }

        @Override
        public Object getItem(int position) {
            return places.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : activity.getLayoutInflater().inflate(R.layout.place_row, parent, false);
            Places.Place p = places.get(position);
            ((ImageView) v.findViewById(R.id.place_icon)).setImageResource(recent ? R.drawable.ic_history : R.drawable.t_destination);
            ((TextView) v.findViewById(R.id.place_name)).setText(p.name);
            ((TextView) v.findViewById(R.id.place_detail)).setText(p.detail);
            String away = "";
            if (here != null) {
                float[] d = new float[1];
                Location.distanceBetween(here.getLatitude(), here.getLongitude(), p.lat, p.lon, d);
                away = formatDistance(d[0]);
            }
            ((TextView) v.findViewById(R.id.place_distance)).setText(away);
            return v;
        }
    }
}
