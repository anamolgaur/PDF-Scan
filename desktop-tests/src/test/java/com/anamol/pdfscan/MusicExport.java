package com.anamol.pdfscan;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/**
 * Renders every soundtrack loop to a WAV file for MP3 encoding (tools/make-music.sh), and writes
 * the track list the app reads. Each file is the loop followed by its first half second, so the
 * player can cut one exact loop out of the decoded MP3 whatever delay the encoder adds.
 *
 * Usage: java com.anamol.pdfscan.MusicExport <wav-dir> <assets/music dir>
 */
public final class MusicExport {
    static final int TAIL = GothicSynth.RATE / 2;

    public static void main(String[] args) throws IOException {
        File wavDir = new File(args[0]);
        File assetDir = new File(args[1]);
        wavDir.mkdirs();
        assetDir.mkdirs();
        StringBuilder json = new StringBuilder("{\n  \"rate\": " + GothicSynth.RATE + ",\n  \"tail\": " + TAIL
                + ",\n  \"tracks\": [\n");
        for (int i = 0; i < Soundtrack.count(); i++) {
            short[] loop = Soundtrack.render(i);
            short[] out = Arrays.copyOf(loop, loop.length + TAIL);
            System.arraycopy(loop, 0, out, loop.length, TAIL);
            String name = fileName(i);
            ChiptuneSynthTest.writeWav(new File(wavDir, name + ".wav").getPath(), out);
            json.append("    {\"file\": \"").append(name).append(".mp3\", \"title\": \"")
                    .append(Soundtrack.TITLES[i]).append("\", \"samples\": ").append(loop.length).append('}')
                    .append(i + 1 < Soundtrack.count() ? ",\n" : "\n");
            System.out.println(name + "  " + loop.length + " samples");
        }
        json.append("  ]\n}\n");
        Files.write(new File(assetDir, "tracks.json").toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String fileName(int i) {
        String slug = Soundtrack.TITLES[i].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return String.format(Locale.ROOT, "%02d-%s", i + 1, slug);
    }
}
