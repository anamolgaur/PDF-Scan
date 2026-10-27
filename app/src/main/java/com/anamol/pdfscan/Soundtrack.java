package com.anamol.pdfscan;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's soundtrack: sixteen original tracks in a fixed order, a journey that starts with the
 * 8-bit puzzle loop, slows down and darkens through 8-bit gothic pieces into full gothic
 * arrangements (harpsichord, pipe organ, bells, timpani), then an 8-bit epilogue leads back to
 * the start. Every melody is written for this app; the gothic tracks take their mood from
 * classic castle-crawler game music without reusing any existing tune.
 *
 * The tracks are composed here as code and rendered once to the MP3 files the app ships with
 * (assets/music). render(i) gives the exact loop of track i.
 */
final class Soundtrack {
    static final String[] TITLES = {
            "Crimson Index", "Lantern Hall", "Moonlit Corridor", "Clocktower Waltz",
            "Cathedral of Ash", "Bloodmoon Gate", "Night Stair",
            "Moonlit Corridor (harpsichord)", "Clocktower Waltz (harpsichord)", "Crypt of Bells",
            "Requiem of Candles", "Cathedral of Ash (pipe organ)", "Masquerade of Thorns",
            "Night Stair (gothic)", "Raven Spire", "Ashen Dawn"};
    static final String[] MOODS = {
            "I \u00B7 8-bit puzzle loop", "II \u00B7 8-bit, slow, A minor", "III \u00B7 8-bit night ostinato",
            "IV \u00B7 8-bit waltz", "V \u00B7 8-bit chip organ", "VI \u00B7 8-bit gothic march",
            "VII \u00B7 8-bit castle chase", "VIII \u00B7 harpsichord ostinato", "IX \u00B7 harpsichord waltz",
            "X \u00B7 bells and heartbeat, E minor", "XI \u00B7 organ requiem, G minor",
            "XII \u00B7 pipe organ, bells, timpani", "XIII \u00B7 harpsichord masquerade, 3/4",
            "XIV \u00B7 gothic chase", "XV \u00B7 tower chase, A minor", "XVI \u00B7 8-bit epilogue, back to the start"};

    /** Tracks played with chip sounds only. */
    static boolean isChip(int track) {
        return track < 7 || track == 15;
    }

    private Soundtrack() {
    }

    static int count() {
        return TITLES.length;
    }

    static short[] render(int track) {
        GothicSynth.Song s = song(track);
        return s == null ? ChiptuneSynth.render() : GothicSynth.render(s);
    }

    /** The composition of a track; null for track 0, which has its own synthesiser. */
    static GothicSynth.Song song(int track) {
        switch (track) {
            case 1: return lanternHall();
            case 2: return moonlit();
            case 3: return clocktower();
            case 4: return cathedral();
            case 5: return bloodmoon();
            case 6: return nightStair();
            case 7: return GothicClassics.moonlit();
            case 8: return GothicClassics.clocktower();
            case 9: return GothicNocturnes.crypt();
            case 10: return GothicNocturnes.requiem();
            case 11: return GothicClassics.cathedral();
            case 12: return GothicNocturnes.masquerade();
            case 13: return GothicClassics.nightStair();
            case 14: return GothicNocturnes.ravenSpire();
            case 15: return ashenDawn();
            default: return null;
        }
    }

    /** A clean 8-bit song: no reverb, gentle tone, optional echo. */
    private static GothicSynth.Song chip(String title, int bpm, int stepsPerBar) {
        GothicSynth.Song s = new GothicSynth.Song(title, bpm, 4, stepsPerBar);
        s.reverbMix = 0f;
        s.echoFeedback = 0.3f;
        s.echoSteps = 3;
        s.toneCutoff = 7000f;
        return s;
    }

    // ------------------------------------------------------------------ helpers

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

    static String transpose(String note, int semis) {
        int m = ChiptuneSynth.midi(note) + semis;
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        return names[m % 12] + (m / 12 - 1);
    }

    /** Transposes every note of a bar, keeping lengths and rests. */
    private static List<String> shift(List<String> bars, int semis) {
        List<String> out = new ArrayList<>();
        for (String bar : bars) {
            StringBuilder sb = new StringBuilder();
            for (String tok : bar.trim().split("\\s+")) {
                String[] p = tok.split(":");
                String body = p[0];
                if (!body.equals(".") && !body.equalsIgnoreCase("x")) {
                    String[] notes = body.split("\\+");
                    StringBuilder nb = new StringBuilder();
                    for (int i = 0; i < notes.length; i++) {
                        if (i > 0) nb.append('+');
                        nb.append(transpose(notes[i], semis));
                    }
                    body = nb.toString();
                }
                sb.append(body);
                if (p.length > 1) sb.append(':').append(p[1]);
                sb.append(' ');
            }
            out.add(sb.toString().trim());
        }
        return out;
    }

    /** Broken chord over one bar: pattern of indexes into the chord tones, each `len` steps. */
    private static String arp(String tones, String pattern, int len) {
        String[] t = tones.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : pattern.split(" ")) {
            sb.append(t[Integer.parseInt(p) % t.length]);
            if (len > 1) sb.append(':').append(len);
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    private static List<String> arps(String pattern, int len, String... chords) {
        List<String> l = new ArrayList<>();
        for (String c : chords) l.add(arp(c, pattern, len));
        return l;
    }

    /** Whole-bar chip chords: "A3+C4+E4" for each bar, held for the bar. */
    private static List<String> held(int steps, String... chords) {
        List<String> l = new ArrayList<>();
        for (String c : chords) l.add(c + ":" + steps);
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

    /** Half-note bass: root then octave. */
    private static List<String> walk(String... roots) {
        List<String> l = new ArrayList<>();
        for (String r : roots) l.add(r + ":8 " + transpose(r, 12) + ":8");
        return l;
    }

    private static List<String> drums(String pattern, int bars) {
        return repeat(pattern, bars);
    }

    // ------------------------------------------------------------------ II. Lantern Hall

    /** A minor, unhurried: plucked arpeggios, a singing square lead, soft drums on the repeat. */
    static GothicSynth.Song lanternHall() {
        GothicSynth.Song s = chip("Lantern Hall", 100, 16);
        s.echoSteps = 3;
        List<String> leadA = list(
                "E5:6 D5:2 C5:4 B4:4",
                "A4:8 C5:4 F5:4",
                "E5:6 D5:2 C5:4 G4:4",
                "B4:8 D5:4 G5:4",
                "A5:6 G5:2 E5:4 C5:4",
                "F5:6 E5:2 C5:4 A4:4",
                "G#4:4 B4:4 E5:4 D5:4",
                "B4:16");
        List<String> leadB = list(
                "D5:4 F5:4 A5:8",
                "G5:4 E5:4 C5:8",
                "F5:4 A5:4 C6:6 B5:2",
                "G#5:8 E5:8",
                "F5:6 E5:2 D5:4 F5:4",
                "E5:6 C5:2 A4:8",
                "B4:4 D5:4 G#5:4 B5:4",
                "A5:16");
        s.voice(GothicSynth.Inst.PULSE50, 0.20f).echo(0.4f).add(concat(leadA, leadB));
        String p = "0 1 2 1 0 1 2 1";
        s.voice(GothicSynth.Inst.PLUCK, 0.17f).add(concat(
                arps(p, 2, "A3 C4 E4", "F3 A3 C4", "C4 E4 G4", "G3 B3 D4", "A3 C4 E4", "F3 A3 C4", "E3 G#3 B3", "E3 G#3 B3"),
                arps(p, 2, "D4 F4 A4", "A3 C4 E4", "F3 A3 C4", "E3 G#3 B3", "D4 F4 A4", "A3 C4 E4", "E3 G#3 B3", "A3 C4 E4")));
        s.voice(GothicSynth.Inst.TRI, 0.30f).add(concat(
                walk("A2", "F2", "C2", "G2", "A2", "F2", "E2", "E2"),
                walk("D2", "A2", "F2", "E2", "D2", "A2", "E2", "A2")));
        s.voice(GothicSynth.Inst.KICK, 0.35f).add(concat(repeat(".:16", 8), drums("x .:7 x .:7", 8)));
        s.voice(GothicSynth.Inst.HAT, 0.06f).add(concat(repeat(".:16", 8), drums(". . x . . . x . . . x . . . x .", 8)));
        return s;
    }

    // ------------------------------------------------------------------ III. Moonlit Corridor

    /** E minor: a plucked ostinato and a slow lead; chip chords and drums join on the repeat. */
    static GothicSynth.Song moonlit() {
        GothicSynth.Song s = chip("Moonlit Corridor", 112, 16);
        s.echoSteps = 6;
        String p = "0 1 2 3 2 1 2 1 0 1 2 3 2 1 2 1";
        List<String> ost = arps(p, 1, "E4 G4 B4 E5", "E4 G4 C5 E5", "E4 A4 C5 E5", "D#4 F#4 B4 D#5",
                "E4 G4 B4 E5", "E4 G4 C5 E5", "D#4 F#4 A4 B4", "D#4 F#4 B4 D#5");
        s.voice(GothicSynth.Inst.PLUCK, 0.19f).add(concat(ost, ost));
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
        s.voice(GothicSynth.Inst.PULSE50, 0.17f).echo(0.4f).add(concat(lead1, lead2));
        s.voice(GothicSynth.Inst.ARP12, 0.07f).add(concat(repeat(".:16", 8),
                held(16, "E4+G4+B4", "C4+E4+G4", "A3+C4+E4", "B3+D#4+F#4", "E4+G4+B4", "C4+E4+G4", "B3+D#4+A4", "B3+D#4+F#4")));
        s.voice(GothicSynth.Inst.KICK, 0.42f).add(concat(repeat(".:16", 8), drums("x .:7 .:3 x .:4", 8)));
        s.voice(GothicSynth.Inst.SNARE, 0.20f).add(concat(repeat(".:16", 8), drums(".:8 x .:7", 8)));
        s.voice(GothicSynth.Inst.HAT, 0.06f).add(concat(repeat(".:16", 8), drums("x . x . x . x . x . x . x . x .", 8)));
        return s;
    }

    // ------------------------------------------------------------------ IV. Clocktower Waltz

    /** A minor waltz: triangle oom, chip-chord pah-pah, an echoing lead and a ticking clock. */
    static GothicSynth.Song clocktower() {
        GothicSynth.Song s = chip("Clocktower Waltz", 138, 12);
        s.echoSteps = 4;
        s.echoFeedback = 0.25f;
        String am = "A3+C4+E4";
        String dm = "A3+D4+F4";
        String e = "G#3+B3+E4";
        String f = "A3+C4+F4";
        String[][] a = {{"A2", am}, {"A2", am}, {"D2", dm}, {"E2", e}, {"A2", am}, {"F2", f}, {"E2", e}, {"E2", e}};
        String[][] b = {{"D2", dm}, {"A2", am}, {"E2", e}, {"A2", am}, {"F2", f}, {"D2", dm}, {"E2", e}, {"A2", am}};
        List<String> bassA = new ArrayList<>();
        List<String> bassB = new ArrayList<>();
        List<String> chA = new ArrayList<>();
        List<String> chB = new ArrayList<>();
        for (String[] c : a) {
            bassA.add(c[0] + ":4 .:8");
            chA.add(".:4 " + c[1] + ":3 .:1 " + c[1] + ":3 .:1");
        }
        for (String[] c : b) {
            bassB.add(c[0] + ":4 .:8");
            chB.add(".:4 " + c[1] + ":3 .:1 " + c[1] + ":3 .:1");
        }
        s.voice(GothicSynth.Inst.TRI, 0.34f).add(concat(bassA, bassB, bassA));
        s.voice(GothicSynth.Inst.ARP12, 0.12f).add(concat(chA, chB, chA));
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
        s.voice(GothicSynth.Inst.PULSE25, 0.20f).echo(0.35f).add(concat(leadA, leadB, leadA));
        s.voice(GothicSynth.Inst.HAT, 0.10f).add(repeat("X .:3 x .:3 x .:3", 24));
        List<String> chime = repeat(".:12", 24);
        chime.set(0, "A5:2 E5:2 .:8");
        chime.set(8, "E5:2 B4:2 .:8");
        chime.set(16, "A5:2 E5:2 .:8");
        s.voice(GothicSynth.Inst.PLUCK, 0.16f).add(chime);
        return s;
    }

    // ------------------------------------------------------------------ V. Cathedral of Ash

    /** Slow D minor "chip organ": arpeggio chords over a triangle pedal; a plucked toccata joins. */
    static GothicSynth.Song cathedral() {
        GothicSynth.Song s = chip("Cathedral of Ash", 72, 16);
        s.echoSteps = 4;
        s.echoFeedback = 0.35f;
        List<String> chords = held(16, "D4+F4+A4", "D#4+G4+A#4", "D4+F4+A4", "C#4+E4+G4+A#4",
                "D4+G4+A#4", "D#4+G4+A#4", "C#4+E4+G4+A4", "D4+F4+A4");
        s.voice(GothicSynth.Inst.ARP25, 0.15f).echo(0.2f).add(concat(chords, chords));
        List<String> pedal = new ArrayList<>();
        for (String r : new String[]{"D2", "D#2", "D2", "A1", "G1", "D#2", "A1", "D2"}) pedal.add(r + ":16");
        s.voice(GothicSynth.Inst.TRI, 0.34f).add(concat(pedal, pedal));
        List<String> melody = list(
                "A4:8 D5:8",
                "A#4:12 G4:4",
                "A4:4 F4:4 D4:8",
                "E4:8 G4:4 A#4:4",
                "D5:8 A#4:4 G4:4",
                "D#5:8 D5:4 C5:4",
                "C#5:8 E5:4 G5:4",
                "F5:4 E5:4 D5:8");
        s.voice(GothicSynth.Inst.PULSE50, 0.20f).echo(0.45f).add(concat(shift(melody, 12), shift(melody, 12)));
        String t = "0 1 2 3 2 1 2 3 0 1 2 3 2 1 2 3";
        List<String> toccata = arps(t, 1, "D5 F5 A5 D6", "D#5 G5 A#5 D#6", "D5 F5 A5 D6", "C#5 E5 G5 A#5",
                "D5 G5 A#5 D6", "D#5 G5 A#5 D#6", "C#5 E5 G5 A5", "D5 F5 A5 D6");
        s.voice(GothicSynth.Inst.PLUCK, 0.12f).add(concat(repeat(".:16", 8), toccata));
        s.voice(GothicSynth.Inst.KICK, 0.45f).add(
                "X .:15", ".:16", ".:16", "x .:7 x .:3 x .:3", ".:16", ".:16", "x .:15", "X .:15",
                "X .:15", ".:16", ".:16", "x .:7 x .:3 x .:3", ".:16", ".:16", "x .:7 x .:3 x .:3", "X .:15");
        return s;
    }

    // ------------------------------------------------------------------ VI. Bloodmoon Gate

    /** C harmonic minor gothic march: galloping bass, echoing lead, chip arpeggios. */
    static GothicSynth.Song bloodmoon() {
        GothicSynth.Song s = chip("Bloodmoon Gate", 148, 16);
        s.echoSteps = 3;
        s.echoFeedback = 0.33f;
        List<String> leadA = list(
                "C5:2 G4:2 C5:2 D#5:2 G5:4 F5:2 D#5:2",
                "D5:2 D#5:2 F5:2 D5:2 C5:8",
                "D#5:2 C5:2 G#4:2 C5:2 D#5:3 F5:1 D#5:2 C5:2",
                "D5:2 A#4:2 F4:2 A#4:2 D5:4 F5:4",
                "G5:4 D#5:2 C5:2 G5:2 F5:2 D#5:2 D5:2",
                "C5:2 G#4:2 F4:2 G#4:2 C5:2 F5:2 D#5:2 C5:2",
                "D5:2 B4:2 G4:2 B4:2 D5:2 F5:2 D#5:2 D5:2",
                "B4:4 C5:2 D5:2 G4:8");
        List<String> leadB = list(
                "G#5:6 G5:2 F5:4 D#5:2 F5:2",
                "G5:6 F5:2 D#5:8",
                "F5:2 G#5:2 C6:4 A#5:2 G#5:2 G5:2 F5:2",
                "G5:4 D5:4 B4:4 D5:2 F5:2",
                "D#6:6 D6:2 C6:4 A#5:2 G#5:2",
                "A#5:6 G#5:2 G5:4 F5:2 D5:2",
                "D5:2 D#5:2 F5:2 G5:2 G#5:4 B5:4",
                "C6:12 .:4");
        s.voice(GothicSynth.Inst.PULSE25, 0.24f).echo(0.45f).add(concat(leadA, leadB));
        String a = "0 1 2 1 0 1 2 1 0 1 2 1 0 1 2 1";
        s.voice(GothicSynth.Inst.PULSE12, 0.07f).add(concat(
                arps(a, 1, "C4 D#4 G4", "C4 D#4 G4", "G#3 C4 D#4", "A#3 D4 F4", "C4 D#4 G4", "F3 G#3 C4", "G3 B3 D4", "G3 B3 D4"),
                arps(a, 1, "G#3 C4 D#4", "D#4 G4 A#4", "F3 G#3 C4", "G3 B3 D4", "G#3 C4 D#4", "A#3 D4 F4", "G3 B3 F4", "G3 B3 D4")));
        s.voice(GothicSynth.Inst.TRI, 0.34f).add(concat(
                gallop("C2", "C2", "G#1", "A#1", "C2", "F1", "G1", "G1"),
                gallop("G#1", "D#2", "F1", "G1", "G#1", "A#1", "G1", "G1")));
        s.voice(GothicSynth.Inst.KICK, 0.55f).add(repeat("X . . . . . . . x . . x . . . .", 16));
        s.voice(GothicSynth.Inst.SNARE, 0.30f).add(repeat(". . . . x . . . . . . . x . x .", 16));
        s.voice(GothicSynth.Inst.HAT, 0.09f).add(repeat("x . x . x . x . x . x . x . x .", 16));
        return s;
    }

    // ------------------------------------------------------------------ VII. Night Stair

    /** Fast D harmonic minor chase: echoing pulse lead over a galloping bass. The climax. */
    static GothicSynth.Song nightStair() {
        GothicSynth.Song s = chip("Night Stair", 160, 16);
        s.echoSteps = 3;
        s.echoFeedback = 0.32f;
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
        s.voice(GothicSynth.Inst.PULSE25, 0.30f).echo(0.45f).add(concat(leadA, leadB, leadA));
        String a = "0 1 2 1 0 1 2 1 0 1 2 1 0 1 2 1";
        List<String> harmA = arps(a, 1, "D4 F4 A4", "D4 F4 A4", "A#3 D4 F4", "C4 E4 G4",
                "D4 F4 A4", "G3 A#3 D4", "A3 C#4 E4", "A3 C#4 E4");
        List<String> harmB = arps(a, 1, "G3 A#3 D4", "D4 F4 A4", "A#3 D4 F4", "A3 C#4 E4",
                "G3 A#3 D4", "D4 F4 A4", "E4 G4 A#4", "A3 C#4 E4");
        s.voice(GothicSynth.Inst.PULSE12, 0.075f).add(concat(harmA, harmB, harmA));
        s.voice(GothicSynth.Inst.TRI, 0.34f).add(concat(
                gallop("D2", "D2", "A#1", "C2", "D2", "G1", "A1", "A1"),
                gallop("G1", "D2", "A#1", "A1", "G1", "D2", "A#1", "A1"),
                gallop("D2", "D2", "A#1", "C2", "D2", "G1", "A1", "A1")));
        s.voice(GothicSynth.Inst.KICK, 0.55f).add(repeat("X . . . . . . . x . . x . . . .", 24));
        s.voice(GothicSynth.Inst.SNARE, 0.30f).add(repeat(". . . . x . . . . . . . x . . x", 24));
        s.voice(GothicSynth.Inst.HAT, 0.10f).add(repeat("x . x . x . x . x . x . x . x .", 24));
        return s;
    }

    // ------------------------------------------------------------------ VIII. Ashen Dawn

    /** Epilogue: D minor brightening to F major, ending on E to lead back into the first track. */
    static GothicSynth.Song ashenDawn() {
        GothicSynth.Song s = chip("Ashen Dawn", 96, 16);
        s.echoSteps = 4;
        List<String> lead = list(
                "A4:8 D5:8",
                "F5:8 D5:4 F5:4",
                "A5:8 G5:4 F5:4",
                "E5:8 G5:8",
                "F5:6 E5:2 D5:4 A4:4",
                "A#4:8 D5:4 F5:4",
                "G5:6 F5:2 D5:4 A#4:4",
                "C#5:8 E5:8",
                "F5:4 A5:4 C6:8",
                "G5:4 E5:4 C5:8",
                "D5:4 F5:4 A5:6 G5:2",
                "F5:8 D5:8",
                "C5:4 F5:4 A5:8",
                "G5:6 F5:2 E5:4 C5:4",
                "G#4:4 B4:4 E5:4 G#5:4",
                "B5:16");
        s.voice(GothicSynth.Inst.PULSE50, 0.19f).echo(0.45f).add(lead);
        String p = "0 1 2 1 0 1 2 1";
        s.voice(GothicSynth.Inst.PLUCK, 0.15f).add(concat(
                arps(p, 2, "D4 F4 A4", "A#3 D4 F4", "F3 A3 C4", "C4 E4 G4", "D4 F4 A4", "A#3 D4 F4", "G3 A#3 D4", "A3 C#4 E4"),
                arps(p, 2, "F3 A3 C4", "C4 E4 G4", "D4 F4 A4", "A#3 D4 F4", "F3 A3 C4", "C4 E4 G4", "E3 G#3 B3", "E3 G#3 B3")));
        s.voice(GothicSynth.Inst.TRI, 0.30f).add(walk("D2", "A#1", "F2", "C2", "D2", "A#1", "G1", "A1",
                "F2", "C2", "D2", "A#1", "F2", "C2", "E2", "E2"));
        s.voice(GothicSynth.Inst.ARP12, 0.06f).add(concat(repeat(".:16", 8),
                held(16, "F4+A4+C5", "C4+E4+G4", "D4+F4+A4", "A#3+D4+F4", "F4+A4+C5", "C4+E4+G4", "E4+G#4+B4", "E4+G#4+B4")));
        s.voice(GothicSynth.Inst.HAT, 0.05f).add(concat(repeat(".:16", 8), drums(". . x . . . x . . . x . . . x .", 8)));
        return s;
    }
}
