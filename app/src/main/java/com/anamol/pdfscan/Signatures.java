package com.anamol.pdfscan;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Saved signatures (vector strokes), kept privately on the device. */
final class Signatures {
    /** Strokes with x in 0..1 and y in 0..aspect (aspect = height / width). */
    static final class Sig {
        float aspect;
        List<float[]> strokes = new ArrayList<>();
    }

    private Signatures() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "signatures.json");
    }

    static List<Sig> load(Context c) {
        List<Sig> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Sig s = new Sig();
                s.aspect = (float) o.getDouble("a");
                JSONArray st = o.getJSONArray("s");
                for (int j = 0; j < st.length(); j++) {
                    JSONArray p = st.getJSONArray(j);
                    float[] pts = new float[p.length()];
                    for (int k = 0; k < pts.length; k++) pts[k] = (float) p.getDouble(k);
                    s.strokes.add(pts);
                }
                out.add(s);
            }
        } catch (IOException | JSONException ignored) {
            // unreadable file: behave as if there are no signatures
        }
        return out;
    }

    static void save(Context c, List<Sig> sigs) throws IOException {
        try {
            JSONArray a = new JSONArray();
            for (Sig s : sigs) {
                JSONArray st = new JSONArray();
                for (float[] p : s.strokes) {
                    JSONArray pa = new JSONArray();
                    for (float v : p) pa.put(Math.round(v * 10000f) / 10000.0);
                    st.put(pa);
                }
                a.put(new JSONObject().put("a", s.aspect).put("s", st));
            }
            Files.write(file(c).toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * Normalises raw pad strokes (pixel coordinates) into a signature: crops to the ink with a
     * small margin, scales so the width is 1. Returns null if nothing was drawn.
     */
    static Sig fromStrokes(List<float[]> raw) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] s : raw) {
            for (int i = 0; i + 1 < s.length; i += 2) {
                minX = Math.min(minX, s[i]);
                maxX = Math.max(maxX, s[i]);
                minY = Math.min(minY, s[i + 1]);
                maxY = Math.max(maxY, s[i + 1]);
            }
        }
        if (minX == Float.MAX_VALUE) return null;
        float margin = Math.max(maxX - minX, maxY - minY) * 0.04f + 2f;
        minX -= margin;
        minY -= margin;
        maxX += margin;
        maxY += margin;
        float width = maxX - minX;
        Sig sig = new Sig();
        sig.aspect = (maxY - minY) / width;
        for (float[] s : raw) {
            float[] n = new float[s.length];
            for (int i = 0; i + 1 < s.length; i += 2) {
                n[i] = (s[i] - minX) / width;
                n[i + 1] = (s[i + 1] - minY) / width;
            }
            sig.strokes.add(n);
        }
        return sig;
    }
}
