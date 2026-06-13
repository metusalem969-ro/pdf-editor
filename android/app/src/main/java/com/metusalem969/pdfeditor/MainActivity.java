package com.metusalem969.pdfeditor;

import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class MainActivity extends BridgeActivity {

    private static final int MAX_FILE_BYTES = 25 * 1024 * 1024;

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
        WebSettings s = bridge.getWebView().getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
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
            String name = "document";
            String path = uri.getLastPathSegment();
            if (path != null) name = path;

            final String js;
            if (isHtml && !isPdf) {
                js = "window.openHtmlBase64 && window.openHtmlBase64('" + b64 + "','" + escapeJs(name) + "')";
            } else {
                if (!name.toLowerCase().endsWith(".pdf")) name += ".pdf";
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
}
