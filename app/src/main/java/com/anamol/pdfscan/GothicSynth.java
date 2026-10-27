package com.anamol.pdfscan;

import java.util.ArrayList;
import java.util.List;

/**
 * A small step sequencer and synthesiser for the soundtrack (pure Java; unit-tested).
 * Instruments: pulse waves, triangle bass, pipe organ (additive drawbars), harpsichord
 * (plucked string), tubular bell (FM), timpani and chip drums, with echo and reverb.
 * Loops are seamless: notes and effect tails that run past the end wrap round to the start.
 */
final class GothicSynth {
    static final int RATE = ChiptuneSynth.RATE;

    enum Inst { PULSE50, PULSE25, PULSE12, TRI, ORGAN, HARPSI, BELL, TIMPANI, KICK, SNARE, HAT, ARP25, ARP12, PLUCK }

    /** One instrument part: one bar string per bar. */
    static final class Voice {
        final Inst inst;
        final float amp;
        float reverb;
        float echo;
        final List<String> bars = new ArrayList<>();

        Voice(Inst inst, float amp) {
            this.inst = inst;
            this.amp = amp;
        }

        Voice reverb(float r) {
            reverb = r;
            return this;
        }

        Voice echo(float e) {
            echo = e;
            return this;
        }

        Voice add(String... b) {
            for (String s : b) bars.add(s);
            return this;
        }

        Voice add(List<String> b) {
            bars.addAll(b);
            return this;
        }
    }

    static final class Song {
        final String title;
        final int bpm;
        final int stepsPerBeat;
        final int stepsPerBar;
        final List<Voice> voices = new ArrayList<>();
        float reverbMix = 0.35f;
        float reverbSize = 0.84f;
        float echoSteps = 3;
        float echoFeedback = 0.35f;
        float loudness = 0.19f;
        float toneCutoff = 6500f;  // gentle low-pass to soften bright chip waves

        Song(String title, int bpm, int stepsPerBeat, int stepsPerBar) {
            this.title = title;
            this.bpm = bpm;
            this.stepsPerBeat = stepsPerBeat;
            this.stepsPerBar = stepsPerBar;
        }

        Voice voice(Inst inst, float amp) {
            Voice v = new Voice(inst, amp);
            voices.add(v);
            return v;
        }

        int bars() {
            int n = 0;
            for (Voice v : voices) n = Math.max(n, v.bars.size());
            return n;
        }

        double stepSeconds() {
            return 60.0 / bpm / stepsPerBeat;
        }
    }

    private GothicSynth() {
    }

    // ------------------------------------------------------------------ notation

    /** A parsed event: start step within the bar, length in steps, MIDI notes (empty = rest, -1 = hit). */
    static final class Event {
        final int step;
        final int len;
        final int[] notes;

        Event(int step, int len, int[] notes) {
            this.step = step;
            this.len = len;
            this.notes = notes;
        }
    }

    /**
     * Bar notation: tokens separated by spaces. "D5" is a note, "D3+F3+A3" a chord, "." a rest,
     * "x" a drum hit, "X" an accented hit; ":n" sets the length in steps (default 1).
     */
    static List<Event> parseBar(String bar) {
        List<Event> out = new ArrayList<>();
        int step = 0;
        for (String tok : bar.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            int len = 1;
            int colon = tok.indexOf(':');
            String body = tok;
            if (colon >= 0) {
                len = Integer.parseInt(tok.substring(colon + 1));
                body = tok.substring(0, colon);
            }
            int[] notes;
            if (body.equals(".")) {
                notes = new int[0];
            } else if (body.equals("x")) {
                notes = new int[]{-1};
            } else if (body.equals("X")) {
                notes = new int[]{-2};
            } else {
                String[] parts = body.split("\\+");
                notes = new int[parts.length];
                for (int i = 0; i < parts.length; i++) notes[i] = ChiptuneSynth.midi(parts[i]);
            }
            out.add(new Event(step, len, notes));
            step += len;
        }
        return out;
    }

    static int barLength(String bar) {
        int n = 0;
        for (Event e : parseBar(bar)) n += e.len;
        return n;
    }

    // ------------------------------------------------------------------ rendering

    static short[] render(Song song) {
        double stepSamples = song.stepSeconds() * RATE;
        int bars = song.bars();
        int total = (int) Math.round(bars * song.stepsPerBar * stepSamples);
        float[] dry = new float[total];
        float[] revSend = new float[total];
        float[] echoSend = new float[total];
        float[] vbuf = new float[total];

        for (Voice v : song.voices) {
            java.util.Arrays.fill(vbuf, 0f);
            for (int b = 0; b < v.bars.size(); b++) {
                for (Event e : parseBar(v.bars.get(b))) {
                    if (e.notes.length == 0) continue;
                    int start = (int) Math.round((b * song.stepsPerBar + e.step) * stepSamples);
                    int len = (int) Math.round(e.len * stepSamples);
                    if (v.inst == Inst.ARP25 || v.inst == Inst.ARP12) {
                        // chip chords: one channel cycling through the notes very fast
                        chipArp(vbuf, start, len, e.notes, v.inst == Inst.ARP25 ? 0.25 : 0.125, v.amp);
                        continue;
                    }
                    for (int n : e.notes) {
                        float amp = v.amp * (e.notes.length > 1 ? 1f / (float) Math.sqrt(e.notes.length) : 1f);
                        if (n == -2) amp *= 1.35f;
                        play(v.inst, vbuf, start, len, n, amp, b * 131 + e.step);
                    }
                }
            }
            for (int i = 0; i < total; i++) {
                float s = vbuf[i];
                dry[i] += s;
                revSend[i] += s * v.reverb;
                echoSend[i] += s * v.echo;
            }
        }

        // echo: feedback delay, computed over two passes so the loop wraps cleanly
        if (song.echoFeedback > 0) {
            int d = Math.max(1, (int) Math.round(song.echoSteps * stepSamples));
            float[] e = new float[total];
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < total; i++) {
                    int j = i - d;
                    if (j < 0) j += total;
                    e[i] = echoSend[i] + song.echoFeedback * e[j];
                }
            }
            for (int i = 0; i < total; i++) {
                int j = i - d;
                if (j < 0) j += total;
                float wet = e[j] * 0.55f;
                dry[i] += wet;
                revSend[i] += wet * 0.3f;
            }
        }

        // reverb: Schroeder (four combs, two all-passes), run twice round the loop
        float[] wet = reverb(revSend, song.reverbSize);
        for (int i = 0; i < total; i++) dry[i] += wet[i] * song.reverbMix;

        // gentle one-pole low-pass, run twice round the loop so the seam stays continuous
        double a = 1 - Math.exp(-2 * Math.PI * song.toneCutoff / RATE);
        float y = 0f;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < total; i++) {
                y += (float) (a * (dry[i] - y));
                if (pass == 1) dry[i] = y;
            }
        }

        // even loudness across tracks, then a soft limiter
        double sum = 0;
        for (float s : dry) sum += s * s;
        double rms = Math.sqrt(sum / Math.max(1, total));
        float gain = rms > 1e-6 ? (float) (song.loudness / rms) : 1f;
        short[] out = new short[total];
        for (int i = 0; i < total; i++) {
            double v = Math.tanh(dry[i] * gain);
            out[i] = (short) Math.round(v * 30000);
        }
        return out;
    }

    private static float[] reverb(float[] in, float size) {
        int n = in.length;
        int[] combs = {(int) (0.0297 * RATE), (int) (0.0371 * RATE), (int) (0.0411 * RATE), (int) (0.0437 * RATE)};
        float[] out = new float[n];
        for (int d : combs) {
            float[] buf = new float[d];
            float lp = 0f;
            int p = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < n; i++) {
                    float y = buf[p];
                    lp = y * 0.7f + lp * 0.3f;  // damping: darker tail
                    buf[p] = in[i] + lp * size;
                    p = (p + 1) % d;
                    if (pass == 1) out[i] += y * 0.25f;
                }
            }
        }
        for (int d : new int[]{(int) (0.005 * RATE), (int) (0.0017 * RATE)}) {
            float[] buf = new float[d];
            int p = 0;
            float[] res = new float[n];
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < n; i++) {
                    float b = buf[p];
                    float x = out[i];
                    float y = -x + b;
                    buf[p] = x + b * 0.5f;
                    p = (p + 1) % d;
                    if (pass == 1) res[i] = y;
                }
            }
            out = res;
        }
        return out;
    }

    // ------------------------------------------------------------------ instruments

    private static void add(float[] buf, int i, double v) {
        int n = buf.length;
        int k = i % n;
        if (k < 0) k += n;
        buf[k] += (float) v;
    }

    private static int lfsr(int s) {
        int bit = (s ^ (s >> 1)) & 1;
        return (s >> 1) | (bit << 14);
    }

    private static void play(Inst inst, float[] buf, int start, int len, int note, float amp, int seed) {
        double f = note >= 0 ? ChiptuneSynth.freq(note) : 0;
        switch (inst) {
            case PULSE50: pulse(buf, start, len, f, 0.5, amp); break;
            case PULSE25: pulse(buf, start, len, f, 0.25, amp); break;
            case PULSE12: pulse(buf, start, len, f, 0.125, amp); break;
            case TRI: triangle(buf, start, len, f, amp); break;
            case ORGAN: organ(buf, start, len, f, amp); break;
            case HARPSI: harpsichord(buf, start, len, f, amp, seed + note * 31); break;
            case PLUCK: pluck(buf, start, len, f, amp); break;
            case BELL: bell(buf, start, f, amp); break;
            case TIMPANI: timpani(buf, start, f, amp, seed); break;
            case KICK: kick(buf, start, amp); break;
            case SNARE: snare(buf, start, amp, seed); break;
            default: hat(buf, start, amp, seed); break;
        }
    }

    /**
     * Chip-style chord: the classic 8-bit trick of one pulse channel stepping through the chord
     * notes every 1/40 s, which the ear hears as a shimmering chord.
     */
    static void chipArp(float[] buf, int start, int len, int[] notes, double duty, float amp) {
        int step = Math.max(1, RATE / 40);
        int attack = (int) (0.006 * RATE);
        int release = (int) (0.05 * RATE);
        double phase = 0;
        for (int i = 0; i < len + release; i++) {
            int n = notes[(i / step) % notes.length];
            phase += ChiptuneSynth.freq(n) / RATE;
            phase -= Math.floor(phase);
            double env = i < attack ? i / (double) attack : 0.85 + 0.15 * Math.exp(-i / (0.3 * RATE));
            if (i >= len) env *= 1 - (i - len) / (double) release;
            add(buf, start + i, (phase < duty ? 1 : -1) * env * amp);
        }
    }

    /** Plucked pulse: 12.5% duty with a fast decay, the 8-bit stand-in for a harpsichord. */
    static void pluck(float[] buf, int start, int len, double f, float amp) {
        int ring = Math.min(len + (int) (0.04 * RATE), (int) (0.6 * RATE));
        double phase = 0;
        for (int i = 0; i < ring; i++) {
            double t = i / (double) RATE;
            phase += f / RATE;
            phase -= Math.floor(phase);
            double env = Math.min(1, i / (0.002 * RATE)) * Math.exp(-t * 7.5);
            if (i >= len) env *= Math.max(0, 1 - (i - len) / (0.04 * RATE));
            double duty = 0.125 + 0.1 * Math.exp(-t * 12);  // brighter at the attack
            add(buf, start + i, (phase < duty ? 1 : -1) * env * amp);
        }
    }

    static void pulse(float[] buf, int start, int len, double f, double duty, float amp) {
        int release = (int) (0.03 * RATE);
        int attack = (int) (0.003 * RATE);
        double phase = 0;
        for (int i = 0; i < len + release; i++) {
            double t = i / (double) RATE;
            double vib = t > 0.18 ? 1 + 0.005 * Math.sin(2 * Math.PI * 5.5 * t) : 1;
            phase += f * vib / RATE;
            phase -= Math.floor(phase);
            double env = i < attack ? i / (double) attack : 0.72 + 0.28 * Math.exp(-t * 10);
            if (i >= len) env *= 1 - (i - len) / (double) release;
            add(buf, start + i, (phase < duty ? 1 : -1) * env * amp);
        }
    }

    static void triangle(float[] buf, int start, int len, double f, float amp) {
        int release = (int) (0.012 * RATE);
        double phase = 0;
        for (int i = 0; i < len + release; i++) {
            phase += f / RATE;
            phase -= Math.floor(phase);
            double v = phase < 0.5 ? 4 * phase - 1 : 3 - 4 * phase;
            double env = i >= len ? 1 - (i - len) / (double) release : 1;
            add(buf, start + i, v * env * amp);
        }
    }

    /** One period of the organ tone at half the played pitch: 16', 8', 4', 2 2/3', 2' and 1' ranks. */
    private static final float[] ORGAN_TABLE = new float[4096];

    static {
        double[] harmonic = {1, 2, 4, 6, 8, 16};   // relative to half the pitch
        double[] weight = {0.35, 1, 0.55, 0.3, 0.25, 0.08};
        double norm = 0;
        for (double w : weight) norm += w;
        for (int i = 0; i < ORGAN_TABLE.length; i++) {
            double x = 2 * Math.PI * i / ORGAN_TABLE.length;
            double v = 0;
            for (int k = 0; k < harmonic.length; k++) v += weight[k] * Math.sin(harmonic[k] * x);
            ORGAN_TABLE[i] = (float) (v / norm);
        }
    }

    /** Pipe organ: wavetable of the ranks, a detuned second rank for width, slow attack. */
    static void organ(float[] buf, int start, int len, double f, float amp) {
        int attack = (int) (0.025 * RATE);
        int release = (int) (0.16 * RATE);
        int size = ORGAN_TABLE.length;
        double base = f * 0.5;
        double p1 = 0;
        double p2 = 0.37;
        for (int i = 0; i < len + release; i++) {
            double t = i / (double) RATE;
            double vib = 1 + 0.0018 * Math.sin(2 * Math.PI * 5.8 * t);
            p1 += base * vib / RATE;
            p2 += base * 1.0021 / RATE;
            p1 -= Math.floor(p1);
            p2 -= Math.floor(p2);
            double s = ORGAN_TABLE[(int) (p1 * size) & (size - 1)] + 0.5 * ORGAN_TABLE[(int) (p2 * size) & (size - 1)];
            double env = i < attack ? i / (double) attack : 1;
            if (i >= len) env *= Math.exp(-(i - len) / (0.05 * RATE));
            add(buf, start + i, s / 1.5 * env * amp);
        }
    }

    /** Harpsichord: Karplus–Strong plucked string with a damper when the key is released. */
    static void harpsichord(float[] buf, int start, int len, double f, float amp, int seed) {
        int period = Math.max(2, (int) Math.round(RATE / f - 0.5));
        float[] str = new float[period];
        int s = 0x2F31 ^ (seed * 7919 & 0x7FFF);
        if (s == 0) s = 1;
        for (int i = 0; i < period; i++) {
            s = lfsr(s);
            str[i] = ((s & 1) == 1 ? 1f : -1f);
        }
        // pluck brightness: a little smoothing of the initial noise
        for (int i = 1; i < period; i++) str[i] = 0.75f * str[i] + 0.25f * str[i - 1];
        int ring = Math.min(len, (int) (2.5 * RATE));
        int damp = (int) (0.05 * RATE);
        double decay = f > 600 ? 0.993 : 0.997;
        int p = 0;
        for (int i = 0; i < ring + damp; i++) {
            float y = str[p];
            int q = (p + 1) % period;
            str[p] = (float) (decay * 0.5 * (str[p] + str[q]));
            p = q;
            double env = i >= ring ? 1 - (i - ring) / (double) damp : 1;
            add(buf, start + i, y * env * amp);
        }
    }

    /** Tubular bell: inharmonic FM with a long ring. */
    static void bell(float[] buf, int start, double f, float amp) {
        int n = (int) (3.5 * RATE);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double index = 3.5 * Math.exp(-t * 2.5);
            double m = Math.sin(2 * Math.PI * f * 1.4 * t);
            double v = Math.sin(2 * Math.PI * f * t + index * m) * Math.exp(-t * 1.1)
                    + 0.3 * Math.sin(2 * Math.PI * f * 2.76 * t) * Math.exp(-t * 3);
            double atk = Math.min(1, i / (0.002 * RATE));
            add(buf, start + i, v * atk * amp);
        }
    }

    static void timpani(float[] buf, int start, double f, float amp, int seed) {
        int n = (int) (1.6 * RATE);
        double phase = 0;
        int s = 0x1D55 ^ (seed & 0x7FFF);
        if (s == 0) s = 1;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double fr = f * (1 + 0.04 * Math.exp(-t * 20));
            phase += fr / RATE;
            s = lfsr(s);
            double noise = ((s & 1) == 1 ? 1 : -1) * Math.exp(-t * 60) * 0.25;
            double v = (Math.sin(2 * Math.PI * phase) + 0.35 * Math.sin(2 * Math.PI * phase * 1.5)) * Math.exp(-t * 3.2);
            add(buf, start + i, (v + noise) * amp);
        }
    }

    static void kick(float[] buf, int start, float amp) {
        int n = (int) (0.16 * RATE);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            phase += (48 + 115 * Math.exp(-t * 32)) / RATE;
            add(buf, start + i, Math.sin(2 * Math.PI * phase) * Math.exp(-t * 20) * amp);
        }
    }

    static void snare(float[] buf, int start, float amp, int seed) {
        int n = (int) (0.14 * RATE);
        int s = 0x5A5A ^ (seed * 7919 & 0x7FFF);
        if (s == 0) s = 1;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            s = lfsr(s);
            double noise = (s & 1) == 1 ? 1 : -1;
            double tone = Math.sin(2 * Math.PI * 190 * t) * Math.exp(-t * 40);
            add(buf, start + i, (noise * 0.55 + tone * 0.5) * Math.exp(-t * 24) * amp);
        }
    }

    static void hat(float[] buf, int start, float amp, int seed) {
        int n = (int) (0.04 * RATE);
        int s = 0x1234 ^ (seed * 104729 & 0x7FFF);
        if (s == 0) s = 1;
        double prev = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            s = lfsr(s);
            double x = (s & 1) == 1 ? 1 : -1;
            add(buf, start + i, (x - prev) * 0.5 * Math.exp(-t * 85) * amp);
            prev = x;
        }
    }
}
