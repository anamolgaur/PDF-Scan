package com.anamol.pdfscan;

/**
 * Synthesises the app's background music: an original, fast, minor-key 8-bit loop
 * (pulse-wave lead, triangle bass, noise drums), in the spirit of classic handheld puzzle games.
 * The melody is written for this app. Pure Java, so it is unit-tested on a desktop JVM.
 */
final class ChiptuneSynth {
    static final int RATE = 22050;
    static final int BPM = 150;

    private ChiptuneSynth() {
    }

    // Eighth-note grid, 8 per bar. Note names with octave; "-" holds the previous note; "." is a rest.
    private static final String[] PART_A = {
            "A4 - E5 - D5 C5 B4 C5",
            "D5 - F5 - E5 D5 C5 B4",
            "C5 - A4 - C5 E5 D5 C5",
            "B4 G#4 B4 D5 E5 - - .",
            "A4 - E5 - D5 C5 B4 C5",
            "D5 - F5 - A5 G5 F5 E5",
            "F5 E5 D5 C5 B4 C5 D5 B4",
            "A4 - - - E4 - A4 ."};
    private static final String[] PART_B = {
            "F5 - - E5 D5 - C5 D5",
            "E5 - - D5 C5 - B4 C5",
            "D5 - - C5 B4 - A4 B4",
            "C5 - B4 - G#4 - E4 .",
            "F5 - - E5 D5 - C5 D5",
            "E5 - A5 - G5 F5 E5 D5",
            "C5 E5 D5 B4 C5 A4 B4 G#4",
            "A4 - - - - - . ."};
    // One chord root per bar (bass), as MIDI note numbers.
    private static final int[] BASS_A = {45, 50, 45, 40, 45, 50, 50, 45};
    private static final int[] BASS_B = {41, 48, 50, 40, 41, 48, 50, 45};
    // Chord tones for the quiet arpeggio voice (semitones above the bass root).
    private static final int[][] CHORD_A = {{0, 3, 7}, {0, 3, 7}, {0, 3, 7}, {0, 4, 7}, {0, 3, 7}, {0, 3, 7}, {0, 3, 7}, {0, 3, 7}};
    private static final int[][] CHORD_B = {{0, 4, 7}, {0, 4, 7}, {0, 3, 7}, {0, 4, 7}, {0, 4, 7}, {0, 4, 7}, {0, 3, 7}, {0, 3, 7}};

    static int midi(String note) {
        int base;
        switch (note.charAt(0)) {
            case 'C': base = 0; break;
            case 'D': base = 2; break;
            case 'E': base = 4; break;
            case 'F': base = 5; break;
            case 'G': base = 7; break;
            case 'A': base = 9; break;
            default: base = 11; break;
        }
        int i = 1;
        if (note.charAt(i) == '#') {
            base++;
            i++;
        } else if (note.charAt(i) == 'b') {
            base--;
            i++;
        }
        int octave = Integer.parseInt(note.substring(i));
        return 12 * (octave + 1) + base;
    }

    static double freq(int midi) {
        return 440.0 * Math.pow(2, (midi - 69) / 12.0);
    }

    /** Renders the whole loop (sections A, B, A) as 16-bit mono PCM at RATE. */
    static short[] render() {
        String[][] sections = {PART_A, PART_B, PART_A};
        int[][] basses = {BASS_A, BASS_B, BASS_A};
        int[][][] chords = {CHORD_A, CHORD_B, CHORD_A};
        int bars = 0;
        for (String[] s : sections) bars += s.length;
        double eighth = 60.0 / BPM / 2.0;
        int eighthSamples = (int) Math.round(eighth * RATE);
        int total = bars * 8 * eighthSamples;
        float[] mix = new float[total];

        int bar = 0;
        for (int s = 0; s < sections.length; s++) {
            boolean lastA = s == 2;
            for (int b = 0; b < sections[s].length; b++, bar++) {
                int barStart = bar * 8 * eighthSamples;
                // lead melody
                String[] tokens = sections[s][b].trim().split("\\s+");
                for (int k = 0; k < tokens.length && k < 8; k++) {
                    String t = tokens[k];
                    if (t.equals("-") || t.equals(".")) continue;
                    int len = 1;
                    while (k + len < tokens.length && tokens[k + len].equals("-")) len++;
                    int note = midi(t) + (lastA && b < 6 ? 12 : 0);  // last section an octave up for lift
                    pulse(mix, barStart + k * eighthSamples, len * eighthSamples, freq(note), 0.25, 0.20f);
                }
                // bass: root and octave on alternating eighths
                for (int k = 0; k < 8; k++) {
                    int n = basses[s][b] + (k % 2 == 1 ? 12 : 0);
                    triangle(mix, barStart + k * eighthSamples, (int) (eighthSamples * 0.85), freq(n), 0.26f);
                }
                // quiet sixteenth-note arpeggio, an octave above the bass
                int[] chord = chords[s][b];
                int sixteenth = eighthSamples / 2;
                for (int k = 0; k < 16; k++) {
                    int n = basses[s][b] + 24 + chord[k % 3];
                    pulse(mix, barStart + k * sixteenth, (int) (sixteenth * 0.7), freq(n), 0.125, 0.045f);
                }
                // drums
                for (int k = 0; k < 8; k++) {
                    int at = barStart + k * eighthSamples;
                    if (k == 0 || k == 4 || (k == 7 && b % 4 == 3)) kick(mix, at);
                    if (k == 2 || k == 6) snare(mix, at, bar * 8 + k);
                    hat(mix, at, bar * 8 + k);
                }
            }
        }
        // soft limiter and conversion
        float peak = 0f;
        for (float v : mix) peak = Math.max(peak, Math.abs(v));
        float gain = peak > 0 ? 0.9f / Math.max(peak, 0.9f) : 1f;
        short[] out = new short[total];
        for (int i = 0; i < total; i++) {
            double v = Math.tanh(mix[i] * gain * 1.1);
            out[i] = (short) Math.round(v * 30000);
        }
        return out;
    }

    /** Pulse wave with a short attack, gentle decay, late vibrato and a release tail. */
    private static void pulse(float[] mix, int start, int len, double f, double duty, float amp) {
        int release = (int) (0.025 * RATE);
        int attack = (int) (0.003 * RATE);
        double phase = 0;
        for (int i = 0; i < len + release && start + i < mix.length; i++) {
            double t = i / (double) RATE;
            double vib = t > 0.15 ? 1 + 0.004 * Math.sin(2 * Math.PI * 5.5 * t) : 1;
            phase += f * vib / RATE;
            phase -= Math.floor(phase);
            double env = i < attack ? i / (double) attack : 0.75 + 0.25 * Math.exp(-t * 12);
            if (i >= len) env *= 1 - (i - len) / (double) release;
            mix[start + i] += (float) ((phase < duty ? 1 : -1) * env * amp);
        }
    }

    private static void triangle(float[] mix, int start, int len, double f, float amp) {
        int release = (int) (0.01 * RATE);
        for (int i = 0; i < len + release && start + i < mix.length; i++) {
            double ph = (i * f / RATE) % 1.0;
            double v = ph < 0.5 ? 4 * ph - 1 : 3 - 4 * ph;
            double env = i >= len ? 1 - (i - len) / (double) release : 1;
            mix[start + i] += (float) (v * env * amp);
        }
    }

    private static void kick(float[] mix, int start) {
        int len = (int) (0.14 * RATE);
        double phase = 0;
        for (int i = 0; i < len && start + i < mix.length; i++) {
            double t = i / (double) RATE;
            double f = 50 + 110 * Math.exp(-t * 35);
            phase += f / RATE;
            mix[start + i] += (float) (Math.sin(2 * Math.PI * phase) * Math.exp(-t * 22) * 0.55);
        }
    }

    /** Deterministic noise (16-bit LFSR, like old sound chips). */
    private static int lfsr(int state) {
        int bit = (state ^ (state >> 1)) & 1;
        return (state >> 1) | (bit << 14);
    }

    private static void snare(float[] mix, int start, int seed) {
        int len = (int) (0.13 * RATE);
        int s = 0x5A5A ^ (seed * 7919 & 0x7FFF);
        if (s == 0) s = 1;
        for (int i = 0; i < len && start + i < mix.length; i++) {
            double t = i / (double) RATE;
            s = lfsr(s);
            double noise = (s & 1) == 1 ? 1 : -1;
            double tone = Math.sin(2 * Math.PI * 185 * t) * Math.exp(-t * 40);
            mix[start + i] += (float) ((noise * 0.20 + tone * 0.18) * Math.exp(-t * 26));
        }
    }

    private static void hat(float[] mix, int start, int seed) {
        int len = (int) (0.035 * RATE);
        int s = 0x1234 ^ (seed * 104729 & 0x7FFF);
        if (s == 0) s = 1;
        double prev = 0;
        for (int i = 0; i < len && start + i < mix.length; i++) {
            double t = i / (double) RATE;
            s = lfsr(s);
            double n = (s & 1) == 1 ? 1 : -1;
            double hp = n - prev;  // crude high-pass
            prev = n;
            mix[start + i] += (float) (hp * 0.035 * Math.exp(-t * 90));
        }
    }
}
