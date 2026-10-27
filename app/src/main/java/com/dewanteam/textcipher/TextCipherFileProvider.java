package com.dewanteam.textcipher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLConnection;
import java.util.List;

//annoy
public final class TextCipherFileProvider extends ContentProvider {
    private static final String SHARED_PATH = "shared";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        final File file;
        try {
            file = resolveFile(uri);
        } catch (FileNotFoundException exception) {
            return null;
        }

        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];

        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = file.length();
            } else if ("_id".equals(columns[i])) {
                row[i] = 0;
            } else {
                row[i] = null;
            }
        }

        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        try {
            String type = URLConnection.guessContentTypeFromName(resolveFile(uri).getName());
            return type == null ? "application/octet-stream" : type;
        } catch (FileNotFoundException exception) {
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("TextCipher shares files as read-only.");
        return ParcelFileDescriptor.open(resolveFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("This provider is read-only.");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("This provider is read-only.");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("This provider is read-only.");
    }

    private File resolveFile(Uri uri) throws FileNotFoundException {
        if (getContext() == null
                || !(getContext().getPackageName() + ".files").equals(uri.getAuthority())) {
            throw new FileNotFoundException("Unknown shared file.");
        }

        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() != 2
                || !SHARED_PATH.equals(segments.get(0))) {
            throw new FileNotFoundException("Unknown shared file.");
        }

        String name = segments.get(1);
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.contains("/") || name.contains("\\")) {
            throw new FileNotFoundException("Unknown shared file.");
        }

        File root = new File(getContext().getCacheDir(), SHARED_PATH);
        File file = new File(root, name);
        try {
            String rootPath = root.getCanonicalPath() + File.separator;
            if (!file.getCanonicalPath().startsWith(rootPath) || !file.isFile()) {
                throw new FileNotFoundException("Shared file was not found.");
            }
            return file;
        } catch (IOException exception) {
            throw new FileNotFoundException("Shared file could not be opened.");
        }
    }
}
