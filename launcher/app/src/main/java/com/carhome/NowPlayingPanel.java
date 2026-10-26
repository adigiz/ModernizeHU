package com.carhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Full-screen player over Home: cover, title, controls and lyrics that follow the song. */
final class NowPlayingPanel {

    interface Controls {
        void playPause();

        void skip(boolean forward);

        void nudge(long ms);

        /** The owner tapped a synced line: that line is being sung now. */
        void seekTo(long ms);

        void close();
    }

    private static final long NUDGE_MS = 2000;

    private final Activity activity;
    private final View root;
    private final ImageView art;
    private final TextView title;
    private final TextView artist;
    private final ImageButton play;
    private final View nudge;
    private final ScrollView scroll;
    private final LinearLayout lines;

    private final Controls controls;
    private Lyrics lyrics;
    private int current = -1;

    NowPlayingPanel(Activity activity, ViewGroup parent, Controls controls) {
        this.activity = activity;
        this.controls = controls;
        root = activity.getLayoutInflater().inflate(R.layout.now_playing, parent, false);
        parent.addView(root);
        root.setVisibility(View.GONE);
        art = root.findViewById(R.id.np_art);
        art.setClipToOutline(true);
        title = root.findViewById(R.id.np_title);
        artist = root.findViewById(R.id.np_artist);
        play = root.findViewById(R.id.np_play);
        nudge = root.findViewById(R.id.np_nudge);
        scroll = root.findViewById(R.id.np_scroll);
        lines = root.findViewById(R.id.np_lines);

        play.setOnClickListener(v -> controls.playPause());
        root.findViewById(R.id.np_prev).setOnClickListener(v -> controls.skip(false));
        root.findViewById(R.id.np_next).setOnClickListener(v -> controls.skip(true));
        root.findViewById(R.id.np_earlier).setOnClickListener(v -> controls.nudge(-NUDGE_MS));
        root.findViewById(R.id.np_later).setOnClickListener(v -> controls.nudge(NUDGE_MS));
        root.findViewById(R.id.np_close).setOnClickListener(v -> controls.close());
    }

    boolean isShowing() {
        return root.getVisibility() == View.VISIBLE;
    }

    void show() {
        root.setVisibility(View.VISIBLE);
    }

    void hide() {
        root.setVisibility(View.GONE);
    }

    void bind(CharSequence songTitle, CharSequence songArtist, Bitmap cover, int fallbackArt,
              boolean playing, boolean estimatedPosition) {
        title.setText(songTitle);
        artist.setText(songArtist);
        if (cover != null) art.setImageBitmap(cover);
        else art.setImageResource(fallbackArt);
        play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        nudge.setVisibility(estimatedPosition && lyrics != null && lyrics.synced ? View.VISIBLE : View.GONE);
    }

    /** message: shown instead when there are no lyrics; 0 for none. */
    void setLyrics(Lyrics newLyrics, int message) {
        lyrics = newLyrics;
        current = -1;
        lines.removeAllViews();
        if (lyrics == null) {
            if (message != 0) lines.addView(line(activity.getString(message), false));
        } else {
            for (int i = 0; i < lyrics.lines.length; i++) {
                TextView v = line(lyrics.lines[i], !lyrics.synced);
                if (lyrics.synced) {
                    long at = lyrics.times[i];
                    v.setBackgroundResource(R.drawable.lyric_line);
                    v.setOnClickListener(view -> controls.seekTo(at));
                }
                lines.addView(v);
            }
        }
        scroll.scrollTo(0, 0);
    }

    void setPosition(long positionMs) {
        if (lyrics == null || !lyrics.synced) return;
        int index = lyrics.indexAt(positionMs);
        if (index == current) return;
        if (current >= 0) style((TextView) lines.getChildAt(current), false);
        current = index;
        if (index < 0) {
            scroll.smoothScrollTo(0, 0);
            return;
        }
        TextView view = (TextView) lines.getChildAt(index);
        style(view, true);
        scroll.smoothScrollTo(0, view.getTop() - scroll.getHeight() / 2 + view.getHeight() / 2);
    }

    private TextView line(String text, boolean bright) {
        TextView v = new TextView(activity);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        v.setLineSpacing(0, 1.1f);
        float density = activity.getResources().getDisplayMetrics().density;
        int vertical = Math.round(9 * density);
        int horizontal = Math.round(10 * density);
        v.setPadding(horizontal, vertical, horizontal, vertical);
        style(v, bright);
        return v;
    }

    private void style(TextView v, boolean active) {
        v.setTextColor(activity.getColor(active ? R.color.text : R.color.lyric_dim));
        v.setTypeface(Typeface.create("sans-serif-medium", active ? Typeface.BOLD : Typeface.NORMAL));
    }
}
