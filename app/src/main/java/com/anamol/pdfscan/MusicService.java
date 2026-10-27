package com.anamol.pdfscan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Plays the soundtrack, also while the app is in the background, as a media service with a
 * notification and lock-screen controls (previous, play/pause, next, stop). Tracks are
 * synthesised on the phone one at a time, with the next one prepared ahead.
 * It pauses for calls and other apps' audio, like any music player.
 */
public class MusicService extends Service {
    static final String ACTION_PLAY = "com.anamol.pdfscan.music.PLAY";
    static final String ACTION_PAUSE = "com.anamol.pdfscan.music.PAUSE";
    static final String ACTION_TOGGLE = "com.anamol.pdfscan.music.TOGGLE";
    static final String ACTION_NEXT = "com.anamol.pdfscan.music.NEXT";
    static final String ACTION_PREV = "com.anamol.pdfscan.music.PREV";
    static final String ACTION_STOP = "com.anamol.pdfscan.music.STOP";
    static final String ACTION_TRACK = "com.anamol.pdfscan.music.TRACK";
    static final String EXTRA_TRACK = "track";
    private static final String CHANNEL = "music";
    private static final int NOTIFICATION = 9;
    /** In "play all" mode each track loops until it has played at least this long. */
    private static final double SECONDS_PER_TRACK = 100;

    private static volatile boolean running;
    private static volatile boolean playingNow;
    private static volatile int currentTrack = -1;

    private final Object lock = new Object();
    private final Map<Integer, short[]> cache = new HashMap<>();
    private Thread feeder;
    private AudioTrack track;
    private MediaSession session;
    private AudioFocusRequest focus;
    private volatile boolean playing;
    private volatile boolean stopped;
    private volatile int requested = -1;
    private volatile boolean resumeAfterFocus;
    private boolean foreground;

    static boolean isPlaying() {
        return running && playingNow;
    }

    static int currentTrack() {
        return currentTrack;
    }

    static void send(Context c, String action) {
        send(c, new Intent(c, MusicService.class).setAction(action));
    }

    static void playTrack(Context c, int index) {
        send(c, new Intent(c, MusicService.class).setAction(ACTION_TRACK).putExtra(EXTRA_TRACK, index));
    }

    private static void send(Context c, Intent i) {
        try {
            if (ACTION_PAUSE.equals(i.getAction()) || ACTION_STOP.equals(i.getAction())) {
                if (running) c.startService(i);
            } else {
                c.startForegroundService(i);
            }
        } catch (RuntimeException e) {
            // background start not allowed right now; music starts next time the app opens
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        // older versions kept synthesised tracks here; the MP3s replace them
        DocStore.deleteRecursive(new java.io.File(getCacheDir(), "music"));
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Music", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Controls for the PDF Scan soundtrack");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        session = new MediaSession(this, "PDF Scan soundtrack");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause(false);
            }

            @Override
            public void onSkipToNext() {
                skip(1);
            }

            @Override
            public void onSkipToPrevious() {
                skip(-1);
            }

            @Override
            public void onStop() {
                stopAll();
            }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_PLAY;
        // a foreground service must show its notification promptly
        if (!foreground) goForeground();
        switch (action) {
            case ACTION_PAUSE:
                pause(false);
                break;
            case ACTION_TOGGLE:
                if (playing) pause(false);
                else play();
                break;
            case ACTION_NEXT:
                skip(1);
                break;
            case ACTION_PREV:
                skip(-1);
                break;
            case ACTION_STOP:
                stopAll();
                break;
            case ACTION_TRACK:
                requested = intent.getIntExtra(EXTRA_TRACK, 0);
                Prefs.get(this).edit().putInt("music_track", requested).apply();
                play();
                break;
            default:
                play();
                break;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        savePosition();
        stopped = true;
        playing = false;
        playingNow = false;
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.release();
            } catch (IllegalStateException ignored) {
                // already released
            }
        }
        abandonFocus();
        if (session != null) session.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ controls

    private void play() {
        if (!MusicPlayer.enabled(this)) {
            stopAll();
            return;
        }
        if (!requestFocus()) return;
        playing = true;
        playingNow = true;
        if (feeder == null || !feeder.isAlive()) {
            stopped = false;
            feeder = new Thread(this::feed, "music");
            feeder.start();
        }
        synchronized (lock) {
            if (track != null) {
                try {
                    track.play();
                } catch (IllegalStateException ignored) {
                    // not yet created
                }
            }
            lock.notifyAll();
        }
        goForeground();
    }

    /** Pauses. When not caused by audio focus, the notification can then be swiped away. */
    private void pause(boolean forFocus) {
        playing = false;
        playingNow = false;
        if (!forFocus) resumeAfterFocus = false;
        synchronized (lock) {
            if (track != null) {
                try {
                    track.pause();
                } catch (IllegalStateException ignored) {
                    // released
                }
            }
        }
        if (!forFocus) abandonFocus();
        savePosition();
        updateSession();
        stopForeground(STOP_FOREGROUND_DETACH);
        foreground = false;
        notifyNow();
    }

    private void skip(int delta) {
        int n = Soundtrack.count();
        int cur = currentTrack < 0 ? Prefs.get(this).getInt("music_track", 0) : currentTrack;
        requested = ((cur + delta) % n + n) % n;
        Prefs.get(this).edit().putInt("music_track", requested).apply();
        if (!playing) play();
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void stopAll() {
        stopped = true;
        playing = false;
        playingNow = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        abandonFocus();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ------------------------------------------------------------------ playback thread

    private short[] load(int index) {
        synchronized (cache) {
            short[] pcm = cache.get(index);
            if (pcm != null) return pcm;
        }
        short[] pcm;
        try {
            pcm = Mp3Loops.load(this, index);  // the MP3s in assets/music
        } catch (IOException | RuntimeException e) {
            Diag.log("Music: couldn't decode track " + index + ", synthesising it", e);
            pcm = Soundtrack.render(index);
        }
        synchronized (cache) {
            // keep at most the current and the next track in memory
            if (cache.size() >= 2) cache.clear();
            cache.put(index, pcm);
        }
        return pcm;
    }

    private void prepareAhead(final int index) {
        Thread t = new Thread(() -> load(index), "music-prepare");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Crossfade between tracks of the journey. */
    private static final int FADE = GothicSynth.RATE * 4;
    /** Short ramp when sound starts from silence or after a jump, so there is no click. */
    private static final int RAMP = GothicSynth.RATE / 25;
    private static final int SAVE_EVERY = GothicSynth.RATE * 5;

    private volatile int curPos;
    private volatile long curPlayed;

    /** Remembers where the journey is, so the next open continues from the same spot. */
    private void savePosition() {
        if (currentTrack < 0) return;
        Prefs.get(this).edit()
                .putInt("music_track", currentTrack)
                .putInt("music_pos", curPos)
                .putLong("music_played", curPlayed)
                .apply();
    }

    private void feed() {
        int min = AudioTrack.getMinBufferSize(GothicSynth.RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(GothicSynth.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(min * 4, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        synchronized (lock) {
            track = t;
            t.setVolume(MusicPlayer.curve(MusicPlayer.volume(this)));
            if (playing) t.play();
        }
        int count = Soundtrack.count();
        android.content.SharedPreferences prefs = Prefs.get(this);
        int index;
        int pos;
        long played;
        if (requested >= 0) {
            index = requested;
            pos = 0;
            played = 0;
        } else {
            index = prefs.getInt("music_track", 0);
            pos = prefs.getInt("music_pos", 0);
            played = prefs.getLong("music_played", 0);
        }
        requested = -1;
        index = ((index % count) + count) % count;
        currentTrack = index;
        updateSession();
        notifyNow();
        short[] pcm = load(index);
        if (pos < 0 || pos >= pcm.length) pos = 0;
        boolean playAll = MusicPlayer.playAll(this);
        if (playAll) prepareAhead((index + 1) % count);

        short[] old = null;      // the track fading out
        int oldPos = 0;
        int fadeDone = 0;
        int ramp = pos > 0 ? RAMP : 0;
        int sinceSave = 0;
        short[] buf = new short[2048];

        while (!stopped) {
            synchronized (lock) {
                while (!playing && !stopped && requested < 0) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            if (stopped) break;
            if (requested >= 0) {
                // picked from the list or skipped: jump straight there
                index = ((requested % count) + count) % count;
                requested = -1;
                synchronized (lock) {
                    try {
                        t.pause();
                        t.flush();
                        if (playing) t.play();
                    } catch (IllegalStateException ignored) {
                        // released
                    }
                }
                currentTrack = index;
                pcm = load(index);
                pos = 0;
                played = 0;
                old = null;
                ramp = RAMP;
                playAll = MusicPlayer.playAll(this);
                if (playAll) prepareAhead((index + 1) % count);
                curPos = 0;
                curPlayed = 0;
                savePosition();
                updateSession();
                notifyNow();
                continue;
            }
            if (!playing) continue;
            t.setVolume(MusicPlayer.curve(MusicPlayer.volume(this)));
            int n = Math.min(buf.length, pcm.length - pos);
            if (old != null) n = Math.min(n, FADE - fadeDone);
            for (int k = 0; k < n; k++) {
                float v = pcm[pos + k];
                if (old != null) {
                    double x = (fadeDone + k) / (double) FADE * Math.PI / 2;
                    v = (float) (v * Math.sin(x) + old[(oldPos + k) % old.length] * Math.cos(x));
                }
                if (ramp > 0) {
                    v *= 1f - ramp / (float) RAMP;
                    ramp--;
                }
                buf[k] = (short) Math.max(-32767, Math.min(32767, Math.round(v)));
            }
            int w = 0;
            while (w < n && !stopped) {
                int r = t.write(buf, w, n - w);
                if (r < 0) {
                    stopped = true;
                    break;
                }
                w += r;
            }
            pos += n;
            played += n;
            sinceSave += n;
            if (old != null) {
                oldPos = (oldPos + n) % old.length;
                fadeDone += n;
                if (fadeDone >= FADE) old = null;
            }
            curPos = pos % pcm.length;
            curPlayed = played;
            if (sinceSave >= SAVE_EVERY) {
                sinceSave = 0;
                savePosition();
            }
            if (pos >= pcm.length) {
                pos = 0;
                if (playAll && played / (double) GothicSynth.RATE >= SECONDS_PER_TRACK) {
                    // move on along the journey: the old track keeps looping underneath while it fades out
                    old = pcm;
                    oldPos = 0;
                    fadeDone = 0;
                    index = (index + 1) % count;
                    pcm = load(index);
                    played = 0;
                    currentTrack = index;
                    prepareAhead((index + 1) % count);
                    curPos = 0;
                    curPlayed = 0;
                    savePosition();
                    updateSession();
                    notifyNow();
                }
            }
        }
        savePosition();
        currentTrack = -1;
    }

    // ------------------------------------------------------------------ notification and session

    private void goForeground() {
        Notification n = notification();
        try {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            foreground = true;
        } catch (RuntimeException e) {
            // not allowed to start in the foreground now
            foreground = false;
        }
    }

    private void notifyNow() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && running && !stopped) nm.notify(NOTIFICATION, notification());
    }

    private PendingIntent action(String action, int code) {
        Intent i = new Intent(this, MusicService.class).setAction(action);
        return PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notification() {
        int idx = currentTrack >= 0 ? currentTrack : Prefs.get(this).getInt("music_track", 0);
        idx = Math.max(0, Math.min(Soundtrack.count() - 1, idx));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_music)
                .setColor(Ui.MAROON)
                .setContentTitle(Soundtrack.TITLES[idx])
                .setContentText("PDF Scan · " + Soundtrack.MOODS[idx])
                .setContentIntent(open)
                .setDeleteIntent(action(ACTION_STOP, 15))
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev),
                        "Previous", action(ACTION_PREV, 11)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this,
                        playing ? R.drawable.ic_pause : R.drawable.ic_play),
                        playing ? "Pause" : "Play", action(ACTION_TOGGLE, 12)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next),
                        "Next", action(ACTION_NEXT, 13)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_close),
                        "Stop", action(ACTION_STOP, 14)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private void updateSession() {
        if (session == null) return;
        int idx = currentTrack >= 0 ? currentTrack : 0;
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, Soundtrack.TITLES[idx])
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "PDF Scan")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Soundtrack")
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
    }

    // ------------------------------------------------------------------ audio focus

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            pause(false);  // another app started playing: stay paused until asked
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            boolean was = playing;
            pause(true);
            resumeAfterFocus = was;
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeAfterFocus) {
            resumeAfterFocus = false;
            play();
        }
    };

    private boolean requestFocus() {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return true;
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes())
                    .setOnAudioFocusChangeListener(focusListener)
                    .setWillPauseWhenDucked(true)
                    .build();
        }
        return am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        AudioManager am = getSystemService(AudioManager.class);
        if (am != null && focus != null) am.abandonAudioFocusRequest(focus);
    }
}
