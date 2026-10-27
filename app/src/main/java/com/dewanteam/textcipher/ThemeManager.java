package com.dewanteam.textcipher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ThemeManager {
    public static final String PREFS_NAME = "textcipher_theme_settings";
    public static final String KEY_THEME_JSON = "theme_json";
    public static final String KEY_TEXTURE_REVISION = "texture_revision";
    public static final String TEXTURE_FILENAME = "overlay-tooltip.png";
    public static final String BACKGROUND_FILENAME = "tooltip-background.png";
    public static final String FRAME_FILENAME = "tooltip-frame.png";
    private static final String KEY_BACKGROUND_BORDER = "texture_background_border";
    private static final String KEY_FRAME_BORDER = "texture_frame_border";

    private static final int MAX_TEXTURE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TEXTURE_DIMENSION = 1024;

    private static final String DEFAULTS = "{\n"
            + "  \"background\": \"#111820\",\n"
            + "  \"surface\": \"#1C252F\",\n"
            + "  \"field\": \"#1C252F\",\n"
            + "  \"text\": \"#F0FCFF\",\n"
            + "  \"muted\": \"#AEBFCD\",\n"
            + "  \"accent\": \"#7DE3F8\",\n"
            + "  \"button\": \"#2B3845\",\n"
            + "  \"overlayBackground\": \"#F21A222B\",\n"
            + "  \"overlayBorder\": \"#FF7DE3F8\",\n"
            + "  \"overlayText\": \"#FFF0FCFF\",\n"
            + "  \"overlayRadiusDp\": 10\n"
            + "}";

    private ThemeManager() {
    }

    public static final class Theme {
        public final int background;
        public final int surface;
        public final int field;
        public final int text;
        public final int muted;
        public final int accent;
        public final int button;
        public final int overlayBackground;
        public final int overlayBorder;
        public final int overlayText;
        public final float overlayRadiusDp;

        Theme(JSONObject json) throws JSONException {
            background = color(json, "background");
            surface = color(json, "surface");
            field = color(json, "field");
            text = color(json, "text");
            muted = color(json, "muted");
            accent = color(json, "accent");
            button = color(json, "button");
            overlayBackground = color(json, "overlayBackground");
            overlayBorder = color(json, "overlayBorder");
            overlayText = color(json, "overlayText");
            overlayRadiusDp = (float) json.optDouble("overlayRadiusDp", 10);
        }

        private static int color(JSONObject json, String key) throws JSONException {
            String value = json.getString(key);
            try {
                return Color.parseColor(value);
            } catch (IllegalArgumentException exception) {
                throw new JSONException("Invalid color for " + key + ". Use #RRGGBB or #AARRGGBB.");
            }
        }
    }

    public static String defaultsJson() {
        return DEFAULTS;
    }

    public static String getJson(Context context) {
        return prefs(context).getString(KEY_THEME_JSON, DEFAULTS);
    }

    public static Theme get(Context context) {
        try {
            return new Theme(new JSONObject(getJson(context)));
        } catch (JSONException exception) {
            try {
                return new Theme(new JSONObject(DEFAULTS));
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    public static String saveJson(Context context, String source) throws JSONException {
        JSONObject supplied = new JSONObject(source);
        JSONObject defaults = new JSONObject(DEFAULTS);
        JSONObject normalized = new JSONObject();
        String[] keys = {
                "background", "surface", "field", "text", "muted", "accent", "button",
                "overlayBackground", "overlayBorder", "overlayText"
        };

        for (String key : keys) {
            String value = supplied.optString(key, defaults.getString(key));
            try {
                Color.parseColor(value);
            } catch (IllegalArgumentException exception) {
                throw new JSONException("Invalid color for " + key + ". Use #RRGGBB or #AARRGGBB.");
            }
            normalized.put(key, value);
        }

        double radius = supplied.optDouble("overlayRadiusDp", defaults.getDouble("overlayRadiusDp"));
        if (radius < 0 || radius > 32) throw new JSONException("overlayRadiusDp must be between 0 and 32.");
        normalized.put("overlayRadiusDp", radius);
        String stored = normalized.toString(2);
        prefs(context).edit().putString(KEY_THEME_JSON, stored).apply();
        return stored;
    }

    public static String reset(Context context) {
        prefs(context).edit().remove(KEY_THEME_JSON).apply();
        return DEFAULTS;
    }

    public static File textureFile(Context context) {
        File file = new File(context.getFilesDir(), TEXTURE_FILENAME);
        return file.isFile() ? file : null;
    }

    public static File backgroundFile(Context context) {
        File file = new File(context.getFilesDir(), BACKGROUND_FILENAME);
        return file.isFile() ? file : null;
    }

    public static File frameFile(Context context) {
        File file = new File(context.getFilesDir(), FRAME_FILENAME);
        return file.isFile() ? file : null;
    }

    public static int backgroundBorder(Context context) {
        return prefs(context).getInt(KEY_BACKGROUND_BORDER, 9);
    }

    public static int frameBorder(Context context) {
        return prefs(context).getInt(KEY_FRAME_BORDER, 10);
    }

    public static void clearTexture(Context context) {
        deleteTexture(context, TEXTURE_FILENAME);
        deleteTexture(context, BACKGROUND_FILENAME);
        deleteTexture(context, FRAME_FILENAME);
        SharedPreferences preferences = prefs(context);
        preferences.edit()
                .remove(KEY_BACKGROUND_BORDER)
                .remove(KEY_FRAME_BORDER)
                .putLong(KEY_TEXTURE_REVISION,
                        preferences.getLong(KEY_TEXTURE_REVISION, 0) + 1)
                .apply();
    }

    private static void deleteTexture(Context context, String name) {
        File file = new File(context.getFilesDir(), name);
        if (file.exists()) file.delete();
    }

    public static void importTexturePack(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IOException("Select a tooltip ZIP first.");

        Map<String, byte[]> entries = new HashMap<>();
        InputStream opened = context.getContentResolver().openInputStream(uri);
        if (opened == null) throw new IOException("The ZIP could not be opened.");
        try (InputStream source = opened; ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            int total = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                if (!name.equals("background.png") && !name.equals("frame.png")
                        && !name.equals("background.png.mcmeta") && !name.equals("frame.png.mcmeta")) {
                    continue;
                }

                int limit = name.endsWith(".mcmeta") ? 16 * 1024 : MAX_TEXTURE_BYTES;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    if (output.size() + read > limit) throw new IOException(name + " is too large.");
                    output.write(buffer, 0, read);
                }
                byte[] bytes = output.toByteArray();
                total += bytes.length;
                if (total > MAX_TEXTURE_BYTES * 2 + 32 * 1024) throw new IOException("The tooltip ZIP is too large.");
                entries.put(name, bytes);
            }
        }

        byte[] background = entries.get("background.png");
        byte[] frame = entries.get("frame.png");
        if (background == null || frame == null) throw new IOException("The ZIP must contain background.png and frame.png.");

        int backgroundWidth = validateTexture(background, "background.png");
        int frameWidth = validateTexture(frame, "frame.png");
        int backgroundSlice = readBorder(entries.get("background.png.mcmeta"), 9);
        int frameSlice = readBorder(entries.get("frame.png.mcmeta"), 10);
        if (backgroundWidth < backgroundSlice * 2 || frameWidth < frameSlice * 2) {
            throw new IOException("The nine-slice border is too large for the supplied texture.");
        }

        saveTexture(context, BACKGROUND_FILENAME, background);
        saveTexture(context, FRAME_FILENAME, frame);
        SharedPreferences preferences = prefs(context);
        preferences.edit()
                .putInt(KEY_BACKGROUND_BORDER, backgroundSlice)
                .putInt(KEY_FRAME_BORDER, frameSlice)
                .putLong(KEY_TEXTURE_REVISION, preferences.getLong(KEY_TEXTURE_REVISION, 0) + 1)
                .apply();
    }

    private static int validateTexture(byte[] bytes, String name) throws IOException {
        if (bytes.length < 24 || bytes[0] != (byte) 0x89 || bytes[1] != 'P'
                || bytes[2] != 'N' || bytes[3] != 'G') {
            throw new IOException(name + " is not a valid PNG.");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth < 1 || options.outHeight < 1
                || options.outWidth > MAX_TEXTURE_DIMENSION || options.outHeight > MAX_TEXTURE_DIMENSION) {
            throw new IOException(name + " must be no larger than 1024 x 1024.");
        }
        return Math.min(options.outWidth, options.outHeight);
    }

    private static int readBorder(byte[] metadata, int fallback) throws IOException {
        if (metadata == null) return fallback;
        try {
            JSONObject root = new JSONObject(new String(metadata, java.nio.charset.StandardCharsets.UTF_8));
            int border = root.getJSONObject("gui").getJSONObject("scaling").getInt("border");
            if (border < 1 || border > MAX_TEXTURE_DIMENSION / 2) {
                throw new IOException("The nine-slice border must be between 1 and 512 pixels.");
            }
            return border;
        } catch (JSONException exception) {
            throw new IOException("Could not read the texture's nine-slice metadata.", exception);
        }
    }

    private static void saveTexture(Context context, String name, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), name), false)) {
            output.write(bytes);
        }
    }

    public static void importTexture(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IOException("No PNG image was selected.");

        byte[] content;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("The selected image could not be opened.");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_TEXTURE_BYTES) throw new IOException("Choose a PNG texture smaller than 4 MB.");
                output.write(buffer, 0, read);
            }
            content = output.toByteArray();
        }

        if (content.length < 24 || content[0] != (byte) 0x89 || content[1] != 'P'
                || content[2] != 'N' || content[3] != 'G') {
            throw new IOException("Choose a valid PNG file.");
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(content, 0, content.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1
                || bounds.outWidth > MAX_TEXTURE_DIMENSION || bounds.outHeight > MAX_TEXTURE_DIMENSION) {
            throw new IOException("Texture dimensions must be no larger than 1024 x 1024.");
        }
        Bitmap decoded = BitmapFactory.decodeByteArray(content, 0, content.length);
        if (decoded == null) throw new IOException("Android could not decode that PNG.");
        decoded.recycle();

        File target = new File(context.getFilesDir(), TEXTURE_FILENAME);
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(content);
            output.flush();
        }
        SharedPreferences preferences = prefs(context);
        preferences.edit().putLong(KEY_TEXTURE_REVISION,
                preferences.getLong(KEY_TEXTURE_REVISION, 0) + 1).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
