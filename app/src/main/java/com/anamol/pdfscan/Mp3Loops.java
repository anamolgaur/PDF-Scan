package com.anamol.pdfscan;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Plays the soundtrack from the MP3 files in assets/music. Each file holds one loop of a track
 * followed by its first half second; the player decodes it and cuts out exactly one loop
 * (the length is in tracks.json), so tracks repeat without a gap or a click.
 */
final class Mp3Loops {
    /** Where the loop is cut from: past any encoder delay, inside the repeated half second. */
    private static final int OFFSET = 2400;
    /** Samples blended at the loop point, so the end flows into the start. */
    private static final int BLEND = 256;
    private static JSONArray tracks;

    private Mp3Loops() {
    }

    private static synchronized JSONArray tracks(Context c) throws IOException {
        if (tracks == null) {
            try (InputStream in = c.getAssets().open("music/tracks.json")) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                tracks = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8)).getJSONArray("tracks");
            } catch (org.json.JSONException e) {
                throw new IOException("Track list is damaged");
            }
        }
        return tracks;
    }

    static int count(Context c) {
        try {
            return tracks(c).length();
        } catch (IOException e) {
            return 0;
        }
    }

    static String file(Context c, int index) throws IOException {
        return tracks(c).optJSONObject(index).optString("file");
    }

    /** One seamless loop of a track as 16-bit mono PCM at GothicSynth.RATE. */
    static short[] load(Context c, int index) throws IOException {
        JSONObject t = tracks(c).optJSONObject(index);
        if (t == null) throw new IOException("No such track");
        int loop = t.optInt("samples");
        short[] all = decode(c, "music/" + t.optString("file"));
        if (all.length < OFFSET + loop + BLEND || loop <= BLEND) {
            // unexpected decoder output: play what there is
            return all;
        }
        short[] out = new short[loop];
        System.arraycopy(all, OFFSET, out, 0, loop);
        // the decoded audio after the loop is the start again: fade from it into the loop's start
        for (int k = 0; k < BLEND; k++) {
            float w = k / (float) BLEND;
            out[k] = (short) Math.round(all[OFFSET + loop + k] * (1 - w) + out[k] * w);
        }
        return out;
    }

    /** Decodes an MP3 asset to mono 16-bit PCM. */
    private static short[] decode(Context c, String asset) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try (AssetFileDescriptor fd = c.getAssets().openFd(asset)) {
            ex.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            MediaFormat format = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    ex.selectTrack(i);
                    format = f;
                    break;
                }
            }
            if (format == null) throw new IOException("No audio in " + asset);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            ShortBuilder pcm = new ShortBuilder(1 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long deadline = System.currentTimeMillis() + 15000;
            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) throw new IOException("Decoding took too long");
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (out >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(out);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        java.nio.ShortBuffer sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                        int frames = sb.remaining() / Math.max(1, channels);
                        for (int i = 0; i < frames; i++) {
                            int sum = 0;
                            for (int ch = 0; ch < channels; ch++) sum += sb.get(i * channels + ch);
                            pcm.add((short) (sum / Math.max(1, channels)));
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            return pcm.toArray();
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (IllegalStateException ignored) {
                    // not started
                }
                codec.release();
            }
            ex.release();
        }
    }

    private static final class ShortBuilder {
        private short[] a;
        private int n;

        ShortBuilder(int cap) {
            a = new short[cap];
        }

        void add(short v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }

        short[] toArray() {
            return java.util.Arrays.copyOf(a, n);
        }
    }
}
