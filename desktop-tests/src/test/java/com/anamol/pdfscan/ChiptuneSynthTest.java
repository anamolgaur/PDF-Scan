package com.anamol.pdfscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import org.junit.Test;

public class ChiptuneSynthTest {
    @Test
    public void noteNumbers() {
        assertEquals(69, ChiptuneSynth.midi("A4"));
        assertEquals(68, ChiptuneSynth.midi("G#4"));
        assertEquals(60, ChiptuneSynth.midi("C4"));
        assertEquals(440.0, ChiptuneSynth.freq(69), 1e-9);
    }

    @Test
    public void loopHasExpectedLengthAndHealthyLevels() throws IOException {
        short[] pcm = ChiptuneSynth.render();
        double seconds = pcm.length / (double) ChiptuneSynth.RATE;
        // 24 bars of 4 beats at 150 BPM = 38.4 s
        assertEquals(38.4, seconds, 0.05);
        long sumSq = 0;
        int peak = 0;
        int clipped = 0;
        for (short s : pcm) {
            sumSq += (long) s * s;
            peak = Math.max(peak, Math.abs(s));
            if (Math.abs(s) >= 32000) clipped++;
        }
        double rms = Math.sqrt(sumSq / (double) pcm.length);
        System.out.printf("music: %.1f s, peak %d, rms %.0f%n", seconds, peak, rms);
        assertTrue("audible", rms > 2000);
        assertTrue("not clipping", clipped == 0);
        // loop seam: the last samples are near silence so the loop restarts cleanly
        int tail = 0;
        for (int i = pcm.length - 200; i < pcm.length; i++) tail = Math.max(tail, Math.abs(pcm[i]));
        assertTrue("quiet loop end, was " + tail, tail < 12000);

        String out = System.getProperty("pdfscan.wav");
        if (out != null) writeWav(out, pcm);
    }

    static void writeWav(String path, short[] pcm) throws IOException {
        try (DataOutputStream d = new DataOutputStream(new FileOutputStream(path))) {
            int bytes = pcm.length * 2;
            d.writeBytes("RIFF");
            d.writeInt(Integer.reverseBytes(36 + bytes));
            d.writeBytes("WAVEfmt ");
            d.writeInt(Integer.reverseBytes(16));
            d.writeShort(Short.reverseBytes((short) 1));
            d.writeShort(Short.reverseBytes((short) 1));
            d.writeInt(Integer.reverseBytes(ChiptuneSynth.RATE));
            d.writeInt(Integer.reverseBytes(ChiptuneSynth.RATE * 2));
            d.writeShort(Short.reverseBytes((short) 2));
            d.writeShort(Short.reverseBytes((short) 16));
            d.writeBytes("data");
            d.writeInt(Integer.reverseBytes(bytes));
            for (short s : pcm) d.writeShort(Short.reverseBytes(s));
        }
    }
}
