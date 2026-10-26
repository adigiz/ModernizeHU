package com.carhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class HomeActivity extends Activity {

    /** Dock tiles in order: package, label. Packages that are missing or disabled are skipped. */
    private static final String[][] DOCK = {
            {"com.google.android.apps.maps", "Maps"},
            {"com.spotify.music", "Spotify"},
            // NewPipe: Google no longer updates the YouTube app for Android 7.1.
            {"org.schabi.newpipe", "YouTube"},
            {"com.nwd.android.phone", "Phone"},
            {"com.nwd.bt.music", "BT Music"},
            {"com.android.car.setting", "Car"},
            {"com.android.settings", "Settings"},
    };
    private static final long DOCK_IDLE_MS = 10_000;
    /** Opened by the now-playing card while no media session is active. */
    private static final String MUSIC_APP = "com.spotify.music";
    private static final String BT_MUSIC_APP = "com.nwd.bt.music";

    private static final String TAG = "Home";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LayoutInflater inflater;
    private PackageManager pm;
    private AppIcons icons;

    private MediaSessionManager sessions;
    private ComponentName listener;
    private MediaController controller;
    private BtMusic btMusic;
    /** The card follows Bluetooth while the phone was the last thing playing, and before anything has. */
    private boolean btLast = true;

    private SongLookup songLookup;
    private String trackKey;
    private Bitmap lookupCover;
    private Lyrics lyrics;
    private boolean lyricsLoading;
    private Runnable pendingLookup;
    private NowPlayingPanel panel;

    private MapView map;
    private MapTiles mapTiles;
    private CarLocation location;
    private Weather weather;
    private Location here;

    private Navigation navigation;
    /** A route is being previewed or driven, so the clock, dock and search are hidden. */
    private boolean navigating;

    private View info;
    private View ambient;
    private View searchPill;
    private LinearLayout dock;
    private View nowPlayingCard;
    private TextView speed;
    private View weatherRow;
    private ImageView weatherIcon;
    private TextView temperature;
    private TextView condition;
    private TextView area;
    private LinearLayout prayers;
    private GridView allApps;
    private View allAppsOverlay;
    private ImageView art;
    private TextView title;
    private TextView artist;
    private ImageButton play;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pickController;

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            render();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            render();
        }

        @Override
        public void onSessionDestroyed() {
            setController(null);
        }
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            buildDock();
            if (allAppsOverlay.getVisibility() == View.VISIBLE) showAllApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Tls.install();
        setContentView(R.layout.home);
        inflater = getLayoutInflater();
        pm = getPackageManager();
        icons = new AppIcons(this);
        sessions = getSystemService(MediaSessionManager.class);
        listener = new ComponentName(this, MediaListener.class);

        map = findViewById(R.id.map);
        info = findViewById(R.id.info);
        ambient = findViewById(R.id.ambient);
        searchPill = findViewById(R.id.search_pill);
        dock = findViewById(R.id.dock);
        nowPlayingCard = findViewById(R.id.now_playing);
        speed = findViewById(R.id.speed);
        weatherRow = findViewById(R.id.weather);
        weatherIcon = findViewById(R.id.weather_icon);
        temperature = findViewById(R.id.temperature);
        condition = findViewById(R.id.condition);
        area = findViewById(R.id.area);
        prayers = findViewById(R.id.prayers);
        allApps = findViewById(R.id.all_apps);
        allAppsOverlay = findViewById(R.id.all_apps_overlay);
        findViewById(R.id.all_apps_close).setOnClickListener(v -> hideAllApps());
        art = findViewById(R.id.art);
        title = findViewById(R.id.title);
        artist = findViewById(R.id.artist);
        play = findViewById(R.id.play);

        findViewById(R.id.prev).setOnClickListener(v -> skip(false));
        findViewById(R.id.next).setOnClickListener(v -> skip(true));
        play.setOnClickListener(v -> playPause());
        art.setBackgroundResource(R.drawable.rounded_art);
        art.setClipToOutline(true);
        nowPlayingCard.setOnClickListener(v -> showNowPlaying());
        nowPlayingCard.setOnLongClickListener(v -> {
            openMusic();
            return true;
        });
        allApps.setOnItemClickListener((parent, view, position, id) -> {
            ResolveInfo ri = (ResolveInfo) parent.getItemAtPosition(position);
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
            hideAllApps();
            launch(i);
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);

        songLookup = new SongLookup(this);
        btMusic = new BtMusic(this, this::render);
        btMusic.register();

        mapTiles = new MapTiles(this);
        map.setTiles(mapTiles);
        navigation = new Navigation(this, map, shown -> {
            navigating = shown;
            ambient.setVisibility(shown ? View.INVISIBLE : View.VISIBLE);
            searchPill.setVisibility(shown ? View.GONE : View.VISIBLE);
            if (shown) {
                handler.removeCallbacks(fadeDock);
                dock.animate().cancel();
                dock.setVisibility(View.INVISIBLE);
            } else {
                wakeDock();
            }
        });
        searchPill.setOnClickListener(v -> navigation.openSearch());
        location = new CarLocation(this, this::onLocation);
        weather = new Weather(this, this::showWeather);
        Weather.Now saved = weather.saved();
        if (saved != null) showWeather(saved);
        speed.setText(getString(R.string.speed_kmh, 0));
        Location last = location.last();
        if (last != null) onLocation(last);

        buildDock();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        btMusic.unregister();
        handler.removeCallbacksAndMessages(null);
        dock.animate().cancel();
        songLookup.close();
        mapTiles.close();
        weather.close();
        navigation.close();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler);
            pickController(sessions.getActiveSessions(listener));
        } catch (SecurityException e) {
            // Notification access not granted: the buttons still work through media keys.
            pickController(null);
        }
        handler.post(playStateTicker);
        location.start();
        registerReceiver(minuteTick, new IntentFilter(Intent.ACTION_TIME_TICK));
        showPrayers();
        wakeDock();
    }

    @Override
    protected void onStop() {
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        } catch (SecurityException ignored) {
        }
        setController(null);
        hideNowPlaying();
        handler.removeCallbacks(playStateTicker);
        handler.removeCallbacks(fadeDock);
        location.stop();
        unregisterReceiver(minuteTick);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideAllApps();
        hideNowPlaying();
    }

    @Override
    public void onBackPressed() {
        if (panel != null && panel.isShowing()) hideNowPlaying();
        else if (allAppsOverlay.getVisibility() == View.VISIBLE) hideAllApps();
        else navigation.onBack();
    }

    private void buildDock() {
        dock.removeAllViews();
        for (String[] entry : DOCK) {
            Intent launch = pm.getLaunchIntentForPackage(entry[0]);
            if (launch == null) continue;
            ResolveInfo ri = pm.resolveActivity(launch, 0);
            View item = dockItem(ri != null ? icons.forActivity(ri.activityInfo) : pm.getDefaultActivityIcon(), entry[1]);
            if (BT_MUSIC_APP.equals(entry[0])) {
                item.setOnClickListener(v -> {
                    btLast = true;
                    btMusic.playInBackground();
                    render();
                });
                item.setOnLongClickListener(v -> {
                    launch(launch);
                    return true;
                });
            } else {
                item.setOnClickListener(v -> launch(launch));
            }
        }
        dockItem(icons.allApps(), getString(R.string.all_apps)).setOnClickListener(v -> showAllApps());
    }

    private View dockItem(Drawable icon, CharSequence label) {
        View item = inflater.inflate(R.layout.dock_item, dock, false);
        ((ImageView) item.findViewById(R.id.icon)).setImageDrawable(icon);
        ((TextView) item.findViewById(R.id.label)).setText(label);
        dock.addView(item);
        return item;
    }

    private void wakeDock() {
        handler.removeCallbacks(fadeDock);
        if (navigating || allAppsOverlay.getVisibility() == View.VISIBLE) return;
        if (dock.getVisibility() != View.VISIBLE || dock.getAlpha() < 1f) {
            dock.animate().cancel();
            dock.setVisibility(View.VISIBLE);
            dock.animate().alpha(1f).setDuration(150).start();
        }
        handler.postDelayed(fadeDock, DOCK_IDLE_MS);
    }

    private final Runnable fadeDock = () -> dock.animate().alpha(0f).setDuration(400)
            .withEndAction(() -> dock.setVisibility(View.INVISIBLE)).start();

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            boolean hidden = dock.getVisibility() != View.VISIBLE;
            wakeDock();
            // The first touch on a bare map only brings the dock back.
            if (hidden && !navigating && !isOverlayShowing() && !touches(nowPlayingCard, event)
                    && !touches(searchPill, event)) return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean isOverlayShowing() {
        return allAppsOverlay.getVisibility() == View.VISIBLE || (panel != null && panel.isShowing())
                || navigation.isSearchShowing();
    }

    private static boolean touches(View v, MotionEvent e) {
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return e.getRawX() >= at[0] && e.getRawX() < at[0] + v.getWidth()
                && e.getRawY() >= at[1] && e.getRawY() < at[1] + v.getHeight();
    }

    private void onLocation(Location location) {
        boolean firstFix = here == null;
        here = location;
        if (firstFix) showPrayers();
        navigation.onLocation(location);
        speed.setText(getString(R.string.speed_kmh, CarLocation.speedKmh(location)));
        weather.update(location);
    }

    private void showWeather(Weather.Now now) {
        weatherRow.setVisibility(View.VISIBLE);
        weatherIcon.setImageResource(Weather.icon(now.code, now.day));
        temperature.setText(getString(R.string.temperature_c, now.temperature));
        condition.setText(Weather.describe(now.code));
        area.setText(now.area);
    }

    private void showPrayers() {
        if (here == null) {
            prayers.setVisibility(View.INVISIBLE);
            return;
        }
        Calendar c = Calendar.getInstance();
        double offsetHours = c.getTimeZone().getOffset(c.getTimeInMillis()) / 3_600_000.0;
        int[] times = PrayerTimes.minutes(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                here.getLatitude(), here.getLongitude(), offsetHours);
        int nowMinutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int next = 0;
        while (next < times.length && times[next] <= nowMinutes) next++;
        if (next == times.length) next = 0;
        if (prayers.getChildCount() == 0) {
            for (String name : PrayerTimes.NAMES) {
                View item = inflater.inflate(R.layout.prayer_item, prayers, false);
                ((TextView) item.findViewById(R.id.prayer_name)).setText(name);
                prayers.addView(item);
            }
        }
        int highlight = getColor(R.color.prayer_next);
        int soft = getColor(R.color.text_soft);
        int white = getColor(R.color.text);
        for (int i = 0; i < times.length; i++) {
            View item = prayers.getChildAt(i);
            TextView name = item.findViewById(R.id.prayer_name);
            TextView time = item.findViewById(R.id.prayer_time);
            time.setText(String.format(Locale.US, "%02d:%02d", times[i] / 60, times[i] % 60));
            name.setTextColor(i == next ? highlight : soft);
            time.setTextColor(i == next ? highlight : white);
        }
        prayers.setVisibility(View.VISIBLE);
    }

    private final BroadcastReceiver minuteTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            showPrayers();
            if (here != null) weather.update(here);
        }
    };

    private void launch(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not open " + intent.getComponent(), e);
            Toast.makeText(this, R.string.cannot_open_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAllApps() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppEntry> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            if (!ri.activityInfo.packageName.equals(getPackageName())) apps.add(new AppEntry(ri, ri.loadLabel(pm).toString()));
        }
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a.label, b.label));
        allApps.setAdapter(new AppsAdapter(apps));
        allAppsOverlay.setVisibility(View.VISIBLE);
        // The clock and info would show through the dimmed overlay.
        info.setVisibility(View.INVISIBLE);
        searchPill.setVisibility(View.INVISIBLE);
        dock.setVisibility(View.INVISIBLE);
    }

    private void hideAllApps() {
        if (allAppsOverlay.getVisibility() == View.VISIBLE) {
            allAppsOverlay.setVisibility(View.GONE);
            allApps.setAdapter(null);
            info.setVisibility(View.VISIBLE);
            if (!navigating) searchPill.setVisibility(View.VISIBLE);
            wakeDock();
        }
    }

    private void pickController(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    pick = c;
                    break;
                }
            }
            if (pick == null && !list.isEmpty()) pick = list.get(0);
        }
        setController(pick);
    }

    private void setController(MediaController c) {
        if (controller != null) controller.unregisterCallback(mediaCallback);
        controller = c;
        if (controller != null) controller.registerCallback(mediaCallback, handler);
        render();
    }

    private boolean isPlaying() {
        PlaybackState s = controller != null ? controller.getPlaybackState() : null;
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    private void render() {
        boolean bt = showingBt();
        String songTitle;
        String songArtist;
        Bitmap ownArt = null;
        boolean playing;
        if (bt) {
            songTitle = btMusic.title();
            songArtist = btMusic.artist();
            playing = btMusic.isPlaying();
        } else {
            MediaMetadata md = controller != null ? controller.getMetadata() : null;
            songTitle = md != null ? md.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
            songArtist = null;
            if (md != null) {
                songArtist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (songArtist == null) songArtist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                ownArt = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (ownArt == null) ownArt = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (ownArt == null) ownArt = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            playing = isPlaying();
        }
        if (songTitle != null && songTitle.isEmpty()) songTitle = null;
        trackChanged(songTitle, songArtist, ownArt == null);

        Bitmap cover = ownArt != null ? ownArt : lookupCover;
        int fallbackArt = bt ? R.drawable.art_bluetooth : R.drawable.art_placeholder;
        CharSequence shownTitle;
        CharSequence shownArtist;
        if (songTitle != null) {
            shownTitle = songTitle;
            shownArtist = songArtist != null ? songArtist : "";
        } else if (bt) {
            shownTitle = getString(R.string.bluetooth_audio);
            shownArtist = getString(R.string.from_phone);
        } else {
            shownTitle = getString(R.string.not_playing);
            shownArtist = getString(R.string.tap_to_open);
        }
        title.setText(shownTitle);
        artist.setText(shownArtist);
        if (cover != null) art.setImageBitmap(cover);
        else art.setImageResource(fallbackArt);
        play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (panel != null) panel.bind(shownTitle, shownArtist, cover, fallbackArt, playing, bt);
    }

    private boolean showingBt() {
        if (btMusic.isPlaying()) btLast = true;
        else if (isPlaying()) btLast = false;
        return btLast;
    }

    /** Starts a cover and lyrics lookup once the song has stayed the same for a moment. */
    private void trackChanged(String songTitle, String songArtist, boolean wantCover) {
        String key = songTitle != null ? SongLookup.key(songTitle, songArtist) : null;
        if (key == null ? trackKey == null : key.equals(trackKey)) return;
        trackKey = key;
        lookupCover = null;
        lyrics = null;
        handler.removeCallbacks(pendingLookup);
        boolean ad = songArtist != null && songArtist.startsWith("Advertisement");
        lyricsLoading = key != null && !ad;
        if (panel != null) panel.setLyrics(null, lyricsMessage());
        if (!lyricsLoading) return;
        pendingLookup = () -> songLookup.lookup(songTitle, songArtist, wantCover, result -> {
            if (!result.key.equals(trackKey)) return;
            lookupCover = result.cover;
            lyrics = result.lyrics;
            lyricsLoading = false;
            if (panel != null) panel.setLyrics(lyrics, lyricsMessage());
            render();
        });
        handler.postDelayed(pendingLookup, 800);
    }

    /** Shown in place of lyrics: 0 leaves the area empty while nothing is playing. */
    private int lyricsMessage() {
        if (lyricsLoading) return R.string.lyrics_loading;
        return trackKey != null ? R.string.lyrics_none : 0;
    }

    private long position() {
        if (showingBt()) return btMusic.positionMs();
        PlaybackState s = controller != null ? controller.getPlaybackState() : null;
        if (s == null) return 0;
        long p = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            p += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return p;
    }

    private void showNowPlaying() {
        if (panel == null) {
            panel = new NowPlayingPanel(this, findViewById(android.R.id.content), new NowPlayingPanel.Controls() {
                @Override
                public void playPause() {
                    HomeActivity.this.playPause();
                }

                @Override
                public void skip(boolean forward) {
                    HomeActivity.this.skip(forward);
                }

                @Override
                public void nudge(long ms) {
                    btMusic.nudge(ms);
                    panel.setPosition(position());
                }

                @Override
                public void seekTo(long ms) {
                    if (showingBt()) btMusic.setPosition(ms);
                    else if (controller != null) controller.getTransportControls().seekTo(ms);
                    panel.setPosition(ms);
                }

                @Override
                public void close() {
                    hideNowPlaying();
                }
            });
        }
        panel.setLyrics(lyrics, lyricsMessage());
        render();
        panel.show();
        handler.removeCallbacks(lyricsTicker);
        handler.post(lyricsTicker);
    }

    private void hideNowPlaying() {
        handler.removeCallbacks(lyricsTicker);
        if (panel != null) panel.hide();
    }

    private final Runnable lyricsTicker = new Runnable() {
        @Override
        public void run() {
            btMusic.syncWithAudio();
            panel.setPosition(position());
            handler.postDelayed(this, 250);
        }
    };

    /** The Bluetooth module can skip its play and pause broadcasts; see {@link BtMusic#syncWithAudio}. */
    private final Runnable playStateTicker = new Runnable() {
        @Override
        public void run() {
            btMusic.syncWithAudio();
            handler.postDelayed(this, 1000);
        }
    };

    private void playPause() {
        if (showingBt()) {
            btMusic.playPause();
        } else if (controller == null) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        } else if (isPlaying()) {
            controller.getTransportControls().pause();
        } else {
            controller.getTransportControls().play();
        }
    }

    private void skip(boolean forward) {
        if (showingBt()) {
            btMusic.skip(forward);
        } else if (controller == null) {
            sendMediaKey(forward ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        } else if (forward) {
            controller.getTransportControls().skipToNext();
        } else {
            controller.getTransportControls().skipToPrevious();
        }
    }

    private void sendMediaKey(int code) {
        AudioManager am = getSystemService(AudioManager.class);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private void openMusic() {
        if (showingBt()) return;
        String pkg = controller != null ? controller.getPackageName() : MUSIC_APP;
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(MUSIC_APP);
        if (i != null) launch(i);
    }

    private static final class AppEntry {
        final ResolveInfo info;
        final String label;
        Drawable icon;

        AppEntry(ResolveInfo info, String label) {
            this.info = info;
            this.label = label;
        }
    }

    private final class AppsAdapter extends BaseAdapter {
        private final List<AppEntry> apps;

        AppsAdapter(List<AppEntry> apps) {
            this.apps = apps;
        }

        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(position).info;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView : inflater.inflate(R.layout.app_item, parent, false);
            AppEntry app = apps.get(position);
            if (app.icon == null) app.icon = icons.forActivity(app.info.activityInfo);
            ((ImageView) v.findViewById(R.id.icon)).setImageDrawable(app.icon);
            ((TextView) v.findViewById(R.id.label)).setText(app.label);
            return v;
        }
    }
}
