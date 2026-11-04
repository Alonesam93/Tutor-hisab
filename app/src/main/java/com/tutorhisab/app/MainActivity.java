package com.tutorhisab.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.graphics.Color;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;

public class MainActivity extends Activity {
    private WebView webView;

    public class BackupBridge {
        @JavascriptInterface
        public void saveBackup(String json) {
            runOnUiThread(() -> {
                try {
                    String fileName = "tutor-hisab-backup-" + System.currentTimeMillis() + ".json";
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (uri == null) throw new IOException("Could not create backup file");
                        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                            if (out == null) throw new IOException("Could not open backup file");
                            out.write(json.getBytes("UTF-8"));
                            out.flush();
                        }
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        getContentResolver().update(uri, done, null, null);
                    } else {
                        File folder = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        if (folder == null) throw new IOException("Storage unavailable");
                        if (!folder.exists()) folder.mkdirs();
                        File file = new File(folder, fileName);
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            out.write(json.getBytes("UTF-8"));
                            out.flush();
                        }
                    }
                    Toast.makeText(MainActivity.this, "ব্যাকআপ Downloads ফোল্ডারে সংরক্ষণ হয়েছে", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "ব্যাকআপ সংরক্ষণ করা যায়নি। আবার চেষ্টা করুন।", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(8, 127, 91));
        getWindow().setNavigationBarColor(Color.rgb(8, 127, 91));
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.addJavascriptInterface(new BackupBridge(), "Android");
        webView.setWebViewClient(new WebViewClient());
        setContentView(webView);
        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
