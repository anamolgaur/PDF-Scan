package com.anamol.pdfscan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-page settings stored next to the page as p….json: geometry (rotation, crop corners),
 * look (filter, glare, sliders), cleanup strokes, mark-up, and recognised text with word boxes.
 * All positions are normalised to the finished page (0..1); sizes are fractions of page width.
 */
final class PageMeta {
    /** An eraser stroke. */
    static final class Stroke {
        float width;
        float[] pts;

        Stroke(float width, float[] pts) {
            this.width = width;
            this.pts = pts;
        }
    }

    /** A mark-up element: pen, highlight, rect, oval, arrow, or a placed signature. */
    static final class Mark {
        static final String PEN = "pen";
        static final String HIGHLIGHT = "highlight";
        static final String RECT = "rect";
        static final String OVAL = "oval";
        static final String ARROW = "arrow";
        static final String SIGNATURE = "sig";

        String type;
        int color;
        float width;
        float[] pts;                       // polyline, or two corner points; for a signature: x, y, w
        List<float[]> sig = new ArrayList<>();  // signature strokes, x in 0..1 and y in 0..sigAspect
        float sigAspect = 0.4f;

        Mark copy() {
            Mark m = new Mark();
            m.type = type;
            m.color = color;
            m.width = width;
            m.pts = pts.clone();
            for (float[] s : sig) m.sig.add(s.clone());
            m.sigAspect = sigAspect;
            return m;
        }
    }

    int rotation;          // 0, 90, 180, 270 (clockwise), applied to the original first
    float[] corners;       // crop quad in rotated-original space, normalised; null = whole image
    String filter = ImageOps.ORIGINAL;
    boolean glare;
    float brightness;
    float contrast;
    float sharpen;
    float curve;           // book page curve correction, -1..1; 0 = none
    boolean spineLeft;     // the book's binding is on the left of this page
    List<Stroke> erase = new ArrayList<>();
    List<Mark> marks = new ArrayList<>();

    String srcPdf;         // the PDF this page came from, kept in the document (see PdfOverlay)
    int srcPage = -1;      // its page number there, from 0

    String text;           // recognised text; null = not recognised yet
    boolean fromPdfText;   // text came from an imported PDF's own text layer (no word boxes)
    int ocrW, ocrH;        // size of the page image the word boxes refer to
    String[] words = new String[0];
    int[] boxes = new int[0];  // x, y, w, h per word

    ImageOps.Adjust adjust() {
        ImageOps.Adjust a = new ImageOps.Adjust();
        a.filter = filter;
        a.glare = glare;
        a.brightness = brightness;
        a.contrast = contrast;
        a.sharpen = sharpen;
        return a;
    }

    void setAdjust(ImageOps.Adjust a) {
        filter = a.filter;
        glare = a.glare;
        brightness = a.brightness;
        contrast = a.contrast;
        sharpen = a.sharpen;
    }

    boolean hasText() {
        return text != null;
    }

    void clearText() {
        text = null;
        fromPdfText = false;
        words = new String[0];
        boxes = new int[0];
        ocrW = 0;
        ocrH = 0;
    }

    void rotateCw() {
        rotation = (rotation + 90) % 360;
        corners = ScanMath.rotateCornersCw(corners);
    }

    /** Cheap check used when listing pages, without parsing everything. */
    static boolean hasTextQuick(File pageJpg) {
        File f = DocStore.metaFile(pageJpg);
        if (!f.exists()) return false;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains("\"text\":");
        } catch (IOException e) {
            return false;
        }
    }

    static PageMeta read(File pageJpg) {
        PageMeta m = new PageMeta();
        File f = DocStore.metaFile(pageJpg);
        if (!f.exists()) return m;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            m.rotation = o.optInt("rot", 0);
            if (o.has("filter")) {
                m.filter = o.getString("filter");
            } else {
                // pages saved by version 2 had only an on/off scan filter
                m.filter = o.optBoolean("scan", false) ? ImageOps.SCAN : ImageOps.ORIGINAL;
            }
            m.glare = o.optBoolean("glare", false);
            m.brightness = (float) o.optDouble("bright", 0);
            m.contrast = (float) o.optDouble("contrast", 0);
            m.sharpen = (float) o.optDouble("sharpen", 0);
            m.curve = (float) o.optDouble("curve", 0);
            m.fromPdfText = o.optBoolean("pdfText", false);
            m.spineLeft = o.optBoolean("spineLeft", false);
            m.srcPdf = o.optString("srcPdf", null);
            m.srcPage = o.optInt("srcPage", -1);
            JSONArray c = o.optJSONArray("corners");
            if (c != null && c.length() == 8) m.corners = floats(c);

            JSONArray er = o.optJSONArray("erase");
            if (er != null) {
                for (int i = 0; i < er.length(); i++) {
                    JSONObject s = er.getJSONObject(i);
                    m.erase.add(new Stroke((float) s.getDouble("w"), floats(s.getJSONArray("p"))));
                }
            }
            JSONArray mk = o.optJSONArray("marks");
            if (mk != null) {
                for (int i = 0; i < mk.length(); i++) {
                    JSONObject e = mk.getJSONObject(i);
                    Mark k = new Mark();
                    k.type = e.getString("t");
                    k.color = e.optInt("c", 0xFF000000);
                    k.width = (float) e.optDouble("w", 0.004);
                    k.pts = floats(e.getJSONArray("p"));
                    k.sigAspect = (float) e.optDouble("sa", 0.4);
                    JSONArray sg = e.optJSONArray("s");
                    if (sg != null) for (int j = 0; j < sg.length(); j++) k.sig.add(floats(sg.getJSONArray(j)));
                    m.marks.add(k);
                }
            }

            if (o.has("text")) {
                m.text = o.getString("text");
                m.ocrW = o.optInt("ocrW");
                m.ocrH = o.optInt("ocrH");
                JSONArray w = o.optJSONArray("words");
                if (w != null) {
                    m.words = new String[w.length()];
                    m.boxes = new int[w.length() * 4];
                    for (int i = 0; i < w.length(); i++) {
                        JSONArray e = w.getJSONArray(i);
                        m.words[i] = e.getString(0);
                        for (int k = 0; k < 4; k++) m.boxes[i * 4 + k] = e.getInt(k + 1);
                    }
                }
            }
        } catch (IOException | JSONException e) {
            return new PageMeta();
        }
        return m;
    }

    private static float[] floats(JSONArray a) throws JSONException {
        float[] out = new float[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = (float) a.getDouble(i);
        return out;
    }

    private static JSONArray array(float[] v) throws JSONException {
        JSONArray a = new JSONArray();
        for (float x : v) a.put(Math.round(x * 10000f) / 10000.0);
        return a;
    }

    void write(File pageJpg) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("rot", rotation);
            o.put("filter", filter);
            if (glare) o.put("glare", true);
            if (brightness != 0) o.put("bright", brightness);
            if (contrast != 0) o.put("contrast", contrast);
            if (sharpen != 0) o.put("sharpen", sharpen);
            if (curve != 0) o.put("curve", curve);
            if (fromPdfText && text != null) o.put("pdfText", true);
            if (spineLeft) o.put("spineLeft", true);
            if (srcPdf != null && srcPage >= 0) {
                o.put("srcPdf", srcPdf);
                o.put("srcPage", srcPage);
            }
            if (corners != null) o.put("corners", array(corners));
            if (!erase.isEmpty()) {
                JSONArray er = new JSONArray();
                for (Stroke s : erase) er.put(new JSONObject().put("w", s.width).put("p", array(s.pts)));
                o.put("erase", er);
            }
            if (!marks.isEmpty()) {
                JSONArray mk = new JSONArray();
                for (Mark k : marks) {
                    JSONObject e = new JSONObject().put("t", k.type).put("c", k.color).put("w", k.width)
                            .put("p", array(k.pts));
                    if (Mark.SIGNATURE.equals(k.type)) {
                        e.put("sa", k.sigAspect);
                        JSONArray sg = new JSONArray();
                        for (float[] s : k.sig) sg.put(array(s));
                        e.put("s", sg);
                    }
                    mk.put(e);
                }
                o.put("marks", mk);
            }
            if (text != null) {
                o.put("text", text);
                o.put("ocrW", ocrW);
                o.put("ocrH", ocrH);
                JSONArray w = new JSONArray();
                for (int i = 0; i < words.length; i++) {
                    JSONArray e = new JSONArray();
                    e.put(words[i]);
                    for (int k = 0; k < 4; k++) e.put(boxes[i * 4 + k]);
                    w.put(e);
                }
                o.put("words", w);
            }
            Files.write(DocStore.metaFile(pageJpg).toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }
}
