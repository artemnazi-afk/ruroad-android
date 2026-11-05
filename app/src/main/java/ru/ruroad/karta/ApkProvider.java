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
        String name = uri.getLastPathSegment();
        // 1) файлы выгрузок из публичной папки Загрузки (шаринг после скачивания)
        File pub = new File(
                android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), name);
        if (pub.exists()) {
            return ParcelFileDescriptor.open(pub, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        // 2) скачанные APK обновлений из кэша
        File dir = getContext().getExternalCacheDir();
        if (dir == null) dir = getContext().getCacheDir();
        File f = new File(dir, name);
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                String ext = MimeTypeMap.getFileExtensionFromUrl(name.substring(dot + 1));
                String mime = null;
                if (ext != null && !ext.isEmpty()) {
                    mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
                }
                if (mime == null && ext != null) {
                    if (ext.equalsIgnoreCase("dxf")) mime = "application/dxf";
                    else if (ext.equalsIgnoreCase("csv")) mime = "text/csv";
                    else if (ext.equalsIgnoreCase("txt")) mime = "text/plain";
                    else if (ext.equalsIgnoreCase("zip")) mime = "application/zip";
                }
                if (mime != null) return mime;
            }
        }
        return "application/octet-stream";
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
