package com.dewanteam.textcipher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class SelectionActionActivity extends Activity {
    private static final int REQUEST_EDITOR = 42;

    private String selectedText;
    private boolean readOnly;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CharSequence selected = getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        selectedText = selected == null ? "" : selected.toString();
        readOnly = getIntent().getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false);

        if (selectedText.trim().isEmpty()) {
            finish();
            return;
        }

        String key = Crypto.loadPassword(this);
        if (key.isEmpty()) {
            openEditor();
            return;
        }
        transformSelectedText(key);
    }

    private void transformSelectedText(String key) {
        try {
            String result = Crypto.looksEncrypted(selectedText)
                    ? Crypto.decrypt(selectedText, key)
                    : Crypto.encrypt(selectedText, key);
            if (readOnly) showResult(result);
            else returnText(result);
        } catch (Exception exception) {
            Toast.makeText(this,
                    exception.getMessage() == null ? "Could not transform the selected text." : exception.getMessage(),
                    Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void openEditor() {
        Intent editor = new Intent(this, MainActivity.class);
        editor.setAction(Intent.ACTION_PROCESS_TEXT);
        editor.putExtra(Intent.EXTRA_PROCESS_TEXT, selectedText);
        editor.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, readOnly);
        startActivityForResult(editor, REQUEST_EDITOR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EDITOR) return;
        if (resultCode != RESULT_OK || data == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        CharSequence result = data.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (result == null) {
            setResult(RESULT_CANCELED);
            finish();
        } else if (readOnly) showResult(result.toString());
        else returnText(result.toString());
    }

    private void returnText(String result) {
        Intent reply = new Intent();
        reply.putExtra(Intent.EXTRA_PROCESS_TEXT, result);
        setResult(RESULT_OK, reply);
        finish();
    }

    private void showResult(String result) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(18));
        page.setBackgroundColor(Color.rgb(17, 24, 32));

        TextView title = new TextView(this);
        title.setText("Result");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(title);

        TextView output = new TextView(this);
        output.setText(result);
        output.setTextColor(Color.WHITE);
        output.setTextSize(16f);
        output.setTextIsSelectable(true);
        output.setPadding(0, dp(16), 0, dp(16));
        page.addView(output, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button copy = makeButton("Copy result");
        copy.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("TextCipher result", result));
                Toast.makeText(this, "Copied.", Toast.LENGTH_SHORT).show();
            }
        });
        page.addView(copy);

        Button share = makeButton("Share result");
        share.setOnClickListener(view -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, result);
            startActivity(Intent.createChooser(send, "Share result"));
        });
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shareParams.topMargin = dp(8);
        page.addView(share, shareParams);
        setContentView(page);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setTextColor(Color.rgb(5, 19, 25));
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(Color.rgb(125, 227, 248));
        return button;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
