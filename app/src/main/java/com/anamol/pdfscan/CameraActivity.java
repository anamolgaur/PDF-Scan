package com.anamol.pdfscan;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The camera, laid out like a phone camera app.
 *
 * Photo mode is fully manual: tap to focus and meter, exposure compensation slider, flash off /
 * auto / on / torch, and the shutter. Scan mode adds live page detection that only accepts real
 * sheets of paper; with Auto on it captures once a page has held steady for a moment, then waits
 * at least five seconds (and for the page to change) before the next one.
 *
 * Every shot is saved straight into the document; by default as a plain photo, to be cropped and
 * filtered later in the page preview.
 */
public class CameraActivity extends Activity {
    static final String EXTRA_SAVED = "saved";
    static final int RESULT_USE_SYSTEM_CAMERA = RESULT_FIRST_USER;
    private static final String EXTRA_DIR = "dir";
    private static final int REQ_PERMISSION = 7;
    private static final float STABLE_DELTA = 0.02f;
    /** A page must hold still this long before auto-capture. */
    private static final long STEADY_MS = 1300;
    /** Minimum pause between automatic captures, so there is time to turn the page. */
    private static final long COOLDOWN_MS = 5000;
    private static final long METER_MS = 8000;

    static Intent intent(Context c, File dir) {
        return new Intent(c, CameraActivity.class).putExtra(EXTRA_DIR, dir.getAbsolutePath());
    }

    private DocStore store;
    private File dir;

    private TextureView texture;
    private CameraOverlay overlay;
    private CameraOverlay.AspectFrame frame;
    private TextView status;
    private TextView countView;
    private Button autoBtn;
    private ImageButton flashBtn;
    private Button doneBtn;
    private Button fallbackBtn;
    private TextView photoTab;
    private TextView scanTab;
    private TextView idTab;
    private TextView qrTab;
    /** ID card mode: the front, waiting for the back. */
    private File idFront;
    private float[] idFrontCorners;
    private volatile long lastQrAt;
    private String lastQr;
    private SeekBar evBar;
    private TextView evText;
    private View shutter;

    private HandlerThread camThread;
    private Handler camHandler;
    private HandlerThread analysisThread;
    private Handler analysisHandler;
    private CameraDevice camera;
    private volatile CameraCaptureSession session;
    private CaptureRequest.Builder previewBuilder;
    private Surface previewSurface;
    private ImageReader jpegReader;
    private ImageReader yuvReader;
    private Size previewSize;
    private int sensorOrientation = 90;
    private boolean hasFlash;
    private Range<Integer> evRange;
    private Rational evStep;
    private Rect activeArray;
    private int maxAfRegions;
    private int maxAeRegions;

    private volatile int mode;
    private int flashMode;
    private float ev;
    private boolean autoOn;
    private volatile MeteringRectangle[] metering;
    private final Runnable endMetering = this::clearMetering;

    private float[] smoothed;
    private float[] lastRaw;
    private float[] lastThumb;
    private float[] capturedThumb;
    private float[] captureCorners;
    private long steadySince;
    private volatile long lastAnalysis;
    private int lost;
    private boolean armed = true;
    private boolean pageChanged = true;
    private long lastCaptureAt;
    private volatile boolean capturing;
    private long captureStarted;
    private volatile int precapture;  // 0 idle, 1 waiting to start, 2 waiting to finish
    private long precaptureStarted;
    private boolean permissionAsked;
    private int saved;
    private int pendingSaves;
    private boolean finishWhenSaved;
    private final ExecutorService saver = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DocStore(this);
        dir = new File(getIntent().getStringExtra(EXTRA_DIR));
        autoOn = Prefs.autoCapture(this);
        mode = Prefs.cameraMode(this);
        flashMode = Prefs.cameraFlash(this);
        ev = Prefs.cameraEv(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        int pad = Ui.dp(this, 10);
        root.setPadding(pad, pad, pad, pad);

        // top bar: close, auto capture (scan mode), flash
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton close = Ui.iconButton(this, R.drawable.ic_close, "Close");
        close.setOnClickListener(v -> done());
        autoBtn = Ui.chip(this, "Auto");
        autoBtn.setContentDescription("Capture automatically when a page holds steady");
        autoBtn.setOnClickListener(v -> {
            autoOn = !autoOn;
            Prefs.setAutoCapture(this, autoOn);
            steadySince = 0;
            refreshUi();
        });
        flashBtn = Ui.iconButton(this, R.drawable.ic_flash_off, "Flash");
        flashBtn.setOnClickListener(v -> cycleFlash());
        top.addView(close);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(autoBtn);
        LinearLayout.LayoutParams fl0 = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        fl0.leftMargin = Ui.dp(this, 8);
        top.addView(flashBtn, fl0);
        root.addView(top);

        // viewfinder
        FrameLayout stage = new FrameLayout(this);
        frame = new CameraOverlay.AspectFrame(this);
        texture = new TextureView(this);
        overlay = new CameraOverlay(this);
        overlay.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) tapToFocus(e.getX(), e.getY());
            return true;
        });
        frame.addView(texture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.gravity = Gravity.CENTER;
        stage.addView(frame, fl);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sl.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        root.addView(stage, sl);

        status = Ui.meta(this, "Starting camera…");
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Ui.CREAM);
        status.setMaxLines(2);
        root.addView(status, Ui.full(this, 0));

        fallbackBtn = Ui.button(this, "Use the phone's camera app", false);
        fallbackBtn.setVisibility(View.GONE);
        fallbackBtn.setOnClickListener(v -> {
            setResult(RESULT_USE_SYSTEM_CAMERA);
            finish();
        });
        root.addView(fallbackBtn, Ui.full(this, 6));

        root.addView(exposureRow(), Ui.full(this, 4));
        root.addView(modeRow(), Ui.full(this, 2));

        // bottom: done, shutter, count
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        doneBtn = Ui.button(this, "Done", true);
        doneBtn.setOnClickListener(v -> done());
        Ui.fit(doneBtn);
        shutter = new View(this);
        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.OVAL);
        inner.setColor(Ui.CREAM);
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(0x00000000);
        outer.setStroke(Ui.dp(this, 3), Ui.CREAM);
        LayerDrawable ring = new LayerDrawable(new android.graphics.drawable.Drawable[]{outer, inner});
        int inset = Ui.dp(this, 7);
        ring.setLayerInset(1, inset, inset, inset, inset);
        shutter.setBackground(ring);
        shutter.setContentDescription("Take picture");
        shutter.setClickable(true);
        shutter.setOnClickListener(v -> {
            v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()).start();
            takePicture();
        });
        countView = Ui.meta(this, "");
        countView.setGravity(Gravity.CENTER);
        countView.setTextColor(Ui.CREAM);
        countView.setMaxLines(2);
        LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int s = Ui.dp(this, 76);
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(s, s);
        mid.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        bottom.addView(doneBtn, side);
        bottom.addView(shutter, mid);
        bottom.addView(countView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bottom, Ui.full(this, 6));

        setContentView(root);
        refreshUi();
        updateCount();
    }

    private View exposureRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView sun = new ImageView(this);
        sun.setImageDrawable(Ui.tinted(this, R.drawable.ic_sun, Ui.CREAM));
        sun.setContentDescription("Exposure");
        int is = Ui.dp(this, 20);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(is, is);
        sp.leftMargin = Ui.dp(this, 8);
        row.addView(sun, sp);
        evBar = new SeekBar(this);
        evBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.CREAM));
        evBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.CREAM));
        evBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF5A5A5A));
        evBar.setEnabled(false);
        evBar.setContentDescription("Exposure compensation");
        evBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                if (!fromUser || evRange == null) return;
                ev = (p + evRange.getLower()) * evStep.floatValue();
                Prefs.setCameraEv(CameraActivity.this, ev);
                showEv();
                if (camHandler != null) camHandler.post(CameraActivity.this::startPreview);
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        });
        row.addView(evBar, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        evText = Ui.meta(this, "");
        evText.setTextColor(Ui.CREAM);
        evText.setGravity(Gravity.CENTER);
        evText.setMinWidth(Ui.dp(this, 64));
        evText.setMinHeight(Ui.dp(this, 44));
        evText.setContentDescription("Reset exposure");
        evText.setOnClickListener(v -> {
            ev = 0f;
            Prefs.setCameraEv(this, ev);
            syncEvBar();
            if (camHandler != null) camHandler.post(this::startPreview);
        });
        row.addView(evText);
        showEv();
        return row;
    }

    private View modeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        photoTab = modeTab("PHOTO", Prefs.CAMERA_PHOTO);
        scanTab = modeTab("SCAN", Prefs.CAMERA_SCAN);
        idTab = modeTab("ID CARD", Prefs.CAMERA_ID);
        qrTab = modeTab("QR", Prefs.CAMERA_QR);
        row.addView(photoTab);
        row.addView(scanTab);
        row.addView(idTab);
        row.addView(qrTab);
        return row;
    }

    private TextView modeTab(String label, final int m) {
        TextView t = Ui.label(this, label);
        t.setTextSize(13);
        t.setLetterSpacing(0.12f);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ui.dp(this, 44));
        t.setMinWidth(Ui.dp(this, 72));
        t.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        t.setSingleLine(true);
        t.setOnClickListener(v -> setMode(m));
        return t;
    }

    private void setMode(int m) {
        if (mode == m) return;
        mode = m;
        Prefs.setCameraMode(this, m);
        smoothed = null;
        lastRaw = null;
        steadySince = 0;
        armed = true;
        pageChanged = true;
        overlay.setQuad(null, 0);
        refreshUi();
    }

    private void cycleFlash() {
        if (!hasFlash && camera != null) {
            Ui.toast(this, "This camera has no flash");
            return;
        }
        flashMode = (flashMode + 1) % 4;
        Prefs.setCameraFlash(this, flashMode);
        String[] names = {"Flash off", "Flash auto", "Flash on", "Torch"};
        Ui.toast(this, names[flashMode]);
        refreshUi();
        if (camHandler != null) camHandler.post(this::startPreview);
    }

    private void refreshUi() {
        boolean scan = mode == Prefs.CAMERA_SCAN;
        boolean id = mode == Prefs.CAMERA_ID;
        boolean qr = mode == Prefs.CAMERA_QR;
        autoBtn.setVisibility(scan ? View.VISIBLE : View.GONE);
        shutter.setVisibility(qr ? View.INVISIBLE : View.VISIBLE);
        Ui.setChipSelected(this, autoBtn, autoOn);
        int[] icons = {R.drawable.ic_flash_off, R.drawable.ic_flash_auto, R.drawable.ic_flash_on, R.drawable.ic_torch};
        String[] names = {"Flash off", "Flash auto", "Flash on", "Torch"};
        flashBtn.setImageDrawable(Ui.tinted(this, icons[flashMode], Ui.CREAM));
        flashBtn.setContentDescription(names[flashMode] + ". Tap to change.");
        TextView[] tabs = {photoTab, scanTab, idTab, qrTab};
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == mode ? Ui.CREAM : 0xFF9A8A84);
            tabs[i].setTypeface(i == mode ? Ui.medium(this) : Ui.regular(this));
        }
        doneBtn.setVisibility(saved > 0 || pendingSaves > 0 ? View.VISIBLE : View.INVISIBLE);
        if (session != null && !capturing) {
            if (id) status.setText(idFront == null ? "ID card: photograph the FRONT, filling most of the frame"
                    : "Now turn the card over and photograph the BACK");
            else if (qr) status.setText("Point at a QR code or barcode");
            else status.setText(scan ? (autoOn ? "Looking for a page" : "Tap the shutter when the page is outlined")
                    : "Tap to focus. Adjust the exposure, then take the photo.");
        }
    }

    private void showEv() {
        evText.setText(String.format(Locale.US, "%+.1f EV", ev));
    }

    private void syncEvBar() {
        if (evRange == null) {
            showEv();
            return;
        }
        int idx = Math.round(ev / evStep.floatValue());
        idx = Math.max(evRange.getLower(), Math.min(evRange.getUpper(), idx));
        ev = idx * evStep.floatValue();
        evBar.setMax(evRange.getUpper() - evRange.getLower());
        evBar.setProgress(idx - evRange.getLower());
        evBar.setEnabled(evRange.getUpper() > evRange.getLower());
        showEv();
    }

    private int evIndex() {
        if (evRange == null) return 0;
        int idx = Math.round(ev / evStep.floatValue());
        return Math.max(evRange.getLower(), Math.min(evRange.getUpper(), idx));
    }

    private void updateCount() {
        String t = saved == 0 ? "" : saved + (saved == 1 ? " page" : " pages");
        if (pendingSaves > 0) t += (t.isEmpty() ? "" : "\n") + "saving " + pendingSaves;
        countView.setText(t);
        doneBtn.setText(saved > 0 ? "Done · " + saved : "Done");
        refreshUi();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionAsked) {
                permissionAsked = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERMISSION);
            }
            return;
        }
        startThreads();
        if (texture.isAvailable()) {
            openCamera();
        } else {
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                }
            });
        }
    }

    @Override
    protected void onPause() {
        overlay.removeCallbacks(endMetering);
        metering = null;
        closeCamera();
        stopThreads();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        saver.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERMISSION && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            fail("Camera permission was not given. You can still use the phone's camera app.");
        }
    }

    private void fail(String message) {
        Diag.log("Camera: " + message, null);
        status.setText(message);
        fallbackBtn.setVisibility(View.VISIBLE);
    }

    private void startThreads() {
        if (camThread != null) return;
        camThread = new HandlerThread("camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        analysisThread = new HandlerThread("page-detect");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());
    }

    private void stopThreads() {
        if (camThread == null) return;
        camThread.quitSafely();
        analysisThread.quitSafely();
        camThread = null;
        camHandler = null;
        analysisThread = null;
    }

    // ------------------------------------------------------------------ camera setup

    private static boolean fourThree(Size s) {
        int w = Math.max(s.getWidth(), s.getHeight());
        int h = Math.min(s.getWidth(), s.getHeight());
        return Math.abs(w * 3 - h * 4) <= w / 40;
    }

    private static Size pick(Size[] sizes, int maxWidth, int target) {
        Size best = null;
        for (Size s : sizes) {
            if (!fourThree(s) || s.getWidth() > maxWidth) continue;
            if (best == null || Math.abs(s.getWidth() - target) < Math.abs(best.getWidth() - target)) best = s;
        }
        if (best == null) {
            for (Size s : sizes) {
                if (s.getWidth() > maxWidth) continue;
                if (best == null || Math.abs(s.getWidth() - target) < Math.abs(best.getWidth() - target)) best = s;
            }
        }
        return best != null ? best : sizes[0];
    }

    private void openCamera() {
        CameraManager mgr = getSystemService(CameraManager.class);
        try {
            String id = null;
            CameraCharacteristics ch = null;
            for (String cid : mgr.getCameraIdList()) {
                CameraCharacteristics cc = mgr.getCameraCharacteristics(cid);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    id = cid;
                    ch = cc;
                    break;
                }
            }
            if (id == null) {
                fail("No back camera found.");
                return;
            }
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                fail("This camera can't be used here.");
                return;
            }
            Size jpegSize = pick(map.getOutputSizes(ImageFormat.JPEG), 4100, 4100);
            previewSize = pick(map.getOutputSizes(SurfaceTexture.class), 1920, 1440);
            Size yuvSize = pick(map.getOutputSizes(ImageFormat.YUV_420_888), 800, 640);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = flash != null && flash;
            evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (evStep == null || evStep.floatValue() <= 0f) evRange = null;
            activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Integer af = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Integer ae = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            maxAfRegions = af == null ? 0 : af;
            maxAeRegions = ae == null ? 0 : ae;
            runOnUiThread(() -> {
                syncEvBar();
                refreshUi();
            });

            // The activity is portrait, so the landscape sensor frame is shown rotated.
            frame.setRatio(previewSize.getHeight() / (float) previewSize.getWidth());
            jpegReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            jpegReader.setOnImageAvailableListener(this::onJpeg, camHandler);
            yuvReader = ImageReader.newInstance(yuvSize.getWidth(), yuvSize.getHeight(), ImageFormat.YUV_420_888, 3);
            yuvReader.setOnImageAvailableListener(this::onFrame, analysisHandler);

            mgr.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice c) {
                    camera = c;
                    createSession();
                }

                @Override
                public void onDisconnected(CameraDevice c) {
                    c.close();
                    camera = null;
                }

                @Override
                public void onError(CameraDevice c, int error) {
                    c.close();
                    camera = null;
                    runOnUiThread(() -> fail("The camera stopped (error " + error + ")."));
                }
            }, camHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            fail("Couldn't open the camera: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void createSession() {
        try {
            SurfaceTexture st = texture.getSurfaceTexture();
            if (st == null || camera == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(st);
            camera.createCaptureSession(Arrays.asList(previewSurface, yuvReader.getSurface(), jpegReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            startPreview();
                            runOnUiThread(() -> refreshUi());
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            runOnUiThread(() -> fail("This camera can't run the live view."));
                        }
                    }, camHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            runOnUiThread(() -> fail("Couldn't start the camera: " + e.getMessage()));
        }
    }

    private int aeMode() {
        if (!hasFlash) return CaptureRequest.CONTROL_AE_MODE_ON;
        switch (flashMode) {
            case Prefs.FLASH_AUTO:
                return CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;
            case Prefs.FLASH_ON:
                return CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
            default:
                return CaptureRequest.CONTROL_AE_MODE_ON;
        }
    }

    /** Settings shared by the live view and the still picture, so the photo looks like the preview. */
    private void applyControls(CaptureRequest.Builder b) {
        MeteringRectangle[] m = metering;
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AF_MODE, m != null && maxAfRegions > 0
                ? CaptureRequest.CONTROL_AF_MODE_AUTO : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        b.set(CaptureRequest.CONTROL_AE_MODE, aeMode());
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex());
        b.set(CaptureRequest.FLASH_MODE, hasFlash && flashMode == Prefs.FLASH_TORCH
                ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        if (m != null) {
            if (maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, m);
            if (maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, m);
        }
    }

    private void startPreview() {
        CameraDevice cam = camera;
        CameraCaptureSession s = session;
        if (cam == null || s == null) return;
        try {
            previewBuilder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            previewBuilder.addTarget(yuvReader.getSurface());
            applyControls(previewBuilder);
            s.setRepeatingRequest(previewBuilder.build(), previewCallback, camHandler);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            runOnUiThread(() -> fail("Couldn't start the preview: " + e.getMessage()));
        }
    }

    private void closeCamera() {
        try {
            if (session != null) session.close();
        } catch (IllegalStateException ignored) {
            // already closed
        }
        session = null;
        if (camera != null) camera.close();
        camera = null;
        if (jpegReader != null) jpegReader.close();
        if (yuvReader != null) yuvReader.close();
        jpegReader = null;
        yuvReader = null;
        capturing = false;
        precapture = 0;
    }

    // ------------------------------------------------------------------ tap to focus and meter

    private void tapToFocus(float vx, float vy) {
        overlay.showFocus(vx, vy);
        if (activeArray == null || session == null || camHandler == null || (maxAfRegions == 0 && maxAeRegions == 0)) return;
        float nx = vx / Math.max(1, overlay.getWidth());
        float ny = vy / Math.max(1, overlay.getHeight());
        // the view shows the sensor image rotated clockwise by the sensor orientation
        float sx, sy;
        switch (sensorOrientation) {
            case 90:
                sx = ny;
                sy = 1 - nx;
                break;
            case 180:
                sx = 1 - nx;
                sy = 1 - ny;
                break;
            case 270:
                sx = 1 - ny;
                sy = nx;
                break;
            default:
                sx = nx;
                sy = ny;
                break;
        }
        int half = Math.round(Math.min(activeArray.width(), activeArray.height()) * 0.08f);
        int cx = activeArray.left + Math.round(sx * activeArray.width());
        int cy = activeArray.top + Math.round(sy * activeArray.height());
        Rect r = new Rect(Math.max(activeArray.left, cx - half), Math.max(activeArray.top, cy - half),
                Math.min(activeArray.right - 1, cx + half), Math.min(activeArray.bottom - 1, cy + half));
        metering = new MeteringRectangle[]{new MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)};
        camHandler.post(() -> {
            startPreview();
            triggerFocus();
        });
        overlay.removeCallbacks(endMetering);
        overlay.postDelayed(endMetering, METER_MS);
    }

    private void triggerFocus() {
        CameraCaptureSession s = session;
        if (s == null || previewBuilder == null || maxAfRegions == 0) return;
        try {
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            s.capture(previewBuilder.build(), previewCallback, camHandler);
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        } catch (CameraAccessException | IllegalStateException e) {
            // ignore: focus stays continuous
        }
    }

    private void clearMetering() {
        metering = null;
        if (camHandler != null) camHandler.post(this::startPreview);
    }

    // ------------------------------------------------------------------ live detection (scan mode)

    /** Runs on the analysis thread for each preview frame (latest frame only). */
    private void onFrame(ImageReader reader) {
        Image img;
        try {
            img = reader.acquireLatestImage();
        } catch (IllegalStateException e) {
            return;
        }
        if (img == null) return;
        long t0 = SystemClock.uptimeMillis();
        if (mode == Prefs.CAMERA_QR) {
            if (t0 - lastAnalysis < 150) {
                img.close();
                return;
            }
            lastAnalysis = t0;
            String text;
            try {
                text = Barcodes.decode(img);
            } finally {
                img.close();
            }
            if (text != null) runOnUiThread(() -> onCode(text));
            return;
        }
        if ((mode != Prefs.CAMERA_SCAN && mode != Prefs.CAMERA_ID) || t0 - lastAnalysis < 110) {
            // photo mode needs no analysis; scan mode checks about nine frames a second (saves battery)
            img.close();
            return;
        }
        lastAnalysis = t0;
        int sw;
        int sh;
        int[] px;
        float[] thumb = new float[16 * 12];
        try {
            Image.Plane y = img.getPlanes()[0];
            ByteBuffer buf = y.getBuffer();
            int rs = y.getRowStride();
            int ps = y.getPixelStride();
            sw = img.getWidth() / 2;
            sh = img.getHeight() / 2;
            px = new int[sw * sh];
            for (int yy = 0; yy < sh; yy++) {
                int row = yy * 2 * rs;
                for (int xx = 0; xx < sw; xx++) {
                    int v = buf.get(row + xx * 2 * ps) & 0xFF;
                    px[yy * sw + xx] = 0xFF000000 | (v << 16) | (v << 8) | v;
                    thumb[(yy * 12 / sh) * 16 + (xx * 16 / sw)] += v;
                }
            }
        } finally {
            img.close();
        }
        float cell = (sw / 16f) * (sh / 12f);
        for (int i = 0; i < thumb.length; i++) thumb[i] /= cell;
        float[] c = ScanMath.detectPage(px, sw, sh);
        for (int r = 0; c != null && r < sensorOrientation / 90; r++) c = ScanMath.rotateCornersCw(c);
        final float[] corners = c;
        runOnUiThread(() -> onAnalysis(corners, thumb));
    }

    private static float maxDelta(float[] a, float[] b) {
        if (a == null || b == null) return 1f;
        float m = 0f;
        for (int i = 0; i < 8; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    private static float thumbDiff(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return s / a.length;
    }

    private void onAnalysis(float[] c, float[] thumb) {
        if (isFinishing() || session == null) return;
        if (mode == Prefs.CAMERA_ID) {
            overlay.setQuad(c, 0f);  // outline only; the card is taken with the shutter
            if (c != null) captureCorners = c;
            return;
        }
        if (mode != Prefs.CAMERA_SCAN) return;
        long now = SystemClock.uptimeMillis();
        lastThumb = thumb;
        if (c != null) {
            if (maxDelta(c, lastRaw) < STABLE_DELTA) {
                if (steadySince == 0) steadySince = now;
            } else {
                steadySince = 0;
            }
            lastRaw = c;
            lost = 0;
            if (smoothed == null) {
                smoothed = c.clone();
            } else {
                for (int i = 0; i < 8; i++) smoothed[i] += (c[i] - smoothed[i]) * 0.5f;
            }
        } else {
            lost++;
            steadySince = 0;
            if (lost > 3) {
                smoothed = null;
                lastRaw = null;
            }
        }
        long wait = lastCaptureAt + COOLDOWN_MS - now;
        if (!armed) {
            if (lost >= 4 || (capturedThumb != null && thumbDiff(thumb, capturedThumb) > 10f)) pageChanged = true;
            if (pageChanged && wait <= 0) {
                armed = true;
                steadySince = 0;
            }
        }
        float progress = steadySince == 0 || !armed || !autoOn ? 0f : Math.min(1f, (now - steadySince) / (float) STEADY_MS);
        overlay.setQuad(smoothed, progress);

        if (capturing && now - captureStarted > 6000) {  // capture lost
            capturing = false;
            precapture = 0;
        }
        if (capturing) {
            status.setText("Capturing…");
        } else if (!armed && wait > 0) {
            status.setText("Saved. Turn the page · next capture in " + (int) Math.ceil(wait / 1000.0) + " s");
        } else if (!armed) {
            status.setText("Saved. Put the next page in view.");
        } else if (smoothed == null) {
            status.setText("Looking for a page");
        } else if (autoOn) {
            status.setText("Hold steady");
        } else {
            status.setText("Page found. Tap the shutter.");
        }

        if (autoOn && armed && !capturing && c != null && smoothed != null && steadySince != 0
                && now - steadySince >= STEADY_MS && wait <= 0) {
            takePicture();
        }
    }

    // ------------------------------------------------------------------ capture

    /** Follows the auto-exposure pre-capture sequence (needed for flash) on the live view. */
    private final CameraCaptureSession.CaptureCallback previewCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
            int state = precapture;
            if (state == 0) return;
            Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
            boolean timedOut = SystemClock.uptimeMillis() - precaptureStarted > 1500;
            if (state == 1) {
                if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                        || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                        || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || timedOut) {
                    precapture = 2;
                }
            } else if (state == 2) {
                if (ae == null || ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE || timedOut) {
                    precapture = 0;
                    stillCapture();
                }
            }
        }
    };

    private void takePicture() {
        if (session == null || camera == null || capturing || jpegReader == null) return;
        capturing = true;
        captureStarted = SystemClock.uptimeMillis();
        captureCorners = smoothed != null ? smoothed.clone() : null;
        boolean needsPrecapture = hasFlash && (flashMode == Prefs.FLASH_AUTO || flashMode == Prefs.FLASH_ON);
        if (!needsPrecapture || previewBuilder == null) {
            stillCapture();
            return;
        }
        try {
            precaptureStarted = SystemClock.uptimeMillis();
            precapture = 1;
            previewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            session.capture(previewBuilder.build(), previewCallback, camHandler);
            previewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        } catch (CameraAccessException | IllegalStateException e) {
            precapture = 0;
            stillCapture();
        }
    }

    private void stillCapture() {
        CameraDevice cam = camera;
        CameraCaptureSession s = session;
        ImageReader jr = jpegReader;
        if (cam == null || s == null || jr == null) {
            capturing = false;
            return;
        }
        try {
            CaptureRequest.Builder b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(jr.getSurface());
            applyControls(b);
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 92);
            s.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r,
                                            android.hardware.camera2.CaptureFailure f) {
                    runOnUiThread(() -> {
                        capturing = false;
                        Ui.toast(CameraActivity.this, "The picture didn't come through. Try again.");
                    });
                }
            }, camHandler);
            runOnUiThread(() -> overlay.flash());
        } catch (CameraAccessException | IllegalStateException e) {
            runOnUiThread(() -> {
                capturing = false;
                Ui.toast(this, "Couldn't take the picture: " + e.getMessage());
            });
        }
    }

    /** Camera thread: the still image is ready. */
    private void onJpeg(ImageReader reader) {
        File f = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".jpg");
        try (Image img = reader.acquireNextImage()) {
            if (img == null) return;
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(bytes);
            }
        } catch (IOException | IllegalStateException e) {
            runOnUiThread(() -> {
                capturing = false;
                Ui.toast(this, "Couldn't save the picture: " + e.getMessage());
            });
            return;
        }
        runOnUiThread(() -> onCaptured(f));
    }

    /** QR or barcode found: show it with the useful actions. One dialog at a time. */
    private void onCode(String text) {
        long now = SystemClock.uptimeMillis();
        if (isFinishing() || (text.equals(lastQr) && now - lastQrAt < 4000)) return;
        if (qrDialog != null && qrDialog.isShowing()) return;
        lastQr = text;
        lastQrAt = now;
        shutter.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        qrDialog = Barcodes.showResult(this, text);
    }

    private android.app.AlertDialog qrDialog;

    private void onCaptured(File original) {
        if (mode == Prefs.CAMERA_ID) {
            onIdSide(original);
            return;
        }
        capturing = false;
        final float[] hint = captureCorners;
        armed = false;
        pageChanged = false;
        lastCaptureAt = SystemClock.uptimeMillis();
        steadySince = 0;
        capturedThumb = lastThumb;
        pendingSaves++;
        updateCount();
        final boolean straighten = Prefs.scanOnCapture(this);
        final String filter = Prefs.defaultFilter(this);
        saver.execute(() -> {
            boolean ok = true;
            try {
                PageMeta m = new PageMeta();
                if (straighten) {
                    m.filter = filter;
                    Bitmap small = ImageProcessor.decodeFile(original, 900);
                    m.corners = ImageProcessor.detectCorners(small);
                    small.recycle();
                    if (m.corners == null) m.corners = hint;
                } else {
                    m.filter = ImageOps.ORIGINAL;  // a normal photo; crop and filters come later
                }
                ImageProcessor.createPage(store, dir, original, m);
            } catch (IOException e) {
                ok = false;
            }
            final boolean success = ok;
            runOnUiThread(() -> {
                pendingSaves--;
                if (success) saved++;
                else Ui.toast(this, "A page couldn't be saved");
                updateCount();
                if (finishWhenSaved && pendingSaves == 0) done();
            });
        });
    }

    /** ID card mode: keep the front, then put front and back together on one page. */
    private void onIdSide(final File photo) {
        capturing = false;
        final float[] corners = captureCorners != null ? captureCorners.clone() : null;
        if (idFront == null) {
            idFront = photo;
            idFrontCorners = corners;
            refreshUi();
            Ui.toast(this, "Front saved. Now the back.");
            return;
        }
        final File front = idFront;
        final float[] frontCorners = idFrontCorners;
        idFront = null;
        idFrontCorners = null;
        saveIdCard(front, frontCorners, photo, corners);
        refreshUi();
    }

    private void saveIdCard(final File front, final float[] fc, final File back, final float[] bc) {
        pendingSaves++;
        updateCount();
        saver.execute(() -> {
            boolean ok = true;
            try {
                File page = new File(getCacheDir(), "idcard_" + System.currentTimeMillis() + ".jpg");
                ImageProcessor.composeIdCard(front, fc, back, bc, page);
                PageMeta m = new PageMeta();
                m.filter = ImageOps.ORIGINAL;
                ImageProcessor.createPage(store, dir, page, m);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Diag.log("ID card", e);
                ok = false;
            }
            final boolean success = ok;
            runOnUiThread(() -> {
                pendingSaves--;
                if (success) saved++;
                else Ui.toast(this, "The ID card page couldn't be made");
                updateCount();
                if (finishWhenSaved && pendingSaves == 0) done();
            });
        });
    }

    private void done() {
        if (idFront != null) {
            // only the front was taken: make the page with just that side
            File front = idFront;
            float[] fc = idFrontCorners;
            idFront = null;
            saveIdCard(front, fc, null, null);
        }
        if (pendingSaves > 0) {
            finishWhenSaved = true;
            status.setText("Saving " + pendingSaves + (pendingSaves == 1 ? " page…" : " pages…"));
            return;
        }
        setResult(saved > 0 ? RESULT_OK : RESULT_CANCELED, new Intent().putExtra(EXTRA_SAVED, saved));
        finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        done();
    }
}
