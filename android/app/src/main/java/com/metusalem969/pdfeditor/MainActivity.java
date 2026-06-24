package com.metusalem969.pdfeditor;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends BridgeActivity {

    private static final int MAX_FILE_BYTES = 25 * 1024 * 1024;
    private static final int MAX_DELIVER_RETRIES = 60;

    private boolean saveBridgeAdded = false;
    private boolean fileBridgeAdded = false;
    private byte[] pendingSaveBytes;
    private PendingIncomingFile pendingIncoming;
    private int deliverRetries = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<Intent> saveDocumentLauncher;

    private static final class PendingIncomingFile {
        final String base64;
        final String name;
        final String type;

        PendingIncomingFile(String base64, String name, String type) {
            this.base64 = base64;
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
        super.onCreate(savedInstanceState);
        readIncomingIntent(getIntent());
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
        readIncomingIntent(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        configureWebView();
        notifyJsIncomingFile();
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
        if (!saveBridgeAdded) {
            webView.addJavascriptInterface(new SaveFileBridge(this), "AndroidSave");
            saveBridgeAdded = true;
        }
        if (!fileBridgeAdded) {
            webView.addJavascriptInterface(new FileOpenBridge(this), "AndroidFileOpen");
            fileBridgeAdded = true;
        }
    }

    private boolean isFileOpenIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_SEND.equals(action)) {
            return intent.getData() != null || intent.hasExtra(Intent.EXTRA_STREAM);
        }
        return false;
    }

    private void readIncomingIntent(Intent intent) {
        if (!isFileOpenIntent(intent)) return;

        Uri uri = intent.getData();
        if (uri == null && Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
        }
        if (uri == null) return;

        try {
            final int flags = intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (flags != 0) {
                getContentResolver().takePersistableUriPermission(uri, flags);
            }
        } catch (Exception ignored) {
        }

        String name = resolveDisplayName(uri);
        final String lower = name.toLowerCase();
        final String mime = getContentResolver().getType(uri);

        final boolean isHtml = lower.endsWith(".html") || lower.endsWith(".htm")
            || lower.endsWith(".xhtml")
            || (mime != null && mime.contains("html"));
        final boolean isPdf = lower.endsWith(".pdf")
            || (mime != null && mime.contains("pdf"));
        final boolean isTxt = lower.endsWith(".txt")
            || "text/plain".equals(mime);

        if (!isHtml && !isPdf && !isTxt) return;

        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return;
            byte[] data = readBytes(in);
            if (data.length == 0 || data.length > MAX_FILE_BYTES) return;

            String type;
            if (isHtml) {
                type = "html";
            } else if (isPdf) {
                type = "pdf";
                if (!lower.endsWith(".pdf")) name += ".pdf";
            } else {
                type = "txt";
                if (!lower.endsWith(".txt")) name += ".txt";
            }

            pendingIncoming = new PendingIncomingFile(
                Base64.encodeToString(data, Base64.NO_WRAP),
                name,
                type
            );
            deliverRetries = 0;
            clearIntentPayload(intent);
            notifyJsIncomingFile();
        } catch (Exception ignored) {
        }
    }

    private String resolveDisplayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            String segment = uri.getLastPathSegment();
            if (segment != null) {
                int slash = segment.lastIndexOf('/');
                name = slash >= 0 ? segment.substring(slash + 1) : segment;
            }
        }
        if (name == null || name.trim().isEmpty()) name = "document";
        return name;
    }

    private void clearIntentPayload(Intent intent) {
        intent.setData(null);
        intent.setAction(null);
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
        return s.replace("\\", "\\\\").replace("'", "\\'");
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
        public String getPendingBase64() {
            return activity.pendingIncoming != null ? activity.pendingIncoming.base64 : "";
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
