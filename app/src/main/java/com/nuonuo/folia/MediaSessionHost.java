package com.nuonuo.folia;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 把网页里 <audio> 的状态搬到 MediaSession：锁屏、通知栏、耳机线控、蓝牙按键。
 *
 * 状态从注入脚本每秒推一次（字段用 \u0001 分隔），指令反向用
 * window.__foliaMediaCmd 送回页面。封面是 http 地址时就地下载成 Bitmap。
 */
final class MediaSessionHost {

    private final FoliaService svc;
    private MediaSession session;

    private String title = "";
    private String artist = "";
    private String album = "";
    private String artUrl = "";
    private String artLoadedFor = "";
    private Bitmap art;
    private boolean playing = false;
    private long position = 0;
    private long duration = 0;
    private String lastNotifKey = "";

    MediaSessionHost(FoliaService svc) {
        this.svc = svc;
    }

    void start() {
        if (session != null) {
            return;
        }
        session = new MediaSession(svc, "Folia");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                command("play", 0);
            }

            @Override
            public void onPause() {
                command("pause", 0);
            }

            @Override
            public void onStop() {
                command("pause", 0);
            }

            @Override
            public void onSkipToNext() {
                command("next", 0);
            }

            @Override
            public void onSkipToPrevious() {
                command("prev", 0);
            }

            @Override
            public void onSeekTo(long pos) {
                command("seek", pos);
            }
        });
        session.setActive(true);
    }

    void stop() {
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        art = null;
        artLoadedFor = "";
    }

    MediaSession.Token token() {
        return session == null ? null : session.getSessionToken();
    }

    boolean isPlaying() {
        return playing;
    }

    String title() {
        return title;
    }

    String subtitle() {
        return artist;
    }

    /** 页面推来的一行状态 */
    void onState(String payload) {
        if (payload == null) {
            return;
        }
        String[] f = payload.split("\u0001", -1);
        if (f.length < 7) {
            return;
        }
        title = f[0];
        artist = f[1];
        album = f[2];
        artUrl = f[3];
        boolean wasPlaying = playing;
        playing = "1".equals(f[4]);
        position = parseLong(f[5]);
        duration = parseLong(f[6]);

        start();
        if (wasPlaying != playing) {
            svc.setPlaying(playing);
        }
        if (session == null) {
            return;
        }
        session.setMetadata(metadata());
        session.setPlaybackState(playbackState());

        if (!artUrl.isEmpty() && !artUrl.equals(artLoadedFor) && artUrl.startsWith("http")) {
            loadArt(artUrl);
        }

        // 每秒都刷通知没必要，只在状态或曲目变化时重建
        String key = playing + "|" + title + "|" + artist + "|" + (art == null);
        if (!key.equals(lastNotifKey)) {
            lastNotifKey = key;
            svc.showMediaNotification();
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    private MediaMetadata metadata() {
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
        if (art != null) {
            b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        }
        return b.build();
    }

    private PlaybackState playbackState() {
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        return new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        position, playing ? 1f : 0f)
                .build();
    }

    private void loadArt(final String url) {
        artLoadedFor = url;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(6000);
                    c.setInstanceFollowRedirects(true);
                    InputStream in = c.getInputStream();
                    // 只解到需要的尺寸，别把大图整张读进内存
                    BitmapFactory.Options probe = new BitmapFactory.Options();
                    probe.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(in, null, probe);
                    in.close();
                    if (probe.outWidth <= 0 || probe.outHeight <= 0) {
                        return;
                    }
                    int sample = 1;
                    while (probe.outWidth / (sample * 2) >= 512) {
                        sample *= 2;
                    }
                    in = c.getInputStream();
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    bmp = BitmapFactory.decodeStream(in, null, opts);
                    in.close();
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) {
                        try {
                            c.disconnect();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (bmp == null) {
                    return;
                }
                art = bmp;
                // 封面到得比状态晚，补一次元数据，锁屏上就有图了
                svc.onMain(new Runnable() {
                    @Override
                    public void run() {
                        if (session != null) {
                            session.setMetadata(metadata());
                            lastNotifKey = "";
                            svc.showMediaNotification();
                        }
                    }
                });
            }
        }, "cover").start();
    }

    /** 把指令送回页面 */
    void command(String cmd, long value) {
        FoliaService.deliverCommand(cmd, value);
    }
}
