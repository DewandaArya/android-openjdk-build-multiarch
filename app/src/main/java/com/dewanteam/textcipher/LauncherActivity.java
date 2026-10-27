package com.dewanteam.textcipher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Toast;

public final class LauncherActivity extends Activity {
    @Override
    protected void onStart() {
        super.onStart();
        Intent settings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        settings.putExtra("android.intent.extra.COMPONENT_NAME",
                new ComponentName(this, TextCipherService.class).flattenToString());
        try {
            startActivity(settings);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "Open Accessibility settings to enable TextCipher.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class));
        }
        finish();
    }
}
