package com.anamol.pdfscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Every track: bars line up with the time signature, levels are healthy, the loop seam is clean. */
public class SoundtrackTest {
    @Test
    public void barsAddUp() {
        List<GothicSynth.Song> songs = new ArrayList<>();
        for (int i = 1; i < Soundtrack.count(); i++) songs.add(Soundtrack.song(i));
        for (GothicSynth.Song s : songs) {
            int bars = s.bars();
            for (GothicSynth.Voice v : s.voices) {
                assertEquals(s.title + ": every part has the same number of bars", bars, v.bars.size());
                for (int i = 0; i < v.bars.size(); i++) {
                    assertEquals(s.title + " " + v.inst + " bar " + (i + 1) + ": " + v.bars.get(i),
                            s.stepsPerBar, GothicSynth.barLength(v.bars.get(i)));
                }
            }
        }
    }

    @Test
    public void onlyChipInstruments() {
        for (int i = 1; i < Soundtrack.count(); i++) {
            if (!Soundtrack.isChip(i)) continue;
            GothicSynth.Song s = Soundtrack.song(i);
            for (GothicSynth.Voice v : s.voices) {
                assertTrue(s.title + " uses " + v.inst, v.inst != GothicSynth.Inst.ORGAN && v.inst != GothicSynth.Inst.HARPSI
                        && v.inst != GothicSynth.Inst.BELL && v.inst != GothicSynth.Inst.TIMPANI);
            }
            assertEquals(s.title + " has no reverb", 0f, s.reverbMix, 0f);
        }
        assertEquals(16, Soundtrack.count());
        assertEquals(Soundtrack.TITLES.length, Soundtrack.MOODS.length);
    }

    @Test
    public void tracksRenderCleanly() throws IOException {
        String dir = System.getProperty("pdfscan.wavdir");
        for (int t = 0; t < Soundtrack.count(); t++) {
            long t0 = System.nanoTime();
            short[] pcm = Soundtrack.render(t);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            double seconds = pcm.length / (double) GothicSynth.RATE;
            long sum = 0;
            int peak = 0;
            for (short v : pcm) {
                sum += (long) v * v;
                peak = Math.max(peak, Math.abs(v));
            }
            double rms = Math.sqrt(sum / (double) pcm.length);
            // seam: the jump from the last sample to the first should be like any other step
            int seam = Math.abs(pcm[0] - pcm[pcm.length - 1]);
            int maxStep = 0;
            for (int i = 1; i < pcm.length; i++) maxStep = Math.max(maxStep, Math.abs(pcm[i] - pcm[i - 1]));
            System.out.printf("%-18s %5.1f s  rms %5.0f  peak %5d  seam %5d (max step %5d)  rendered in %d ms%n",
                    Soundtrack.TITLES[t], seconds, rms, peak, seam, maxStep, ms);
            assertTrue(Soundtrack.TITLES[t] + " long enough", seconds > 20);
            assertTrue(Soundtrack.TITLES[t] + " audible", rms > 2500);
            assertTrue(Soundtrack.TITLES[t] + " no hard clipping", peak <= 30000);
            assertTrue(Soundtrack.TITLES[t] + " clean seam", seam <= maxStep);
            if (dir != null) ChiptuneSynthTest.writeWav(new File(dir, "track" + (t + 1) + ".wav").getPath(), pcm);
        }
    }
}
