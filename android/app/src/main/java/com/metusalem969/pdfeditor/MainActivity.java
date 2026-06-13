package com.metusalem969.pdfeditor;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.getcapacitor.BridgeActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends BridgeActivity {

    private static final int MAX_FILE_BYTES = 25 * 1024 * 1024;

    private boolean saveBridgeAdded = false;
    private byte[] pendingSaveBytes;

    private ActivityResultLauncher<Intent> saveDocumentLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        saveDocumentLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> handleSaveResult(result.getResultCode(), result.getData())
        );
        super.onCreate(savedInstanceState);
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
        handleFileIntent(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        configureWebView();
        handleFileIntent(getIntent());
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

    private void handleFileIntent(Intent intent) {
        if (intent == null || bridge == null || bridge.getWebView() == null) return;

        Uri uri = intent.getData();
        if (uri == null && Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) return;

        final String mime = getContentResolver().getType(uri);
        String name = "document";
        String path = uri.getLastPathSegment();
        if (path != null) name = path;
        final String lower = name.toLowerCase();
        final boolean isHtml = (mime != null && mime.contains("html"))
            || lower.endsWith(".html") || lower.endsWith(".htm");
        final boolean isPdf = !isHtml && (
            (mime != null && mime.contains("pdf")) || lower.endsWith(".pdf") || mime == null);
        if (!isHtml && !isPdf) return;

        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return;
            byte[] data = readBytes(in);
            if (data.length == 0 || data.length > MAX_FILE_BYTES) return;

            String b64 = Base64.encodeToString(data, Base64.NO_WRAP);

            final String js;
            if (isHtml) {
                js = "window.openHtmlBase64 && window.openHtmlBase64('" + b64 + "','" + escapeJs(name) + "')";
            } else {
                if (!lower.endsWith(".pdf")) name += ".pdf";
                js = "window.openPdfBase64 && window.openPdfBase64('" + b64 + "','" + escapeJs(name) + "')";
            }

            bridge.getWebView().post(() -> bridge.getWebView().evaluateJavascript(js, null));
            intent.setData(null);
            intent.setAction(null);
        } catch (Exception ignored) {
        }
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
