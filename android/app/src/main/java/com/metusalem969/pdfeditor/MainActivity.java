package com.metusalem969.pdfeditor;

import android.Manifest;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;
import androidx.documentfile.provider.DocumentFile;

import com.getcapacitor.BridgeActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class MainActivity extends BridgeActivity {

    private static final int MAX_FILE_BYTES = 25 * 1024 * 1024;
    private static final int MAX_DELIVER_RETRIES = 120;
    private static final int MAX_READ_RETRIES = 8;
    private static final int REQ_STORAGE = 9102;

    private byte[] pendingSaveBytes;
    private PendingIncomingFile pendingIncoming;
    private int deliverRetries = 0;
    private int readRetries = 0;
    private WebView bridgedWebView = null;
    private String pendingToast = null;
    private Intent heldIntent = null;
    private Uri heldUri = null;
    private String heldName = null;
    private boolean openDocumentFallbackUsed = false;
    private boolean waitingStoragePermission = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable readIncomingRunnable = this::tryReadIncoming;

    private ActivityResultLauncher<Intent> saveDocumentLauncher;
    private ActivityResultLauncher<String[]> openDocumentLauncher;

    private static final class PendingIncomingFile {
        final byte[] data;
        final String name;
        final String type;

        PendingIncomingFile(byte[] data, String name, String type) {
            this.data = data;
            this.name = name;
            this.type = type;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        saveDocumentLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> handleSaveResult(result.getResultCode(), result.getData())
        );
        openDocumentLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null) {
                    toastJs("❌ Deschidere anulată");
                    clearHeldIntent();
                    return;
                }
                try {
                    final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(uri, flags);
                } catch (Exception ignored) {
                }
                heldUri = uri;
                heldIntent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, getContentResolver().getType(uri));
                heldName = resolveDisplayName(uri);
                readRetries = 0;
                openDocumentFallbackUsed = false;
                tryReadIncoming();
            }
        );
        cacheIncomingIntent(getIntent());
        super.onCreate(savedInstanceState);
        scheduleReadIncoming(100);
    }

    @Override
    public void onStart() {
        super.onStart();
        configureWebView();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pendingIncoming == null && heldUri != null) {
            scheduleReadIncoming(50);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingIncoming = null;
        openDocumentFallbackUsed = false;
        cacheIncomingIntent(intent);
        scheduleReadIncoming(0);
    }

    @Override
    public void onResume() {
        super.onResume();
        configureWebView();
        if (pendingIncoming == null && heldUri != null && !waitingStoragePermission) {
            scheduleReadIncoming(0);
        }
        notifyJsIncomingFile();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            waitingStoragePermission = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scheduleReadIncoming(0);
            } else {
                launchOpenDocumentFallback();
            }
        }
    }

    private void cacheIncomingIntent(Intent intent) {
        if (!isFileOpenIntent(intent)) return;
        heldIntent = intent;
        heldUri = extractUri(intent);
        heldName = heldUri != null ? resolveDisplayName(heldUri) : null;
        readRetries = 0;
    }

    private void scheduleReadIncoming(long delayMs) {
        mainHandler.removeCallbacks(readIncomingRunnable);
        if (heldUri == null || pendingIncoming != null || waitingStoragePermission) return;
        mainHandler.postDelayed(readIncomingRunnable, delayMs);
    }

    private void tryReadIncoming() {
        if (pendingIncoming != null || heldUri == null || waitingStoragePermission) return;

        if (needsStoragePermission()) {
            waitingStoragePermission = true;
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }

        Intent intent = heldIntent != null ? heldIntent : getIntent();
        if (!isFileOpenIntent(intent)) return;

        Uri uri = heldUri;
        String name = heldName != null ? heldName : resolveDisplayName(uri);
        final String lower = name.toLowerCase();
        String mime = null;
        try {
            mime = getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        if (mime == null && intent.getType() != null) mime = intent.getType();

        try {
            byte[] data = readUriBytes(intent, uri, name);
            if (data.length == 0) {
                failRead("❌ Fișier gol");
                return;
            }
            if (data.length > MAX_FILE_BYTES) {
                toastJs("❌ Fișier prea mare (max 25 MB)");
                clearHeldIntent();
                return;
            }

            String type = detectFileType(lower, mime, data);
            if (type == null) {
                toastJs("⚠️ Doar PDF, HTML și TXT");
                clearHeldIntent();
                return;
            }

            if ("pdf".equals(type) && !lower.endsWith(".pdf")) name += ".pdf";
            if ("txt".equals(type) && !lower.endsWith(".txt")) name += ".txt";

            pendingIncoming = new PendingIncomingFile(data, name, type);
            deliverRetries = 0;
            clearHeldIntent();
            clearIntentPayload(intent);
            notifyJsIncomingFile();
        } catch (Exception e) {
            failRead(null);
        }
    }

    private boolean needsStoragePermission() {
        if (heldUri == null) return false;
        if (!"file".equalsIgnoreCase(heldUri.getScheme())) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED;
    }

    private void failRead(String finalMessage) {
        readRetries++;
        if (readRetries < MAX_READ_RETRIES) {
            scheduleReadIncoming(400);
            return;
        }
        if (!openDocumentFallbackUsed) {
            launchOpenDocumentFallback();
            return;
        }
        toastJs(finalMessage != null ? finalMessage : "❌ Nu s-a putut citi fișierul");
        notifyJsOpenFailed();
        clearHeldIntent();
    }

    private void launchOpenDocumentFallback() {
        openDocumentFallbackUsed = true;
        readRetries = 0;
        toastJs("📂 Alege fișierul din listă");
        mainHandler.postDelayed(() -> {
            try {
                openDocumentLauncher.launch(new String[]{
                    "application/pdf", "text/html", "text/plain", "application/octet-stream", "*/*"
                });
            } catch (Exception e) {
                toastJs("❌ Nu s-a putut citi fișierul");
                notifyJsOpenFailed();
                clearHeldIntent();
            }
        }, 400);
    }

    private void notifyJsOpenFailed() {
        if (bridge == null || bridge.getWebView() == null) {
            mainHandler.postDelayed(this::notifyJsOpenFailed, 300);
            return;
        }
        bridge.getWebView().post(() -> {
            if (bridge != null && bridge.getWebView() != null) {
                bridge.getWebView().evaluateJavascript(
                    "window.onExternalOpenFailed && window.onExternalOpenFailed()",
                    null
                );
            }
        });
    }

    private void clearHeldIntent() {
        heldIntent = null;
        heldUri = null;
        heldName = null;
        readRetries = 0;
        waitingStoragePermission = false;
        mainHandler.removeCallbacks(readIncomingRunnable);
    }

    private void configureWebView() {
        if (bridge == null || bridge.getWebView() == null) return;
        WebView webView = bridge.getWebView();
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        if (webView != bridgedWebView) {
            bridgedWebView = webView;
            webView.addJavascriptInterface(new SaveFileBridge(this), "AndroidSave");
            webView.addJavascriptInterface(new FileOpenBridge(this), "AndroidFileOpen");
        }
        flushPendingToast();
    }

    private boolean isFileOpenIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        return Intent.ACTION_VIEW.equals(action) || Intent.ACTION_SEND.equals(action);
    }

    private Uri extractUri(Intent intent) {
        if (intent == null) return null;
        Uri uri = intent.getData();
        if (uri != null) return uri;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
            if (uri != null) return uri;
        }
        ClipData clip = intent.getClipData();
        if (clip != null && clip.getItemCount() > 0) {
            return clip.getItemAt(0).getUri();
        }
        return null;
    }

    private byte[] readUriBytes(Intent intent, Uri uri, String displayName) throws Exception {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Exception last = null;

        try {
            DocumentFile doc = DocumentFile.fromSingleUri(this, uri);
            if (doc != null && doc.exists() && doc.canRead()) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in != null) return readBytes(in);
                }
            }
        } catch (Exception e) {
            last = e;
        }

        try {
            File direct = fileFromDocumentUri(uri, displayName);
            if (direct != null) {
                try (FileInputStream in = new FileInputStream(direct)) {
                    return readBytes(in);
                }
            }
        } catch (Exception e) {
            last = e;
        }

        try {
            byte[] fromStore = readFromMediaStoreDownloads(displayName);
            if (fromStore != null) return fromStore;
        } catch (Exception e) {
            last = e;
        }

        try {
            return readBytes(openUriStream(uri));
        } catch (Exception e) {
            last = e;
        }

        try {
            android.content.ContentProviderClient client = getContentResolver().acquireContentProviderClient(uri);
            if (client != null) {
                try {
                    ParcelFileDescriptor pfd = client.openFile(uri, "r");
                    if (pfd != null) {
                        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                            return readBytes(in);
                        } finally {
                            pfd.close();
                        }
                    }
                } finally {
                    client.close();
                }
            }
        } catch (Exception e) {
            last = e;
        }

        try {
            AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd != null) {
                try (InputStream in = afd.createInputStream()) {
                    if (in != null) return readBytes(in);
                } finally {
                    afd.close();
                }
            }
        } catch (Exception e) {
            last = e;
        }

        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                    return readBytes(in);
                } finally {
                    pfd.close();
                }
            }
        } catch (Exception e) {
            last = e;
        }

        if (last != null) throw last;
        throw new Exception("acces refuzat");
    }

    private byte[] readFromMediaStoreDownloads(String displayName) throws Exception {
        if (displayName == null || displayName.isEmpty()) return null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;

        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Downloads._ID};
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=?";
        try (Cursor c = getContentResolver().query(collection, projection, selection, new String[]{displayName}, null)) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                Uri downloadUri = ContentUris.withAppendedId(collection, id);
                try (InputStream in = getContentResolver().openInputStream(downloadUri)) {
                    if (in != null) return readBytes(in);
                }
            }
        }
        return null;
    }

    private File fileFromDocumentUri(Uri uri, String displayName) {
        if (uri == null) return null;

        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null) {
                File f = new File(path);
                if (f.exists() && f.canRead()) return f;
            }
            return null;
        }

        if (!"content".equalsIgnoreCase(uri.getScheme())) return null;
        String authority = uri.getAuthority();
        if (authority == null) return null;

        if ("com.android.externalstorage.documents".equals(authority)) {
            try {
                String docId = DocumentsContract.getDocumentId(uri);
                if (docId != null && docId.startsWith("primary:")) {
                    String rel = docId.substring(8);
                    File f = resolveStorageFile(rel);
                    if (f != null) return f;
                }
            } catch (Exception ignored) {
            }
        }

        if ("com.android.providers.downloads.documents".equals(authority)) {
            try {
                String docId = DocumentsContract.getDocumentId(uri);
                if (docId != null && docId.startsWith("raw:")) {
                    File f = new File(docId.substring(4));
                    if (f.exists() && f.canRead()) return f;
                }
            } catch (Exception ignored) {
            }
        }

        String segment = uri.getLastPathSegment();
        if (segment != null && segment.contains(":")) {
            int colon = segment.indexOf(':');
            String rel = segment.substring(colon + 1);
            File f = resolveStorageFile(rel);
            if (f != null) return f;
        }

        if (displayName != null && displayName.contains(".")) {
            try {
                File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File candidate = new File(dl, displayName);
                if (candidate.exists() && candidate.canRead()) return candidate;
                File candidate2 = new File("/storage/emulated/0/Download", displayName);
                if (candidate2.exists() && candidate2.canRead()) return candidate2;
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private File resolveStorageFile(String rel) {
        if (rel == null || rel.isEmpty()) return null;
        if (rel.startsWith("/")) rel = rel.substring(1);
        File[] bases = new File[]{
            Environment.getExternalStorageDirectory(),
            new File("/storage/emulated/0")
        };
        for (File base : bases) {
            try {
                File f = new File(base, rel);
                if (f.exists() && f.canRead()) return f;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private InputStream openUriStream(Uri uri) throws Exception {
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            String path = uri.getPath();
            if (path != null) {
                File file = new File(path);
                if (file.exists() && file.canRead()) {
                    return new FileInputStream(file);
                }
            }
        }

        InputStream in = getContentResolver().openInputStream(uri);
        if (in != null) return in;

        throw new Exception("nu am acces la fișier");
    }

    private static String detectFileType(String lowerName, String mime, byte[] data) {
        if (data.length >= 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F') {
            return "pdf";
        }
        if (lowerName.endsWith(".pdf") || (mime != null && mime.contains("pdf"))) return "pdf";
        if (lowerName.endsWith(".html") || lowerName.endsWith(".htm") || lowerName.endsWith(".xhtml")
            || (mime != null && mime.contains("html"))) return "html";
        if (lowerName.endsWith(".txt") || "text/plain".equals(mime)) return "txt";

        if (data.length >= 5) {
            String head = new String(data, 0, Math.min(data.length, 64), StandardCharsets.UTF_8).trim().toLowerCase();
            if (head.startsWith("<!doctype html") || head.startsWith("<html")) return "html";
        }
        return null;
    }

    private String resolveDisplayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            String segment = uri.getLastPathSegment();
            if (segment != null) {
                int slash = segment.lastIndexOf('/');
                name = slash >= 0 ? segment.substring(slash + 1) : segment;
                int colon = name.indexOf(':');
                if (colon >= 0 && colon < name.length() - 1) name = name.substring(colon + 1);
            }
        }
        if (name == null || name.trim().isEmpty()) name = "document";
        try {
            name = Uri.decode(name);
        } catch (Exception ignored) {
        }
        return name;
    }

    private void clearIntentPayload(Intent intent) {
        intent.setData(null);
        intent.setType(null);
        intent.setAction(null);
        intent.setClipData(null);
        intent.removeExtra(Intent.EXTRA_STREAM);
    }

    void notifyJsIncomingFile() {
        if (pendingIncoming == null) return;
        if (bridge == null || bridge.getWebView() == null) {
            scheduleDeliverRetry();
            return;
        }
        bridge.getWebView().post(() -> {
            if (bridge == null || bridge.getWebView() == null) return;
            bridge.getWebView().evaluateJavascript(
                "(function(){return typeof window.consumeNativeIncomingFile==='function'})()",
                value -> {
                    if ("true".equals(value)) {
                        bridge.getWebView().evaluateJavascript(
                            "window.consumeNativeIncomingFile && window.consumeNativeIncomingFile()",
                            null
                        );
                    } else {
                        scheduleDeliverRetry();
                    }
                }
            );
        });
    }

    private void scheduleDeliverRetry() {
        if (pendingIncoming == null) return;
        deliverRetries++;
        if (deliverRetries > MAX_DELIVER_RETRIES) return;
        mainHandler.postDelayed(this::notifyJsIncomingFile, 250);
    }

    void clearPendingIncoming() {
        pendingIncoming = null;
        deliverRetries = 0;
    }

    private void toastJs(String message) {
        pendingToast = message;
        flushPendingToast();
    }

    private void flushPendingToast() {
        if (pendingToast == null) return;
        if (bridge == null || bridge.getWebView() == null) return;
        final String msg = escapeJs(pendingToast);
        pendingToast = null;
        bridge.getWebView().post(() -> {
            if (bridge != null && bridge.getWebView() != null) {
                bridge.getWebView().evaluateJavascript(
                    "window.showToast && window.showToast('" + msg + "')",
                    null
                );
            }
        });
    }

    void launchSavePicker(byte[] content, String filename, String mime) {
        if (content == null) content = new byte[0];
        if (filename == null || filename.trim().isEmpty()) filename = "document.txt";
        if (mime == null || mime.trim().isEmpty()) mime = "application/octet-stream";

        pendingSaveBytes = content;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_TITLE, filename);

        try {
            saveDocumentLauncher.launch(intent);
        } catch (Exception e) {
            pendingSaveBytes = null;
            notifySaveResult(false, "Nu s-a putut deschide dialogul de salvare");
        }
    }

    private void handleSaveResult(int resultCode, Intent data) {
        byte[] content = pendingSaveBytes;
        pendingSaveBytes = null;

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            notifySaveResult(false, "cancel");
            return;
        }

        Uri uri = data.getData();
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("Nu s-a putut scrie fișierul");
            out.write(content != null ? content : new byte[0]);
            out.flush();
            String name = uri.getLastPathSegment();
            if (name == null) name = "fișier";
            notifySaveResult(true, name);
        } catch (Exception e) {
            notifySaveResult(false, e.getMessage() != null ? e.getMessage() : "Eroare la salvare");
        }
    }

    private void notifySaveResult(boolean ok, String message) {
        if (bridge == null || bridge.getWebView() == null) return;
        final String js = "window.onNativeSaveResult && window.onNativeSaveResult("
            + ok + ",'" + escapeJs(message != null ? message : "") + "')";
        bridge.getWebView().post(() -> bridge.getWebView().evaluateJavascript(js, null));
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String escapeJs(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
    }

    static class FileOpenBridge {
        private final MainActivity activity;

        FileOpenBridge(MainActivity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public boolean hasPendingFile() {
            return activity.pendingIncoming != null;
        }

        @JavascriptInterface
        public String getPendingType() {
            return activity.pendingIncoming != null ? activity.pendingIncoming.type : "";
        }

        @JavascriptInterface
        public String getPendingName() {
            return activity.pendingIncoming != null ? activity.pendingIncoming.name : "";
        }

        @JavascriptInterface
        public int getPendingByteLength() {
            return activity.pendingIncoming != null ? activity.pendingIncoming.data.length : 0;
        }

        @JavascriptInterface
        public String getPendingBase64Chunk(int offset, int maxLen) {
            if (activity.pendingIncoming == null) return "";
            byte[] data = activity.pendingIncoming.data;
            if (offset < 0 || offset >= data.length) return "";
            int end = Math.min(offset + Math.max(maxLen, 1), data.length);
            return Base64.encodeToString(Arrays.copyOfRange(data, offset, end), Base64.NO_WRAP);
        }

        @JavascriptInterface
        public void clearPending() {
            activity.runOnUiThread(activity::clearPendingIncoming);
        }

        @JavascriptInterface
        public void onAppReady() {
            activity.runOnUiThread(activity::notifyJsIncomingFile);
        }
    }

    static class SaveFileBridge {
        private final MainActivity activity;

        SaveFileBridge(MainActivity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public void pickSave(String content, String filename, String mime) {
            byte[] bytes = content != null ? content.getBytes(StandardCharsets.UTF_8) : new byte[0];
            activity.runOnUiThread(() -> activity.launchSavePicker(bytes, filename, mime));
        }

        @JavascriptInterface
        public void pickSaveBase64(String base64, String filename, String mime) {
            byte[] bytes;
            try {
                bytes = Base64.decode(base64 != null ? base64 : "", Base64.DEFAULT);
            } catch (Exception e) {
                activity.runOnUiThread(() -> activity.notifySaveResult(false, "Date invalide"));
                return;
            }
            activity.runOnUiThread(() -> activity.launchSavePicker(bytes, filename, mime));
        }
    }
}
