package com.dewanteam.textcipher;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.SecretKey;

public final class TextCipherService extends AccessibilityService {
    private static final String TAG = "TextCipherService";
    private static final int MAX_NODES = 180;
    private static final int MAX_DEPTH = 18;
    private static final int MAX_OVERLAYS = 10;
    private static final long CONTENT_DELAY_MS = 110L;
    private static final long SCROLL_DELAY_MS = 190L;
    private static final String KEY_PREFS = "textcipher_secret_store";
    private static final String KEY_PREF_NAME = "encrypted_password";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private OverlayManager overlays;
    private SharedPreferences keyPreferences;
    private SharedPreferences themePreferences;
    private SecretKey cachedKey;
    private String cachedPassword;
    private int visitedNodes;
    private AccessibilityNodeInfo focusedInput;
    private Rect focusedInputBounds;
    private boolean keyNeedsRefresh = true;

    private final Runnable scanRunnable = this::scanActiveWindow;
    private final SharedPreferences.OnSharedPreferenceChangeListener keyListener = (preferences, key) -> {
        if (KEY_PREF_NAME.equals(key)) {
            cachedPassword = null;
            cachedKey = null;
            keyNeedsRefresh = true;
            scheduleScan(0L);
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener themeListener = (preferences, key) -> {
        if (overlays != null) {
            handler.post(() -> {
                overlays.refreshTheme();
                scheduleScan(0L);
            });
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            overlays = new OverlayManager(this);
            keyPreferences = getSharedPreferences(KEY_PREFS, MODE_PRIVATE);
            keyPreferences.registerOnSharedPreferenceChangeListener(keyListener);
            themePreferences = getSharedPreferences(ThemeManager.PREFS_NAME, MODE_PRIVATE);
            themePreferences.registerOnSharedPreferenceChangeListener(themeListener);
            refreshKey();
            scheduleScan(CONTENT_DELAY_MS);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not initialize the screen service.", exception);
            if (overlays != null) overlays.clear();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || overlays == null) return;
        try {
            CharSequence packageName = event.getPackageName();
            if (packageName != null && getPackageName().contentEquals(packageName)) {
                handler.removeCallbacks(scanRunnable);
                overlays.clearMessageOverlays();
                overlays.updateEncryptChip(null, null);
                recycleFocusedInput();
                return;
            }

            int type = event.getEventType();
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {

                overlays.clearMessageOverlays();
                scheduleScan(SCROLL_DELAY_MS);
            } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    || type == AccessibilityEvent.TYPE_VIEW_FOCUSED
                    || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    || type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                scheduleScan(CONTENT_DELAY_MS);
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Ignoring an accessibility event that could not be read.", exception);
            overlays.clearMessageOverlays();
            scheduleScan(SCROLL_DELAY_MS);
        }
    }

    private void scheduleScan(long delayMillis) {
        handler.removeCallbacks(scanRunnable);
        handler.postDelayed(scanRunnable, delayMillis);
    }

    private void scanActiveWindow() {
        if (overlays == null) return;
        try {
            refreshKey();
            recycleFocusedInput();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                overlays.clearMessageOverlays();
                overlays.updateEncryptChip(null, null);
                return;
            }

            List<OverlayManager.MessageOverlay> candidates = new ArrayList<>();
            visitedNodes = 0;
            try {
                visitNode(root, 0, false, candidates);
            } finally {
                try {
                    root.recycle();
                } catch (RuntimeException ignored) {
                }
            }
            overlays.updateMessages(removeDuplicates(candidates));
            if (cachedKey != null && focusedInput != null && focusedInputBounds != null) {
                overlays.updateEncryptChip(focusedInputBounds, view -> encryptFocusedInput());
            } else {
                overlays.updateEncryptChip(null, null);
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Screen scan failed; will retry on the next accessibility event.", exception);
            overlays.clearMessageOverlays();
        }
    }

    private void recycleFocusedInput() {
        if (focusedInput != null) {
            try {
                focusedInput.recycle();
            } catch (RuntimeException ignored) {
            }
        }
        focusedInput = null;
        focusedInputBounds = null;
    }

    private void encryptFocusedInput() {
        AccessibilityNodeInfo target = focusedInput;
        if (target == null || cachedKey == null) return;
        if (!target.isEditable() || target.isPassword() || !target.isEnabled()) {
            overlays.updateEncryptChip(null, null);
            return;
        }

        CharSequence current = target.getText();
        if (current == null || current.toString().trim().isEmpty()) return;
        if (Crypto.looksEncrypted(current.toString())) {
            android.widget.Toast.makeText(this, "This text already starts with §.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String result = Crypto.encrypt(current.toString(), cachedKey);
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result);
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                scheduleScan(CONTENT_DELAY_MS);
            } else {
                android.widget.Toast.makeText(this, "This app does not allow TextCipher to replace its text.", android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (GeneralSecurityException exception) {
            android.widget.Toast.makeText(this, "Could not encrypt this text.", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshKey() {
        if (!keyNeedsRefresh) return;
        keyNeedsRefresh = false;
        String password = Crypto.loadPassword(this);
        if (password.isEmpty()) {
            cachedPassword = null;
            cachedKey = null;
            return;
        }
        if (password.equals(cachedPassword) && cachedKey != null) return;
        try {
            cachedKey = Crypto.deriveKey(password);
            cachedPassword = password;
        } catch (GeneralSecurityException exception) {
            cachedKey = null;
            cachedPassword = null;
        }
    }

    private void visitNode(AccessibilityNodeInfo node, int depth, boolean insideReplyPreview,
                           List<OverlayManager.MessageOverlay> matches) {
        if (node == null || depth > MAX_DEPTH || visitedNodes >= MAX_NODES) return;
        visitedNodes++;
        boolean skipText;
        try {
            skipText = insideReplyPreview || isReplyPreviewNode(node);
            if (node.isEditable() && node.isFocused() && node.isEnabled() && !node.isPassword()) {
                Rect inputBounds = new Rect();
                node.getBoundsInScreen(inputBounds);
                if (!inputBounds.isEmpty()) {
                    if (focusedInput != null) {
                        try {
                            focusedInput.recycle();
                        } catch (RuntimeException ignored) {
                        }
                    }
                    focusedInput = AccessibilityNodeInfo.obtain(node);
                    focusedInputBounds = inputBounds;
                }
            }
            CharSequence text = node.getText();
            if (!skipText && cachedKey != null && !node.isPassword()
                    && text != null && text.length() > 0) {
                String payload = findMarkedPayload(text.toString());
                if (payload != null) {
                    try {
                        String plaintext = Crypto.decrypt(payload, cachedKey);
                        if (shouldDisplay(plaintext)) {
                            Rect bounds = new Rect();
                            node.getBoundsInScreen(bounds);
                            if (!bounds.isEmpty()) {
                                String id = overlayId(node, bounds, payload);
                                matches.add(new OverlayManager.MessageOverlay(id, payload, plaintext, bounds));
                            }
                        }
                    } catch (GeneralSecurityException ignored) {
                    }
                }
            }

            for (int i = 0; i < node.getChildCount() && visitedNodes < MAX_NODES; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                try {
                    visitNode(child, depth + 1, skipText, matches);
                } finally {
                    try {
                        child.recycle();
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private boolean shouldDisplay(String plaintext) {
        if (plaintext == null || plaintext.trim().isEmpty()) return false;
      // sdia
        return true; // !plaintext.trim().matches("(?i)\\[sz:\\s*\\d+\\s*,\\s*\\d+\\s*\\]");
    }

    private boolean isReplyPreviewNode(AccessibilityNodeInfo node) {
        CharSequence id = node.getViewIdResourceName();
        CharSequence description = node.getContentDescription();
        String viewId = id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
        String hint = description == null ? "" : description.toString().toLowerCase(Locale.ROOT);
        return viewId.contains("reply_preview") || viewId.contains("replypreview")
                || viewId.contains("quoted_message") || viewId.contains("quotedmessage")
                || hint.contains("reply preview") || hint.contains("quoted message");
    }

    private String findMarkedPayload(String text) {
        int marker = text.indexOf(Crypto.PREFIX);
        while (marker >= 0) {
            int nextMarker = text.indexOf(Crypto.PREFIX, marker + Crypto.PREFIX.length());
            String section = text.substring(marker + Crypto.PREFIX.length(),
                    nextMarker >= 0 ? nextMarker : text.length()).trim();
            StringBuilder encoded = new StringBuilder(section.length());
            boolean foundData = false;

            for (String line : section.split("\\R")) {
                String part = line.trim();
                if (part.isEmpty()) continue;
                int validLength = firstNonBase64(part);
                if (validLength == 0) break;
                if (validLength < part.length()) part = part.substring(0, validLength);
                encoded.append(part.replaceAll("\\s+", ""));
                foundData = true;
                if (part.endsWith("=")) break;
            }

            String value = encoded.toString();
            if (foundData && value.length() >= 40 && value.matches("[A-Za-z0-9+/]*={0,2}")) return Crypto.PREFIX + value;
            marker = text.indexOf(Crypto.PREFIX, marker + Crypto.PREFIX.length());
        }
        return null;
    }

    private int firstNonBase64(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '='
                    || Character.isWhitespace(c))) return i;
        }
        return value.length();
    }

    private Map<String, OverlayManager.MessageOverlay> removeDuplicates(
            List<OverlayManager.MessageOverlay> candidates) {
        candidates.sort(Comparator.comparingLong(item -> area(item.bounds)));
        Map<String, OverlayManager.MessageOverlay> result = new HashMap<>();
        List<OverlayManager.MessageOverlay> accepted = new ArrayList<>();

        for (OverlayManager.MessageOverlay candidate : candidates) {
            boolean duplicate = false;
            for (OverlayManager.MessageOverlay existing : accepted) {
                if (candidate.payload.equals(existing.payload)
                        && overlapsSameText(candidate.bounds, existing.bounds)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                accepted.add(candidate);
                result.put(candidate.id, candidate);
                if (accepted.size() >= MAX_OVERLAYS) break;
            }
        }
        return result;
    }

    private boolean overlapsSameText(Rect first, Rect second) {
        Rect intersection = new Rect(first);
        if (!intersection.intersect(second)) return false;
        long sharedArea = area(intersection);
        long smallerArea = Math.min(area(first), area(second));
        return smallerArea > 0 && sharedArea / (double) smallerArea > 0.55d;
    }

    private long area(Rect bounds) {
        return (long) Math.max(0, bounds.width()) * Math.max(0, bounds.height());
    }

    private String overlayId(AccessibilityNodeInfo node, Rect bounds, String payload) {
        String viewId = node.getViewIdResourceName();
        String source = (viewId == null ? "" : viewId) + ':' + bounds.left + ':' + bounds.top
                + ':' + bounds.right + ':' + bounds.bottom + ':' + payload.hashCode();
        return Integer.toHexString(source.hashCode());
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(scanRunnable);
        recycleFocusedInput();
        if (overlays != null) overlays.clear();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(scanRunnable);
        if (keyPreferences != null) keyPreferences.unregisterOnSharedPreferenceChangeListener(keyListener);
        if (themePreferences != null) themePreferences.unregisterOnSharedPreferenceChangeListener(themeListener);
        recycleFocusedInput();
        if (overlays != null) overlays.clear();
        super.onDestroy();
    }
}
