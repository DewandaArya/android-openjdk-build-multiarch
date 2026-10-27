package com.dewanteam.textcipher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.MediaController;
import android.widget.VideoView;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_TEXTURE = 101;
    private static final int REQUEST_MEDIA = 102;
    private static final int REQUEST_SAVE_MEDIA = 103;

    private final ExecutorService mediaWorker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable keySaveTask = this::saveKeyAutomatically;

    private EditText keyField;
    private EditText messageField;
    private TextView statusView;
    private boolean processingSelection;
    private byte[] pendingMediaBytes;
    private String pendingMediaName = "decrypted-image.png";
    private String pendingMediaMime = "image/png";
    private boolean pendingMediaIsEncrypted;
    private Uri selectedMediaUri;
    private String selectedMediaName = "";
    private Button attachMediaButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        processingSelection = Intent.ACTION_PROCESS_TEXT.equals(getIntent().getAction());
        getWindow().setStatusBarColor(ThemeManager.get(this).background);
        getWindow().setNavigationBarColor(ThemeManager.get(this).background);
        buildScreen();
        keyField.setText(Crypto.loadPassword(this));
        loadIncomingText(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        processingSelection = Intent.ACTION_PROCESS_TEXT.equals(intent.getAction());
        if (keyField != null) keyField.setText(Crypto.loadPassword(this));
        loadIncomingText(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_TEXTURE) {
            try {
                ThemeManager.importTexturePack(this, uri);
                showStatus("Tooltip textures applied.", false);
            } catch (IOException exception) {
                showStatus(exception.getMessage() == null ? "Could not load that ZIP." : exception.getMessage(), true);
            }
        } else if (requestCode == REQUEST_MEDIA) selectMediaInput(uri);
        else if (requestCode == REQUEST_SAVE_MEDIA) savePendingMedia(uri);
    }

    @Override
    protected void onDestroy() {
        mediaWorker.shutdownNow();
        super.onDestroy();
    }

    private void buildScreen() {
        ThemeManager.Theme theme = ThemeManager.get(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(theme.background);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(14), dp(18), dp(18));
        scroll.addView(page);

        keyField = new EditText(this);
        keyField.setSingleLine(true);
        keyField.setTextColor(theme.text);
        keyField.setHintTextColor(theme.muted);
        keyField.setHint(Crypto.DEFAULT_KEY);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setTransformationMethod(PasswordTransformationMethod.getInstance());
        styleInput(keyField);
        page.addView(keyField, fullWidth());
        keyField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                mainHandler.removeCallbacks(keySaveTask);
                mainHandler.postDelayed(keySaveTask, 350);
            }
        });

        LinearLayout messageHeader = new LinearLayout(this);
        messageHeader.setOrientation(LinearLayout.HORIZONTAL);
        messageHeader.setGravity(Gravity.CENTER_VERTICAL);
        attachMediaButton = makeButton(selectedMediaUri == null ? "Attach media…" : "Change media…", false);
        attachMediaButton.setOnClickListener(view -> chooseMediaInput());
        LinearLayout.LayoutParams attachParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        attachParams.leftMargin = dp(8);
        messageHeader.addView(attachMediaButton, attachParams);
        page.addView(messageHeader, fullWidth());

        messageField = new EditText(this);
        messageField.setTextColor(theme.text);
        messageField.setHintTextColor(theme.muted);
        messageField.setHint("Message");
        messageField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        messageField.setGravity(Gravity.TOP | Gravity.START);
        messageField.setMinLines(5);
        messageField.setMaxLines(12);
        styleInput(messageField);
        page.addView(messageField, fullWidth());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button encrypt = makeButton("Encrypt", true);
        Button decrypt = makeButton("Decrypt", false);
        actions.addView(encrypt, weightedButton());
        actions.addView(decrypt, weightedButton());
        page.addView(actions);
        encrypt.setOnClickListener(view -> transformMessage(true));
        decrypt.setOnClickListener(view -> transformMessage(false));

        Button share = makeButton("Share result…", false);
        share.setOnClickListener(view -> shareResult());
        page.addView(share, buttonLayout());

        Button appearance = makeButton("Appearance / tooltip textures…", false);
        appearance.setOnClickListener(view -> showAppearanceDialog());
        page.addView(appearance, buttonLayout());

        Button accessibility = makeButton("Accessibility settings", false);
        accessibility.setOnClickListener(view -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        page.addView(accessibility, buttonLayout());

        statusView = new TextView(this);
        statusView.setTextColor(theme.accent);
        statusView.setTextSize(13f);
        statusView.setPadding(dp(2), dp(8), dp(2), dp(4));
        page.addView(statusView);

        setContentView(scroll);
    }

    private void loadIncomingText(Intent intent) {
        if (intent == null || messageField == null) return;
        CharSequence incoming = null;
        if (Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) incoming = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        else if (Intent.ACTION_SEND.equals(intent.getAction())) incoming = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (incoming != null) {
            messageField.setText(incoming);
            messageField.setSelection(messageField.length());
        }
    }

    private void saveKeyAutomatically() {
        mainHandler.removeCallbacks(keySaveTask);
        if (keyField == null) return;

        String key = keyField.getText().toString();
        if (key.isEmpty()) return;

        if (key.length() > Crypto.MAX_PASSWORD_LENGTH) {
            showStatus("key must be 128 characters or less.", true);
            return;
        }

        try {
            if (!key.equals(Crypto.loadPassword(this))) Crypto.savePassword(this, key);
        } catch (Exception exception) {
            showStatus(messageFor(exception, "couldn't save the key."), true);
        }
    }

    @Override
    protected void onPause() {
        saveKeyAutomatically();
        super.onPause();
    }

    private void transformMessage(boolean encrypt) {
        String key = keyField.getText().toString();
        if (key.isEmpty()) {
            showStatus("Enter a key first.", true);
            return;
        }
        if (selectedMediaUri != null) {
            transformMedia(encrypt, key);
            return;
        }

        String message = messageField.getText().toString();
        try {
            String result = encrypt ? Crypto.encrypt(message, key) : Crypto.decrypt(message, key);
            messageField.setText(result);
            messageField.setSelection(messageField.length());
            showStatus(encrypt ? "done. Check result before share." : "Decrypted.", false);
            if (processingSelection) {
                Intent reply = new Intent();
                reply.putExtra(Intent.EXTRA_PROCESS_TEXT, result);
                setResult(RESULT_OK, reply);
                finish();
            }
        } catch (Exception exception) {
            showStatus(messageFor(exception, "Could not transform this message."), true);
        }
    }

    private void shareResult() {
        String text = messageField.getText().toString();
        if (text.trim().isEmpty()) {
            showStatus("There is no message to share yet.", true);
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "Share message"));
    }

    private void showAppearanceDialog() {
        ThemeManager.Theme theme = ThemeManager.get(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), dp(4));

        EditText jsonField = new EditText(this);
        jsonField.setGravity(Gravity.TOP | Gravity.START);
        jsonField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        jsonField.setMinLines(9);
        jsonField.setMaxLines(14);
        jsonField.setText(ThemeManager.getJson(this));
        jsonField.setTextColor(theme.text);
        jsonField.setHintTextColor(theme.muted);
        styleInput(jsonField);
        LinearLayout.LayoutParams jsonParams = fullWidth();
        jsonParams.topMargin = dp(8);
        content.addView(jsonField, jsonParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Appearance")
                .setView(content)
                .setPositiveButton("Save and Exit", null)
                .create();

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        Button texture = makeButton("Import tooltip ZIP…", false);
        texture.setOnClickListener(view -> {
            dialog.dismiss();
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("*/*");
            startActivityForResult(picker, REQUEST_TEXTURE);
        });
        Button removeTexture = makeButton("Reset tooltip textures", false);
        removeTexture.setOnClickListener(view -> {
            ThemeManager.clearTexture(this);
            showStatus("tooltip texture reset.", false);
        });
        Button reset = makeButton("Reset", false);
        reset.setOnClickListener(view -> {
            String key = keyField.getText().toString();
            String message = messageField.getText().toString();
            ThemeManager.reset(this);
            dialog.dismiss();
            buildScreen();
            keyField.setText(key);
            messageField.setText(message);
            showStatus("reset.", false);
        });

        buttons.addView(texture, buttonLayout());
        buttons.addView(removeTexture, buttonLayout());
        buttons.addView(reset, buttonLayout());
        content.addView(buttons);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String key = keyField.getText().toString();
            String message = messageField.getText().toString();
            try {
                ThemeManager.saveJson(this, jsonField.getText().toString());
                dialog.dismiss();
                buildScreen();
                keyField.setText(key);
                messageField.setText(message);
                messageField.setSelection(messageField.length());
                showStatus("saved.", false);
            } catch (JSONException exception) {
                jsonField.setError("this JSON doesn't look right.");
            }
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void chooseMediaInput() {
        if (selectedMediaUri == null) {
            pickMediaInput();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Message input")
                .setItems(new String[]{"Change media…", "Use text instead"}, (dialog, which) -> {
                    if (which == 0) {
                        pickMediaInput();
                    } else {
                        selectedMediaUri = null;
                        selectedMediaName = "";
                        attachMediaButton.setText("Attach media…");
                        showStatus("Text input selected.", false);
                    }
                })
                .show();
    }

    private void pickMediaInput() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        try {
            startActivityForResult(picker, REQUEST_MEDIA);
        } catch (RuntimeException exception) {
            showStatus("No file picker is available on this device.", true);
        }
    }

    private void selectMediaInput(Uri uri) {
        selectedMediaUri = uri;
        selectedMediaName = queryDisplayName(uri);
        attachMediaButton.setText("Change media…");
        showStatus("selected: " + selectedMediaName + "…", false);
    }

    private void transformMedia(boolean encrypt, String key) {
        Uri sourceUri = selectedMediaUri;
        String inputName = selectedMediaName;
        selectedMediaUri = null;
        selectedMediaName = "";
        attachMediaButton.setText("Attach media…");
        showStatus(encrypt ? "encrypting…" : "decrypting…", false);

        mediaWorker.execute(() -> {
            try {
                byte[] source = readLimited(sourceUri);
                byte[] result = encrypt ? Crypto.encryptMedia(source, key) : Crypto.decryptMedia(source, key);
                String mime = encrypt ? "application/octet-stream" : detectMediaMime(result);
                String name = encrypt ? encryptedOutputName(inputName) : outputName(inputName, mime);
                Bitmap preview = mime.startsWith("image/") ? makePreview(result) : null;
                mainHandler.post(() -> showMediaPreview(result, preview, name, mime, encrypt));
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    selectMediaInput(sourceUri);
                    showStatus(messageFor(exception, encrypt
                            ? "Could not encrypt that file."
                            : "Could not decrypt that file. Check the key and file integrity."), true);
                });
            }
        });
    }

    private byte[] readLimited(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new FileNotFoundException("The selected file could not be opened.");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > Crypto.MAX_MEDIA_BYTES + 64) throw new IOException("Choose a file smaller than 40 MB.");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private Bitmap makePreview(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null;

        int sample = 1;
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private String detectMediaMime(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8) return "image/jpeg";
        if (data.length >= 8 && (data[0] & 0xff) == 0x89 && data[1] == 'P'
                && data[2] == 'N' && data[3] == 'G') return "image/png";
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return "image/gif";
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return "image/webp";
        if (data.length >= 12 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') return "video/mp4";
        if (data.length >= 4 && (data[0] & 0xff) == 0x1a && (data[1] & 0xff) == 0x45
                && (data[2] & 0xff) == 0xdf && (data[3] & 0xff) == 0xa3) return "video/webm";
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'A' && data[9] == 'V' && data[10] == 'I' && data[11] == ' ') return "video/x-msvideo";
        return "application/octet-stream";
    }

    private String encryptedOutputName(String inputName) {
        String name = inputName == null || inputName.trim().isEmpty() ? "encrypted-media" : new File(inputName).getName();
        if (name.toLowerCase(Locale.ROOT).contains(".enc.")) return name;
        int extension = name.lastIndexOf('.');
        if (extension > 0) return name.substring(0, extension) + ".enc" + name.substring(extension);
        return name + ".enc";
    }

    private String outputName(String inputName, String mime) {
        String name = inputName == null || inputName.trim().isEmpty() ? "encrypted-media" : inputName;
        int encryptedPart = name.toLowerCase(Locale.ROOT).indexOf(".enc.");
        if (encryptedPart >= 0) name = name.substring(0, encryptedPart) + name.substring(encryptedPart + 4);
        else if (name.toLowerCase(Locale.ROOT).endsWith(".enc")) name = name.substring(0, name.length() - 4);
        if (name.indexOf('.') < 0) {
            if ("image/jpeg".equals(mime)) name += ".jpg";
            else if ("image/png".equals(mime)) name += ".png";
            else if ("image/gif".equals(mime)) name += ".gif";
            else if ("image/webp".equals(mime)) name += ".webp";
            else if ("video/mp4".equals(mime)) name += ".mp4";
            else if ("video/webm".equals(mime)) name += ".webm";
            else if ("video/x-msvideo".equals(mime)) name += ".avi";
            else if ("video/quicktime".equals(mime)) name += ".mov";
            else name += ".bin";
        }
        return name;
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (RuntimeException ignored) {
        }
        return "encrypted-media.enc.jpg";
    }

    private void showMediaPreview(byte[] result, Bitmap preview, String name, String mime, boolean encrypted) {
        pendingMediaBytes = result;
        pendingMediaName = name;
        pendingMediaMime = mime;
        pendingMediaIsEncrypted = encrypted;

        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Media")
                .setPositiveButton("Share…", (window, which) -> sharePendingMedia())
                .setNeutralButton("Save copy…", (window, which) -> savePendingMedia())
                .setNegativeButton("Close", null);

        if (preview != null) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(preview);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setMaxHeight(dp(480));
            image.setMaxWidth(dp(360));
            dialog.setView(image);
        } else if (mime.startsWith("video/")) {
            try {
                File directory = new File(getCacheDir(), "media-preview");
                if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not prepare video preview.");
                String suffix = "video/webm".equals(mime) ? ".webm" : ".mp4";
                File file = File.createTempFile("preview-", suffix, directory);
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(result);
                }
                VideoView video = new VideoView(this);
                video.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
                video.setVideoURI(Uri.fromFile(file));
                video.setOnErrorListener((player, what, extra) -> {
                    Toast.makeText(this,
                            "couldn't play that video here. Save or share it to open elsewhere.",
                            Toast.LENGTH_LONG).show();
                    return true;
                });
                MediaController controls = new MediaController(this);
                controls.setAnchorView(video);
                video.setMediaController(controls);
                video.setOnPreparedListener(player -> video.start());
                dialog.setView(video);
            } catch (IOException exception) {
                dialog.setMessage("Video is ready to share or save, but its preview could not be opened.");
            }
        } else {
            dialog.setMessage("File: " + name);
        }

        dialog.show();
    }

    private void sharePendingMedia() {
        if (pendingMediaBytes == null) return;
        try {
            File directory = new File(getCacheDir(), "shared");
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not prepare the file for sharing.");
            File file = new File(directory, new File(pendingMediaName).getName());
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(pendingMediaBytes);
            }
            Uri uri = new Uri.Builder()
                    .scheme("content")
                    .authority(getPackageName() + ".files")
                    .appendPath("shared")
                    .appendPath(file.getName())
                    .build();
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(pendingMediaMime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(android.content.ClipData.newUri(getContentResolver(), pendingMediaName, uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share media"));
        } catch (Exception exception) {
            showStatus(messageFor(exception, "Could not share this file."), true);
        }
    }

    private void savePendingMedia() {
        if (pendingMediaBytes == null) {
            showStatus("There is no decrypted file to save.", true);
            return;
        }
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType(pendingMediaMime);
        create.putExtra(Intent.EXTRA_TITLE, pendingMediaName);
        try {
            startActivityForResult(create, REQUEST_SAVE_MEDIA);
        } catch (RuntimeException exception) {
            showStatus("No file saver is available on this device.", true);
        }
    }

    private void savePendingMedia(Uri destination) {
        if (pendingMediaBytes == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IOException("The destination could not be opened.");
            output.write(pendingMediaBytes);
            output.flush();
            pendingMediaBytes = null;
            showStatus(pendingMediaIsEncrypted ? "Encrypted file saved." : "Decrypted file saved.", false);
        } catch (IOException exception) {
            showStatus(exception.getMessage() == null ? "Could not save the decrypted file." : exception.getMessage(), true);
        }
    }

    private void showStatus(String message, boolean error) {
        if (statusView != null) {
            ThemeManager.Theme theme = ThemeManager.get(this);
            statusView.setText(message);
            statusView.setTextColor(error ? Color.rgb(255, 153, 153) : theme.accent);
        }
    }

    private String messageFor(Exception exception, String fallback) {
        return exception.getMessage() == null ? fallback : exception.getMessage();
    }

    private void addLabel(LinearLayout parent, String text) {
        ThemeManager.Theme theme = ThemeManager.get(this);
        TextView label = new TextView(this);
        label.setText("key".equals(text) ? "key" : text.toUpperCase(Locale.ROOT));
        label.setTextColor(theme.accent);
        label.setTextSize(11f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(dp(2), dp(8), dp(2), dp(6));
        parent.addView(label);
    }

    private void styleInput(EditText field) {
        ThemeManager.Theme theme = ThemeManager.get(this);
        field.setTextSize(15f);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(theme.field);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), theme.muted & 0x44ffffff);
        field.setBackground(background);
    }

    private Button makeButton(String text, boolean primary) {
        ThemeManager.Theme theme = ThemeManager.get(this);
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? theme.background : theme.text);
        button.setPadding(dp(8), dp(5), dp(8), dp(5));
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? theme.accent : theme.button);
        background.setCornerRadius(dp(10));
        button.setBackground(background);
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonLayout() {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(7);
        params.bottomMargin = dp(3);
        return params;
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), dp(7), dp(2), dp(3));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
