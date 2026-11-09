package com.tutorhisab.app;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.graphics.Color;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;

    public class ShareBridge {
        @JavascriptInterface
        public void shareText(String title, String message) {
            runOnUiThread(() -> {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, title);
                    send.putExtra(Intent.EXTRA_TEXT, message);
                    startActivity(Intent.createChooser(send, "Share Tutor Hisab"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "শেয়ার করা যায়নি। আবার চেষ্টা করুন।", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

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
        getWindow().setStatusBarColor(Color.rgb(81, 65, 143));
        getWindow().setNavigationBarColor(Color.rgb(50, 36, 95));
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.addJavascriptInterface(new BackupBridge(), "Android");
        webView.addJavascriptInterface(new ShareBridge(), "Android");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                    fallback.addCategory(Intent.CATEGORY_OPENABLE);
                    fallback.setType("*/*");
                    try {
                        startActivityForResult(Intent.createChooser(fallback, "ব্যাকআপ ফাইল নির্বাচন করুন"), FILE_CHOOSER_REQUEST);
                    } catch (Exception ignored) {
                        filePathCallback = null;
                        callback.onReceiveValue(null);
                        return false;
                    }
                }
                return true;
            }
        });
        setContentView(webView);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2002);
        }
        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && intent != null && intent.getData() != null) {
                results = new Uri[]{intent.getData()};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript(
                "(function(){return window.handleAppBack ? window.handleAppBack() : false;})()",
                value -> {
                    if (!"true".equals(value)) {
                        MainActivity.super.onBackPressed();
                    }
                }
            );
        } else {
            super.onBackPressed();
        }
    }
}
