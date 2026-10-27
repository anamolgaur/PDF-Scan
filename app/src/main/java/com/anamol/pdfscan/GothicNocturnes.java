package com.anamol.pdfscan;

import java.util.ArrayList;
import java.util.List;

/**
 * Four more gothic pieces for the soundtrack: a candlelit organ requiem, a harpsichord
 * masquerade in 3/4, a bell crypt and a fast tower chase. All melodies are original.
 */
final class GothicNocturnes {
    private GothicNocturnes() {
    }

    private static List<String> list(String... bars) {
        List<String> l = new ArrayList<>();
        for (String b : bars) l.add(b);
        return l;
    }

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        List<String> l = new ArrayList<>();
        for (List<String> p : parts) l.addAll(p);
        return l;
    }

    private static List<String> repeat(String bar, int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(bar);
        return l;
    }

    /** Each entry held for a whole bar of the given length. */
    private static List<String> held(int steps, String... notes) {
        List<String> l = new ArrayList<>();
        for (String n : notes) l.add(n + ":" + steps);
        return l;
    }

    /** Broken chord: pattern indexes into the chord tones, each note `len` steps. */
    private static List<String> arps(String pattern, int len, String... chords) {
        List<String> l = new ArrayList<>();
        for (String c : chords) {
            String[] t = c.split(" ");
            StringBuilder sb = new StringBuilder();
            for (String p : pattern.split(" ")) {
                sb.append(t[Integer.parseInt(p) % t.length]);
                if (len > 1) sb.append(':').append(len);
                sb.append(' ');
            }
            l.add(sb.toString().trim());
        }
        return l;
    }

    private static List<String> gallop(String... roots) {
        List<String> l = new ArrayList<>();
        for (String r : roots) {
            String o = Soundtrack.transpose(r, 12);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 4; k++) sb.append(r).append(":2 ").append(r).append(' ').append(o).append(' ');
            l.add(sb.toString().trim());
        }
        return l;
    }

    // ------------------------------------------------------------------ Requiem of Candles

    /** G minor organ chorale, slow, with bells and a soft timpani roll at each cadence. */
    static GothicSynth.Song requiem() {
        GothicSynth.Song s = new GothicSynth.Song("Requiem of Candles", 60, 4, 16);
        s.reverbMix = 0.7f;
        s.reverbSize = 0.88f;
        s.echoFeedback = 0f;
        s.loudness = 0.17f;
        s.voice(GothicSynth.Inst.ORGAN, 0.28f).reverb(0.7f).add(held(16,
                "G3+A#3+D4", "G3+A#3+D#4", "G3+C4+D#4", "F#3+A3+C4",
                "G3+A#3+D4", "F3+A#3+D4", "G3+C4+D#4", "F#3+A3+D4",
                "G3+A#3+D#4", "F3+A3+C4", "F3+A#3+D4", "G3+A#3+D4",
                "G3+C4+D#4", "A3+C4+D#4", "F#3+A3+C4", "G3+A#3+D4"));
        s.voice(GothicSynth.Inst.ORGAN, 0.25f).reverb(0.6f).add(held(16,
                "G2", "D#2", "C2", "D2", "G2", "A#1", "C2", "D2",
                "D#2", "F2", "A#1", "G1", "C2", "A1", "D2", "G1"));
        s.voice(GothicSynth.Inst.ORGAN, 0.25f).reverb(0.7f).add(
                "D5:8 A#4:4 G4:4", "D#5:8 D5:4 C5:4", "C5:8 D#5:4 G5:4", "F#5:8 D5:8",
                "G5:6 F5:2 D5:4 A#4:4", "F5:8 D5:4 A#4:4", "C5:4 D#5:4 G5:4 D#5:4", "D5:16",
                "G5:8 A#5:4 G5:4", "A5:8 F5:4 C5:4", "D5:6 F5:2 A#5:8", "A#5:4 A5:4 G5:8",
                "G5:4 D#5:4 C5:4 D#5:4", "F#5:8 D#5:4 C5:4", "A4:4 C5:4 F#5:4 A5:4", "G5:16");
        s.voice(GothicSynth.Inst.BELL, 0.15f).reverb(0.8f).add(
                "G5 .:15", ".:16", ".:16", ".:16", "D5 .:15", ".:16", ".:16", ".:16",
                "D#5 .:15", ".:16", ".:16", ".:16", "C5 .:15", ".:16", ".:16", "G4 .:7 D5 .:7");
        String roll = "D2 .:7 D2 .:3 D2 .:3";
        s.voice(GothicSynth.Inst.TIMPANI, 0.30f).reverb(0.5f).add(
                "G1 .:15", ".:16", ".:16", roll, ".:16", ".:16", ".:16", roll,
                ".:16", ".:16", ".:16", ".:16", ".:16", ".:16", roll, "G1 .:15");
        return s;
    }

    // ------------------------------------------------------------------ Masquerade of Thorns

    /** C minor harpsichord dance in 3/4 over an organ bass: a masked ball in a cold castle. */
    static GothicSynth.Song masquerade() {
        GothicSynth.Song s = new GothicSynth.Song("Masquerade of Thorns", 132, 4, 12);
        s.reverbMix = 0.4f;
        s.echoFeedback = 0f;
        List<String> leadA = list(
                "G5:4 D#5:2 C5:2 D#5:2 G5:2",
                "F5:4 D5:2 B4:2 D5:2 F5:2",
                "D#5:2 D5:2 C5:2 D5:2 D#5:2 G5:2",
                "G#5:6 G5:2 F5:4",
                "D#5:4 C5:2 G#4:2 C5:2 D#5:2",
                "D5:2 C5:2 G#4:2 F4:2 G#4:2 C5:2",
                "B4:4 D5:4 F5:4",
                "D5:6 B4:2 G4:4");
        List<String> leadB = list(
                "A#5:4 G5:2 D#5:2 G5:2 A#5:2",
                "A#5:2 A5:2 A#5:2 F5:2 D5:2 A#4:2",
                "C6:4 G5:2 D#5:2 C5:2 D#5:2",
                "D5:4 B4:2 G4:2 B4:2 D5:2",
                "C6:6 G#5:2 D#5:4",
                "C5:2 D5:2 D#5:2 F5:2 G#5:2 C6:2",
                "B5:4 G5:2 F5:2 D5:2 B4:2",
                "C5:12");
        s.voice(GothicSynth.Inst.HARPSI, 0.30f).reverb(0.35f).add(concat(leadA, leadB, leadA));
        String p = "0 2 1 2 0 2";
        List<String> accA = arps(p, 2, "C4 D#4 G4", "G3 B3 D4", "C4 D#4 G4", "F3 G#3 C4",
                "G#3 C4 D#4", "F3 G#3 C4", "G3 B3 D4", "G3 B3 D4");
        List<String> accB = arps(p, 2, "G3 A#3 D#4", "F3 A#3 D4", "C4 D#4 G4", "G3 B3 D4",
                "G#3 C4 D#4", "F3 G#3 C4", "G3 B3 F4", "C4 D#4 G4");
        s.voice(GothicSynth.Inst.HARPSI, 0.20f).reverb(0.3f).add(concat(accA, accB, accA));
        List<String> bassA = held(12, "C3", "G2", "C3", "F2", "G#2", "F2", "G2", "G2");
        List<String> bassB = held(12, "D#3", "A#2", "C3", "G2", "G#2", "F2", "G2", "C3");
        s.voice(GothicSynth.Inst.ORGAN, 0.22f).reverb(0.45f).add(concat(bassA, bassB, bassA));
        List<String> timp = repeat(".:12", 24);
        for (int i : new int[]{0, 4, 8, 12, 16, 20}) timp.set(i, (i % 8 == 0 ? "C2" : "G1") + " .:11");
        timp.set(15, "G1 .:3 G1 .:3 C2 .:3");
        s.voice(GothicSynth.Inst.TIMPANI, 0.28f).reverb(0.4f).add(timp);
        List<String> bell = repeat(".:12", 24);
        bell.set(7, ".:8 G5 .:3");
        bell.set(15, "C6 .:11");
        bell.set(23, ".:8 G5 .:3");
        s.voice(GothicSynth.Inst.BELL, 0.12f).reverb(0.7f).add(bell);
        return s;
    }

    // ------------------------------------------------------------------ Crypt of Bells

    /** E minor: a slow bell ostinato under a harpsichord line, organ air and a timpani heartbeat. */
    static GothicSynth.Song crypt() {
        GothicSynth.Song s = new GothicSynth.Song("Crypt of Bells", 84, 4, 16);
        s.reverbMix = 0.6f;
        s.reverbSize = 0.86f;
        s.echoFeedback = 0f;
        s.loudness = 0.18f;
        String[] chords = {"E4 G4 B4", "C4 E4 G4", "A3 C4 E4", "B3 D#4 F#4",
                "E4 G4 B4", "D4 G4 B4", "A3 C4 E4", "B3 D#4 F#4",
                "C4 E4 G4", "D4 F#4 A4", "D4 G4 B4", "E4 G4 B4",
                "A3 C4 E4", "F#3 A3 C4", "B3 D#4 A4", "E4 G4 B4"};
        List<String> pad = new ArrayList<>();
        List<String> bells = new ArrayList<>();
        for (String c : chords) {
            pad.add(c.replace(' ', '+') + ":16");
            String[] t = c.split(" ");
            String hi0 = Soundtrack.transpose(t[0], 12);
            String hi1 = Soundtrack.transpose(t[1], 12);
            String hi2 = Soundtrack.transpose(t[2], 12);
            bells.add(hi2 + ":4 " + hi0 + ":4 " + hi1 + ":4 " + hi0 + ":4");
        }
        s.voice(GothicSynth.Inst.ORGAN, 0.18f).reverb(0.7f).add(pad);
        s.voice(GothicSynth.Inst.BELL, 0.11f).reverb(0.8f).add(bells);
        s.voice(GothicSynth.Inst.ORGAN, 0.22f).reverb(0.6f).add(held(16,
                "E2", "C2", "A1", "B1", "E2", "G1", "A1", "B1",
                "C2", "D2", "G1", "E2", "A1", "F#1", "B1", "E2"));
        s.voice(GothicSynth.Inst.HARPSI, 0.26f).reverb(0.45f).add(
                "B4:8 E5:4 G5:4", "G5:6 E5:2 C5:8", "A4:4 C5:4 E5:4 A5:4", "F#5:12 D#5:4",
                "E5:6 F#5:2 G5:4 B5:4", "D5:8 B4:8", "C5:4 E5:4 A5:4 G5:4", "F#5:16",
                "E5:4 G5:4 C6:8", "A5:4 F#5:4 D5:8", "G5:6 F#5:2 G5:4 B5:4", "B5:8 G5:4 E5:4",
                "A5:4 E5:4 C5:4 E5:4", "D#5:8 F#5:4 A5:4", "B5:4 A5:4 G5:4 F#5:4", "E5:16");
        List<String> heart = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            boolean dominant = chords[i].startsWith("B3");
            heart.add(dominant ? "B1 . B1 .:13" : "E2 . E2 .:13");
        }
        s.voice(GothicSynth.Inst.TIMPANI, 0.26f).reverb(0.4f).add(heart);
        return s;
    }

    // ------------------------------------------------------------------ Raven Spire

    /** A minor, fast: organ stabs, harpsichord sixteenths, a pulse lead and a galloping bass. */
    static GothicSynth.Song ravenSpire() {
        GothicSynth.Song s = new GothicSynth.Song("Raven Spire", 168, 4, 16);
        s.reverbMix = 0.25f;
        s.echoSteps = 3;
        s.echoFeedback = 0.3f;
        List<String> leadA = list(
                "A5:2 E5:2 A5:2 B5:2 C6:4 B5:2 A5:2",
                "G#5:2 A5:2 B5:2 E5:2 A5:8",
                "C6:2 A5:2 F5:2 A5:2 C6:3 D6:1 C6:2 B5:2",
                "B5:2 G5:2 D5:2 G5:2 B5:4 D6:4",
                "E6:4 C6:2 A5:2 E6:2 D6:2 C6:2 B5:2",
                "A5:2 F5:2 D5:2 F5:2 A5:2 D6:2 C6:2 A5:2",
                "B5:2 G#5:2 E5:2 G#5:2 B5:2 D6:2 C6:2 B5:2",
                "G#5:4 A5:2 B5:2 E5:8");
        List<String> leadB = list(
                "C6:6 D6:2 E6:4 D6:2 C6:2",
                "B5:6 A5:2 G5:8",
                "G5:2 B5:2 E6:4 D6:2 C6:2 B5:2 G5:2",
                "A5:4 E5:4 C5:4 E5:2 A5:2",
                "F5:6 E5:2 D5:4 F5:2 A5:2",
                "G#5:6 B5:2 E6:8",
                "F6:4 E6:2 D6:2 C6:2 B5:2 A5:2 F5:2",
                "E5:2 F5:2 G#5:2 A5:2 B5:4 G#5:4");
        s.voice(GothicSynth.Inst.PULSE25, 0.24f).echo(0.4f).reverb(0.25f).add(concat(leadA, leadB, leadA));
        String[] a = {"A3 C4 E4", "A3 C4 E4", "F3 A3 C4", "G3 B3 D4", "A3 C4 E4", "D4 F4 A4", "E3 G#3 B3", "E3 G#3 B3"};
        String[] b = {"F3 A3 C4", "G3 B3 D4", "E3 G3 B3", "A3 C4 E4", "D4 F4 A4", "E3 G#3 B3", "F3 A3 C4", "E3 G#3 B3"};
        String p = "0 1 2 1 0 1 2 1 0 1 2 1 0 1 2 1";
        s.voice(GothicSynth.Inst.HARPSI, 0.16f).reverb(0.25f).add(concat(arps(p, 1, a), arps(p, 1, b), arps(p, 1, a)));
        List<String> stabA = new ArrayList<>();
        List<String> stabB = new ArrayList<>();
        for (String c : a) {
            String ch = c.replace(' ', '+');
            stabA.add(ch + ":2 .:2 " + ch + ":2 .:6 " + ch + ":2 .:2");
        }
        for (String c : b) {
            String ch = c.replace(' ', '+');
            stabB.add(ch + ":2 .:2 " + ch + ":2 .:6 " + ch + ":2 .:2");
        }
        s.voice(GothicSynth.Inst.ORGAN, 0.20f).reverb(0.3f).add(concat(stabA, stabB, stabA));
        s.voice(GothicSynth.Inst.TRI, 0.34f).add(concat(
                gallop("A1", "A1", "F2", "G2", "A1", "D2", "E2", "E2"),
                gallop("F2", "G2", "E2", "A1", "D2", "E2", "F2", "E2"),
                gallop("A1", "A1", "F2", "G2", "A1", "D2", "E2", "E2")));
        s.voice(GothicSynth.Inst.KICK, 0.55f).add(repeat("X . . . . . . . x . . x . . . .", 24));
        s.voice(GothicSynth.Inst.SNARE, 0.30f).add(repeat(". . . . x . . . . . . . x . x .", 24));
        s.voice(GothicSynth.Inst.HAT, 0.09f).add(repeat("x . x . x . x . x . x . x . x .", 24));
        List<String> timp = repeat(".:16", 24);
        for (int i : new int[]{7, 15, 23}) timp.set(i, "E2 .:3 E2 .:3 E2 .:3 E2 .:3");
        s.voice(GothicSynth.Inst.TIMPANI, 0.30f).reverb(0.3f).add(timp);
        return s;
    }
}
