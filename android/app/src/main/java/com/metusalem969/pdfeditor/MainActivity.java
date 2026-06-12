package com.metusalem969.pdfeditor;

import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import com.getcapacitor.BridgeActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class MainActivity extends BridgeActivity {

    private static final int MAX_PDF_BYTES = 25 * 1024 * 1024;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePdfIntent(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        handlePdfIntent(getIntent());
    }

    private void handlePdfIntent(Intent intent) {
        if (intent == null || bridge == null || bridge.getWebView() == null) return;

        Uri uri = intent.getData();
        if (uri == null && Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) return;

        final String mime = getContentResolver().getType(uri);
        if (mime != null && !mime.contains("pdf")) return;

        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return;
            byte[] data = readBytes(in);
            if (data.length == 0 || data.length > MAX_PDF_BYTES) return;

            String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
            String name = "document.pdf";
            String path = uri.getLastPathSegment();
            if (path != null && path.toLowerCase().endsWith(".pdf")) {
                name = path;
            }

            final String js = "window.openPdfBase64 && window.openPdfBase64('" + b64 + "','" + escapeJs(name) + "')";
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
