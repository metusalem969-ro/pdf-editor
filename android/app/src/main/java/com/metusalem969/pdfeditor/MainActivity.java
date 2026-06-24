package com.metusalem969.pdfeditor;

import android.content.ClipData;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.IntentCompat;

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
    private static final int MAX_READ_RETRIES = 12;

    private byte[] pendingSaveBytes;
    private PendingIncomingFile pendingIncoming;
    private int deliverRetries = 0;
    private int readRetries = 0;
    private WebView bridgedWebView = null;
    private String pendingToast = null;
    private Intent heldIntent = null;
    private Uri heldUri = null;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable readIncomingRunnable = this::tryReadIncoming;

    private ActivityResultLauncher<Intent> saveDocumentLauncher;

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
        cacheIncomingIntent(getIntent());
        super.onCreate(savedInstanceState);
        scheduleReadIncoming(80);
    }

    @Override
    public void onStart() {
        super.onStart();
        configureWebView();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingIncoming = null;
        cacheIncomingIntent(intent);
        scheduleReadIncoming(0);
    }

    @Override
    public void onResume() {
        super.onResume();
        configureWebView();
        if (pendingIncoming == null && heldUri != null) {
            scheduleReadIncoming(0);
        }
        notifyJsIncomingFile();
    }

    private void cacheIncomingIntent(Intent intent) {
        if (!isFileOpenIntent(intent)) return;
        heldIntent = intent;
        heldUri = extractUri(intent);
        readRetries = 0;
    }

    private void scheduleReadIncoming(long delayMs) {
        mainHandler.removeCallbacks(readIncomingRunnable);
        if (heldUri == null || pendingIncoming != null) return;
        mainHandler.postDelayed(readIncomingRunnable, delayMs);
    }

    private void tryReadIncoming() {
        if (pendingIncoming != null || heldUri == null) return;

        Intent intent = heldIntent != null ? heldIntent : getIntent();
        if (!isFileOpenIntent(intent)) return;

        Uri uri = heldUri;
        String name = resolveDisplayName(uri);
        final String lower = name.toLowerCase();
        String mime = null;
        try {
            mime = getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        if (mime == null && intent.getType() != null) mime = intent.getType();

        try {
            byte[] data = readUriBytes(intent, uri);
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

    private void failRead(String finalMessage) {
        readRetries++;
        if (readRetries < MAX_READ_RETRIES) {
            scheduleReadIncoming(350);
            return;
        }
        toastJs(finalMessage != null ? finalMessage : "❌ Nu s-a putut citi fișierul");
        clearHeldIntent();
    }

    private void clearHeldIntent() {
        heldIntent = null;
        heldUri = null;
        readRetries = 0;
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

    private byte[] readUriBytes(Intent intent, Uri uri) throws Exception {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Exception last = null;

        try {
            File direct = fileFromDocumentUri(uri);
            if (direct != null) {
                try (FileInputStream in = new FileInputStream(direct)) {
                    return readBytes(in);
                }
            }
        } catch (Exception e) {
            last = e;
        }

        try {
            return readBytes(openUriStream(uri));
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

    private File fileFromDocumentUri(Uri uri) {
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
                    File f = new File(Environment.getExternalStorageDirectory(), rel);
                    if (f.exists() && f.canRead()) return f;
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
            File f = new File(Environment.getExternalStorageDirectory(), rel);
            if (f.exists() && f.canRead()) return f;
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
