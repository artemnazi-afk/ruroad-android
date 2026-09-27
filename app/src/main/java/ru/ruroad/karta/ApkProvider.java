package ru.ruroad.karta;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Минимальный ContentProvider для отдачи скачанного APK установщику
 * (content:// вместо file:// — обязательно с API 24).
 */
public class ApkProvider extends ContentProvider {

    static Uri uriFor(android.content.Context ctx, File f) {
        return new Uri.Builder()
                .scheme("content")
                .authority(ctx.getPackageName() + ".apkprovider")
                .path(f.getName())
                .build();
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File dir = getContext().getExternalCacheDir();
        if (dir == null) dir = getContext().getCacheDir();
        File f = new File(dir, uri.getLastPathSegment());
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
