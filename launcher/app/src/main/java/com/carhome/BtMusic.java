package com.carhome;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Bluetooth music from the phone, through NWD's own apps: com.bt.bc03 reports the state and the
 * song, com.nwd.bt.music owns the audio source and takes the play commands.
 */
final class BtMusic {

    interface Listener {
        void onBtMusicChanged();
    }

    private static final String ACTION_PLAY = "com.bt.ACTION_BT_MUSIC_PLAY";
    private static final String ACTION_PAUSE = "com.bt.ACTION_BT_MUSIC_PAUSE";
    private static final String ACTION_ID3 = "com.bt.ACTION_AVRCP_MUSIC_ID3";
    private static final String ACTION_A2DP_RELEASE = "com.bt.ACTION_A2DP_RELEASE";
    private static final String EXTRA_TITLE = "extra_avrcp_id3_title";
    private static final String EXTRA_ARTIST = "extra_avrcp_id3_artist";

    // com.nwd.bt.music answers this by opening in a 1x1 window and moving to the back, with Bluetooth
    // as the car's audio source. Its own Back key would mute Bluetooth instead, so its UI is skipped.
    private static final String ACTION_BACKGROUND_PLAY = "com.nwd.ACTION_BACKGROUND_MEDIA_PLAY";
    private static final String EXTRA_APP_ID = "extra_app_id";
    private static final int BT_MUSIC_APP_ID = 15;

    // Registered by com.nwd.bt.music while it runs, ignored while Bluetooth is muted.
    private static final String ACTION_COMMAND = "com.nwd.ACTION_PLAY_BTMUSIC_CMD";
    private static final String EXTRA_COMMAND = "extra_command";
    private static final int COMMAND_PLAY_PAUSE = 1;
    private static final int COMMAND_PREVIOUS = 2;
    private static final int COMMAND_NEXT = 3;
    private static final long RESTART_THRESHOLD_MS = 4000;
    private static final long RESUME_WAIT_MS = 2500;

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // The play command is a toggle and the module reports play state only on changes, so whether
    // audio flows right now comes from Android: com.bt.bc03 plays the phone's stream itself.
    private final AudioManager audio;

    private String title;
    private String artist;
    private boolean playing;

    // The module never reports the playback position, so it is counted here from the moment a new
    // song's title arrives. A song already halfway through when the title arrives reads as starting.
    private long positionBase;
    private long playingSince;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            long now = SystemClock.elapsedRealtime();
            switch (String.valueOf(intent.getAction())) {
                case ACTION_PLAY:
                    if (!playing) playingSince = now;
                    playing = true;
                    break;
                case ACTION_PAUSE:
                    if (playing) positionBase += now - playingSince;
                    playing = false;
                    break;
                case ACTION_ID3:
                    String newTitle = intent.getStringExtra(EXTRA_TITLE);
                    // On resume an iPhone briefly sends "Title • Artist" / "Listening on iPhone".
                    if (newTitle == null || (title != null && newTitle.startsWith(title + " •"))) return;
                    if (!newTitle.equals(title)) {
                        positionBase = 0;
                        playingSince = now;
                    }
                    title = newTitle;
                    artist = intent.getStringExtra(EXTRA_ARTIST);
                    break;
                case ACTION_A2DP_RELEASE:
                    playing = false;
                    title = null;
                    artist = null;
                    break;
                default: return;
            }
            listener.onBtMusicChanged();
        }
    };

    BtMusic(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        audio = context.getSystemService(AudioManager.class);
    }

    String title() {
        return title;
    }

    String artist() {
        return artist;
    }

    boolean isPlaying() {
        return playing;
    }

    /** The module sometimes sends no play or pause broadcast at all, which would freeze the lyrics timer. */
    void syncWithAudio() {
        boolean active = audio.isMusicActive();
        if (active == playing) return;
        long now = SystemClock.elapsedRealtime();
        if (active) playingSince = now;
        else positionBase += now - playingSince;
        playing = active;
        listener.onBtMusicChanged();
    }

    long positionMs() {
        return positionBase + (playing ? SystemClock.elapsedRealtime() - playingSince : 0);
    }

    /** Moves the counted position when the lyrics run ahead of or behind the phone. */
    void nudge(long ms) {
        positionBase += ms;
    }

    /** Sets the counted position, e.g. to the lyric line the owner taps while it is being sung. */
    void setPosition(long ms) {
        positionBase = ms;
        playingSince = SystemClock.elapsedRealtime();
    }

    // Before Android 13 there is no export flag; the branch below passes it from 13 on.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    void register() {
        if (audio.isMusicActive()) {
            playing = true;
            playingSince = SystemClock.elapsedRealtime();
        }
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PLAY);
        f.addAction(ACTION_PAUSE);
        f.addAction(ACTION_ID3);
        f.addAction(ACTION_A2DP_RELEASE);
        // Sent by com.bt.bc03, another app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, f);
        }
    }

    void unregister() {
        context.unregisterReceiver(receiver);
        handler.removeCallbacksAndMessages(null);
    }

    /**
     * Makes Bluetooth the audio source without showing NWD's player. The phone often resumes by
     * itself; play is pressed only if it is still silent afterwards.
     */
    void playInBackground() {
        context.sendBroadcast(new Intent(ACTION_BACKGROUND_PLAY).putExtra(EXTRA_APP_ID, BT_MUSIC_APP_ID));
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            if (!playing && !audio.isMusicActive()) command(COMMAND_PLAY_PAUSE);
        }, RESUME_WAIT_MS);
    }

    void playPause() {
        if (playing || audio.isMusicActive()) command(COMMAND_PLAY_PAUSE);
        else playInBackground();
    }

    void skip(boolean forward) {
        // Past the first seconds, previous restarts the song on the phone and no new title is sent.
        if (!forward && positionMs() > RESTART_THRESHOLD_MS) setPosition(0);
        command(forward ? COMMAND_NEXT : COMMAND_PREVIOUS);
    }

    private void command(int command) {
        context.sendBroadcast(new Intent(ACTION_COMMAND).putExtra(EXTRA_COMMAND, command));
    }
}
