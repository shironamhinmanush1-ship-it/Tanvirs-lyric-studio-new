package com.tanvir.lyricstudio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_WRITE_STORAGE = 1001;
    private static final int REQ_FILE_PICKER = 1002;
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final Map<String, PendingSave> activeSaves = new HashMap<>();
    private byte[] pendingBytes;
    private String pendingName;
    private String pendingMime;

    private static class PendingSave {
        final String name;
        final String mime;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PendingSave(String name, String mime) { this.name = name; this.mime = mime; }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF090B12);
        getWindow().setNavigationBarColor(0xFF090B12);
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF090B12);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*","video/*","image/*","application/json"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "ফাইল বাছাই করো"), REQ_FILE_PICKER);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "ফাইল বাছাই করা যাচ্ছে না", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });
        if (state != null) webView.restoreState(state);
        else webView.loadUrl("file:///android_asset/www/index.html");
    }

    public class AndroidBridge {
        @JavascriptInterface public synchronized String startFileSave(String name, String mime) {
            String safeName = (name == null || name.trim().isEmpty()) ? "lyric-studio-file" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
            String safeMime = (mime == null || mime.trim().isEmpty()) ? "application/octet-stream" : mime;
            String id = UUID.randomUUID().toString();
            activeSaves.put(id, new PendingSave(safeName, safeMime));
            return id;
        }
        @JavascriptInterface public synchronized void appendFileChunk(String id, String chunk) {
            PendingSave p = activeSaves.get(id);
            if (p == null || chunk == null || chunk.isEmpty()) return;
            byte[] decoded = Base64.decode(chunk, Base64.DEFAULT);
            p.bytes.write(decoded, 0, decoded.length);
        }
        @JavascriptInterface public synchronized void finishFileSave(String id) {
            PendingSave p = activeSaves.remove(id);
            if (p == null) return;
            byte[] data = p.bytes.toByteArray();
            runOnUiThread(() -> writeToDownloads(data, p.name, p.mime));
        }
    }

    private void writeToDownloads(byte[] data, String name, String mime) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LyricStudio");
                ContentResolver r = getContentResolver();
                Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("ফাইল তৈরি করা যায়নি");
                try (OutputStream out = r.openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("ফাইল লেখা যায়নি");
                    out.write(data);
                }
                Toast.makeText(this, "Downloads/LyricStudio-তে সেভ হয়েছে: " + name, Toast.LENGTH_LONG).show();
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pendingBytes = data; pendingName = name; pendingMime = mime;
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
                    return;
                }
                saveLegacy(data, name, mime);
            }
        } catch (Exception e) {
            Toast.makeText(this, "সেভ ব্যর্থ: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveLegacy(byte[] data, String name, String mime) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LyricStudio");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Downloads ফোল্ডার তৈরি হয়নি");
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); }
        MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, new String[]{mime}, null);
        Toast.makeText(this, "Downloads/LyricStudio-তে সেভ হয়েছে: " + name, Toast.LENGTH_LONG).show();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQ_WRITE_STORAGE && pendingBytes != null) {
            byte[] b = pendingBytes; String n = pendingName; String m = pendingMime;
            pendingBytes = null; pendingName = null; pendingMime = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                try { saveLegacy(b, n, m); }
                catch (Exception e) { Toast.makeText(this, "সেভ ব্যর্থ: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
            } else Toast.makeText(this, "ফাইল সেভের অনুমতি দেওয়া হয়নি", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_FILE_PICKER && filePathCallback != null) {
            Uri[] uris = null;
            if (result == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    uris = new Uri[count];
                    for (int i = 0; i < count; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) uris = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(uris);
            filePathCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("(function(){return window.onAndroidBack?Boolean(window.onAndroidBack()):false;})()", value -> {
                if (!"true".equals(value)) {
                    if (webView.canGoBack()) webView.goBack();
                    else finish();
                }
            });
        } else finish();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (webView != null) webView.saveState(out);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        if (filePathCallback != null) { filePathCallback.onReceiveValue(null); filePathCallback = null; }
        if (webView != null) { webView.loadUrl("about:blank"); webView.removeAllViews(); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
