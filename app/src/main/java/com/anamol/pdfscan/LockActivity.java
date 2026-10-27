package com.anamol.pdfscan;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Covers the app until the phone's own unlock (fingerprint, face, PIN...) succeeds. */
public class LockActivity extends Activity {
    private boolean asking;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.screen(this);
        root.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        int s = Ui.dp(this, 72);
        root.addView(icon, new LinearLayout.LayoutParams(s, s));
        TextView t = Ui.heading(this, "PDF Scan is locked");
        t.setGravity(Gravity.CENTER);
        root.addView(t, Ui.full(this, 20));
        TextView m = Ui.meta(this, "Unlock with your fingerprint, face or screen lock.");
        m.setGravity(Gravity.CENTER);
        root.addView(m, Ui.full(this, 8));
        Button unlock = Ui.button(this, "Unlock", true);
        unlock.setOnClickListener(v -> ask());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 200), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 28);
        root.addView(unlock, lp);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ask();
    }

    private void ask() {
        if (asking) return;
        asking = true;
        AppLock.authenticate(this, "Unlock PDF Scan", null, () -> {
            asking = false;
            AppLock.markUnlocked();
            finish();
            overridePendingTransition(0, 0);
        }, () -> asking = false);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        moveTaskToBack(true);  // stay locked; leave to the home screen
    }
}
