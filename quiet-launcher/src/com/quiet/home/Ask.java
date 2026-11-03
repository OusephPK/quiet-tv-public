package com.quiet.home;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/**
 * Quiet as the TV's assistant. The remote's Google Assistant button (and "assist" requests in
 * general) doesn't arrive as a key: the TV asks Android to start "the assistant". This is it:
 * it wakes the voice (see Voice) and is gone before it's ever drawn.
 */
public class Ask extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(Voice.TAG, "asked by " + (getIntent() == null ? "?" : getIntent().getAction()) + " from " + getReferrer()
                + (getIntent() != null && getIntent().getExtras() != null ? " " + getIntent().getExtras().keySet() : ""));
        Voice v = Voice.self;
        if (v != null) v.ui.post(() -> v.pressed());
        else Log.w(Voice.TAG, "the voice isn't running");
        finish();
        overridePendingTransition(0, 0);
    }
}
