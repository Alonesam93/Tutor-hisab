package com.tutorhisab.app;

import android.app.Activity;
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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;

import com.google.android.libraries.ads.mobile.sdk.MobileAds;
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig;
import com.google.android.libraries.ads.mobile.sdk.banner.AdView;
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize;
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd;
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest;
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback;
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError;

public class MainActivity extends Activity {
    private static final String TAG = "TutorHisabAds";
    private static final String ADMOB_APP_ID = "ca-app-pub-7526847355861260~8505766194";

    // Google-provided test banner ID. Keep test ads enabled while checking the SDK integration.
    // Replace this with the TutorHisab_Bottom_Banner ID only after testing is complete.
    private static final String BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/9214589741";

    private WebView webView;
    private AdView bannerAdView;
    private FrameLayout bannerContainer;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;

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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.addJavascriptInterface(new BackupBridge(), "Android");
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

        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bannerContainer = new FrameLayout(this);
        bannerContainer.setBackgroundColor(Color.WHITE);
        bannerContainer.setMinimumHeight(dp(50));
        root.addView(bannerContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }

        initializeAds();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void initializeAds() {
        new Thread(() -> {
            try {
                MobileAds.initialize(
                        this,
                        new InitializationConfig.Builder(ADMOB_APP_ID).build(),
                        initializationStatus -> runOnUiThread(this::loadBannerAd));
            } catch (Exception e) {
                Log.e(TAG, "AdMob initialization failed", e);
            }
        }).start();
    }

    private void loadBannerAd() {
        if (isFinishing() || isDestroyed() || bannerContainer == null) return;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int widthDp = (int) (metrics.widthPixels / metrics.density);
        AdSize adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(this, widthDp);

        bannerAdView = new AdView(this);
        bannerContainer.removeAllViews();
        bannerContainer.addView(bannerAdView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        BannerAdRequest request = new BannerAdRequest.Builder(BANNER_AD_UNIT_ID, adSize).build();
        bannerAdView.loadAd(request, new AdLoadCallback<BannerAd>() {
            @Override
            public void onAdLoaded(BannerAd ad) {
                Log.d(TAG, "Test banner ad loaded successfully.");
            }

            @Override
            public void onAdFailedToLoad(LoadAdError error) {
                Log.w(TAG, "Test banner ad failed to load: " + error);
            }
        });
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
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (bannerAdView != null) {
            bannerAdView.destroy();
            bannerAdView = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
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
