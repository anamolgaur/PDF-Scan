package com.anamol.pdfscan;

import java.util.ArrayList;
import java.util.List;

/**
 * The original gothic arrangements (pipe organ, harpsichord, bells, timpani, reverb), kept as
 * part of the soundtrack alongside their 8-bit versions. All melodies are original.
 */
final class GothicClassics {
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

    private static String transpose(String note, int semis) {
        int m = ChiptuneSynth.midi(note) + semis;
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        return names[m % 12] + (m / 12 - 1);
    }

    /** Broken chord over one bar: pattern of indexes into the chord tones, one step each. */
    private static String arp(String tones, String pattern) {
        String[] t = tones.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : pattern.split(" ")) sb.append(t[Integer.parseInt(p) % t.length]).append(' ');
        return sb.toString().trim();
    }

    private static List<String> arps(String pattern, String... chords) {
        List<String> l = new ArrayList<>();
        for (String c : chords) l.add(arp(c, pattern));
        return l;
    }

    /** Castle-chase bass: eighth + two sixteenths per beat, root and octave. */
    private static List<String> gallop(String... roots) {
        List<String> l = new ArrayList<>();
        for (String r : roots) {
            String o = transpose(r, 12);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 4; k++) sb.append(r).append(":2 ").append(r).append(' ').append(o).append(' ');
            l.add(sb.toString().trim());
        }
        return l;
    }

    // ------------------------------------------------------------------ 2. Night Stair

    /** Fast D harmonic minor chase: echoing pulse lead over a galloping bass. */
    static GothicSynth.Song nightStair() {
        GothicSynth.Song s = new GothicSynth.Song("Night Stair", 160, 4, 16);
        s.echoSteps = 3;
        s.echoFeedback = 0.32f;
        s.reverbMix = 0.22f;
        List<String> leadA = list(
                "D5:2 A4:2 D5:2 E5:2 F5:4 E5:2 D5:2",
                "C#5:2 D5:2 E5:2 A4:2 D5:8",
                "F5:2 D5:2 A#4:2 D5:2 F5:3 G5:1 F5:2 E5:2",
                "E5:2 C5:2 G4:2 C5:2 E5:4 G5:4",
                "A5:4 F5:2 D5:2 A5:2 G5:2 F5:2 E5:2",
                "D5:2 A#4:2 G4:2 A#4:2 D5:2 G5:2 F5:2 D5:2",
                "E5:2 C#5:2 A4:2 C#5:2 E5:2 G5:2 F5:2 E5:2",
                "C#5:4 D5:2 E5:2 A4:8");
        List<String> leadB = list(
                "G5:6 A5:2 A#5:4 A5:2 G5:2",
                "F5:6 E5:2 D5:8",
                "D5:2 F5:2 A#5:4 A5:2 G5:2 F5:2 D5:2",
                "E5:4 C#5:4 A4:4 C#5:2 E5:2",
                "A#5:6 A5:2 G5:4 F5:2 G5:2",
                "A5:6 G5:2 F5:4 E5:2 D5:2",
                "D5:2 E5:2 F5:2 G5:2 A#5:4 G#5:4",
                "A5:12 .:4");
        s.voice(GothicSynth.Inst.PULSE25, 0.30f).echo(0.45f).reverb(0.3f).add(concat(leadA, leadB, leadA));

        String a = "0 1 2 1 0 1 2 1 0 1 2 1 0 1 2 1";
        List<String> harmA = arps(a, "D4 F4 A4", "D4 F4 A4", "A#3 D4 F4", "C4 E4 G4",
                "D4 F4 A4", "G3 A#3 D4", "A3 C#4 E4", "A3 C#4 E4");
        List<String> harmB = arps(a, "G3 A#3 D4", "D4 F4 A4", "A#3 D4 F4", "A3 C#4 E4",
                "G3 A#3 D4", "D4 F4 A4", "E4 G4 A#4", "A3 C#4 E4");
        s.voice(GothicSynth.Inst.PULSE12, 0.075f).reverb(0.2f).add(concat(harmA, harmB, harmA));

        List<String> bassA = gallop("D2", "D2", "A#1", "C2", "D2", "G1", "A1", "A1");
        List<String> bassB = gallop("G1", "D2", "A#1", "A1", "G1", "D2", "A#1", "A1");
        s.voice(GothicSynth.Inst.TRI, 0.34f).add(concat(bassA, bassB, bassA));

        s.voice(GothicSynth.Inst.KICK, 0.55f).add(repeat("X . . . . . . . x . . x . . . .", 24));
        s.voice(GothicSynth.Inst.SNARE, 0.30f).add(repeat(". . . . x . . . . . . . x . . x", 24));
        s.voice(GothicSynth.Inst.HAT, 0.10f).add(repeat("x . x . x . x . x . x . x . x .", 24));
        return s;
    }

    // ------------------------------------------------------------------ 3. Cathedral of Ash

    /** Slow D minor organ with Phrygian E-flat, bells and timpani; second half adds a toccata. */
    static GothicSynth.Song cathedral() {
        GothicSynth.Song s = new GothicSynth.Song("Cathedral of Ash", 66, 4, 16);
        s.reverbMix = 0.75f;
        s.reverbSize = 0.88f;
        s.echoFeedback = 0f;
        s.loudness = 0.17f;
        String[] chords = {"D3+F3+A3+D4", "D#3+G3+A#3+D#4", "D3+F3+A3+D4", "C#3+E3+G3+A#3",
                "G3+A#3+D4", "D#3+G3+A#3", "A2+E3+G3+C#4", "D3+F3+A3+D4"};
        List<String> chordBars = new ArrayList<>();
        for (String c : chords) chordBars.add(c + ":16");
        s.voice(GothicSynth.Inst.ORGAN, 0.30f).reverb(0.7f).add(concat(chordBars, chordBars));

        String[] pedal = {"D2", "D#2", "D2", "A1", "G1", "D#2", "A1", "D2"};
        List<String> pedalBars = new ArrayList<>();
        for (String p : pedal) pedalBars.add(p + ":16");
        s.voice(GothicSynth.Inst.ORGAN, 0.26f).reverb(0.6f).add(concat(pedalBars, pedalBars));

        List<String> melody = list(
                "A4:8 D5:8",
                "A#4:12 G4:4",
                "A4:4 F4:4 D4:8",
                "E4:8 G4:4 A#4:4",
                "D5:8 A#4:4 G4:4",
                "D#5:8 D5:4 C5:4",
                "C#5:8 E5:4 G5:4",
                "F5:4 E5:4 D5:8");
        List<String> high = new ArrayList<>();
        for (String bar : melody) {
            StringBuilder sb = new StringBuilder();
            for (String tok : bar.split(" ")) {
                String[] p = tok.split(":");
                sb.append(transpose(p[0], 12)).append(':').append(p[1]).append(' ');
            }
            high.add(sb.toString().trim());
        }
        s.voice(GothicSynth.Inst.ORGAN, 0.26f).reverb(0.7f).add(concat(melody, high));

        String t = "0 1 2 3 2 1 2 3 0 1 2 3 2 1 2 3";
        List<String> toccata = arps(t, "D4 F4 A4 D5", "D#4 G4 A#4 D#5", "D4 F4 A4 D5", "C#4 E4 G4 A#4",
                "D4 G4 A#4 D5", "D#4 G4 A#4 D#5", "C#4 E4 G4 A4", "D4 F4 A4 D5");
        s.voice(GothicSynth.Inst.HARPSI, 0.20f).reverb(0.5f).add(concat(repeat(".:16", 8), toccata));

        s.voice(GothicSynth.Inst.BELL, 0.16f).reverb(0.8f).add(
                "D5 .:15", ".:16", ".:16", ".:16", "G4 .:15", ".:16", "A4 .:15", ".:16",
                "D5 .:15", ".:16", ".:16", ".:16", "G4 .:15", ".:16", "A4 .:15", "D4 .:15");
        s.voice(GothicSynth.Inst.TIMPANI, 0.32f).reverb(0.5f).add(
                "D2 .:15", ".:16", ".:16", "A1 .:7 A1 .:3 A1 .:3", ".:16", ".:16", "A1 .:15", "D2 .:15",
                "D2 .:15", ".:16", ".:16", "A1 .:7 A1 .:3 A1 .:3", ".:16", ".:16", "A1 .:7 A1 .:3 A1 .:3", "D2 .:15");
        return s;
    }

    // ------------------------------------------------------------------ 4. Clocktower Waltz

    /** A minor harmonic waltz: harpsichord oom-pah-pah, a ticking clock and a chime. */
    static GothicSynth.Song clocktower() {
        GothicSynth.Song s = new GothicSynth.Song("Clocktower Waltz", 138, 4, 12);
        s.echoSteps = 4;
        s.echoFeedback = 0.25f;
        s.reverbMix = 0.35f;
        String am = "A3+C4+E4";
        String dm = "A3+D4+F4";
        String e = "G#3+B3+E4";
        String f = "A3+C4+F4";
        String[][] a = {{"A2", am}, {"A2", am}, {"D2", dm}, {"E2", e}, {"A2", am}, {"F2", f}, {"E2", e}, {"E2", e}};
        String[][] b = {{"D2", dm}, {"A2", am}, {"E2", e}, {"A2", am}, {"F2", f}, {"D2", dm}, {"E2", e}, {"A2", am}};
        List<String> accA = new ArrayList<>();
        List<String> accB = new ArrayList<>();
        for (String[] c : a) accA.add(c[0] + ":4 " + c[1] + ":4 " + c[1] + ":4");
        for (String[] c : b) accB.add(c[0] + ":4 " + c[1] + ":4 " + c[1] + ":4");
        s.voice(GothicSynth.Inst.HARPSI, 0.34f).reverb(0.35f).add(concat(accA, accB, accA));

        List<String> leadA = list(
                "E5:4 A5:4 B5:2 C6:2",
                "B5:4 A5:4 E5:4",
                "F5:4 D5:4 A4:2 D5:2",
                "G#4:4 B4:4 E5:4",
                "C5:2 E5:2 A5:4 G#5:2 A5:2",
                "C6:4 A5:4 F5:4",
                "E5:2 F5:2 G#5:4 B5:2 D6:2",
                "C6:4 B5:4 G#5:4");
        List<String> leadB = list(
                "A5:6 F5:2 D5:4",
                "E5:6 C5:2 A4:4",
                "G#4:4 B4:4 D5:2 F5:2",
                "E5:8 .:4",
                "A4:2 C5:2 F5:4 E5:2 F5:2",
                "D5:2 F5:2 A5:4 G#5:2 A5:2",
                "B5:4 G#5:4 E5:2 D5:2",
                "A4:8 .:4");
        s.voice(GothicSynth.Inst.PULSE25, 0.19f).echo(0.35f).reverb(0.35f).add(concat(leadA, leadB, leadA));

        s.voice(GothicSynth.Inst.HAT, 0.10f).add(repeat("X .:3 x .:3 x .:3", 24));
        List<String> chime = repeat(".:12", 24);
        chime.set(0, "A5 .:11");
        chime.set(8, "E5 .:11");
        chime.set(16, "A5 .:11");
        s.voice(GothicSynth.Inst.BELL, 0.14f).reverb(0.8f).add(chime);
        return s;
    }

    // ------------------------------------------------------------------ 5. Moonlit Corridor

    /** E minor harpsichord ostinato with a slow pulse melody; organ and drums join on the repeat. */
    static GothicSynth.Song moonlit() {
        GothicSynth.Song s = new GothicSynth.Song("Moonlit Corridor", 112, 4, 16);
        s.echoSteps = 6;
        s.echoFeedback = 0.3f;
        s.reverbMix = 0.45f;
        String p = "0 1 2 3 2 1 2 1 0 1 2 3 2 1 2 1";
        String[] tones = {"E4 G4 B4 E5", "E4 G4 C5 E5", "E4 A4 C5 E5", "D#4 F#4 B4 D#5",
                "E4 G4 B4 E5", "E4 G4 C5 E5", "D#4 F#4 A4 B4", "D#4 F#4 B4 D#5"};
        List<String> ost = arps(p, tones);
        s.voice(GothicSynth.Inst.HARPSI, 0.24f).reverb(0.4f).add(concat(ost, ost));

        String[] roots = {"E2", "C2", "A1", "B1", "E2", "C2", "B1", "B1"};
        List<String> bass = new ArrayList<>();
        for (String r : roots) bass.add(r + ":6 " + r + ":2 " + transpose(r, 12) + ":8");
        s.voice(GothicSynth.Inst.TRI, 0.30f).add(concat(bass, bass));

        List<String> lead1 = list(
                "B4:8 E5:8",
                "G5:12 F#5:4",
                "E5:8 C5:4 A4:4",
                "D#5:12 .:4",
                "E5:4 F#5:4 G5:8",
                "A5:4 G5:4 E5:8",
                "F#5:4 A5:4 D#5:4 F#5:4",
                "B4:16");
        List<String> lead2 = list(
                "B5:8 E6:8",
                "G5:12 F#5:4",
                "E5:4 G5:4 A5:4 C6:4",
                "B5:12 .:4",
                "G5:4 F#5:4 E5:8",
                "C6:4 B5:4 A5:4 G5:4",
                "F#5:4 D#5:4 B4:4 A5:4",
                "G5:4 F#5:4 E5:8");
        s.voice(GothicSynth.Inst.PULSE50, 0.15f).echo(0.4f).reverb(0.45f).add(concat(lead1, lead2));

        List<String> pad = new ArrayList<>(repeat(".:16", 8));
        for (String t : new String[]{"E3+G3+B3", "C3+E3+G3", "A2+C3+E3", "B2+D#3+F#3",
                "E3+G3+B3", "C3+E3+G3", "B2+D#3+A3", "B2+D#3+F#3"}) pad.add(t + ":16");
        s.voice(GothicSynth.Inst.ORGAN, 0.14f).reverb(0.7f).add(pad);

        s.voice(GothicSynth.Inst.KICK, 0.45f).add(concat(repeat(".:16", 8), repeat("x .:7 .:3 x .:4", 8)));
        s.voice(GothicSynth.Inst.SNARE, 0.22f).add(concat(repeat(".:16", 8), repeat(".:8 x .:7", 8)));
        s.voice(GothicSynth.Inst.HAT, 0.07f).add(concat(repeat(".:16", 8), repeat("x . x . x . x . x . x . x . x .", 8)));
        return s;
    }
}
