package com.tutorhisab.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.pm.PackageManager;
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
import android.webkit.WebResourceRequest;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.graphics.Color;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.view.View;
import android.view.ViewGroup;
import android.util.DisplayMetrics;
import com.google.android.libraries.ads.mobile.sdk.MobileAds;
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig;
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize;
import com.google.android.libraries.ads.mobile.sdk.banner.AdView;
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd;
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest;
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback;
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError;
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest;
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError;
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd;
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

// Native Android shell for Tutor Hisab; Google Mobile Ads uses official test units until production IDs are configured.
public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1002;
    private static final String FIREBASE_BASE_URL = "https://tutor-hisab.firebaseapp.com/";
    private static final String TEST_ADMOB_APP_ID = "ca-app-pub-3940256099942544~3347511713";
    private static final String TEST_BANNER_ID = "ca-app-pub-3940256099942544/9214589741";
    private FrameLayout bannerContainer;
    private AdView bannerAdView;
    private InterstitialAd interstitialAd;
    private final Handler adHandler = new Handler(Looper.getMainLooper());
    private Runnable interstitialRunnable;
    private static final String TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712";

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
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, NOTIFICATION_PERMISSION_REQUEST);
        }
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
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String path = uri.getPath() == null ? "" : uri.getPath();
                if (uri.getScheme() != null && uri.getScheme().equals("mailto")) {
                    try { startActivity(new Intent(Intent.ACTION_SENDTO, uri)); } catch (Exception ignored) {}
                    return true;
                }
                if ("tutor-hisab.firebaseapp.com".equals(uri.getHost())) {
                    if (path.endsWith("/privacy.html")) { loadAssetPage("privacy.html"); return true; }
                    if (path.endsWith("/terms.html")) { loadAssetPage("terms.html"); return true; }
                }
                return false;
            }
        });
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bannerContainer = new FrameLayout(this);
        bannerContainer.setVisibility(View.GONE);
        root.addView(bannerContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        initializeTestAds();
        if (savedInstanceState == null) {
            loadAssetPage("index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void initializeTestAds() {
        new Thread(() -> MobileAds.initialize(
                this,
                new InitializationConfig.Builder(TEST_ADMOB_APP_ID).build(),
                status -> runOnUiThread(() -> { loadBannerAd(); loadInterstitialAd(); scheduleInterstitial(7 * 60 * 1000L); }))).start();
    }

    private void loadBannerAd() {
        if (bannerContainer == null || isFinishing()) return;
        bannerAdView = new AdView(this);
        bannerContainer.addView(bannerAdView);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int widthDp = (int) (metrics.widthPixels / metrics.density);
        AdSize size = AdSize.getLargeAnchoredAdaptiveBannerAdSize(this, widthDp);
        BannerAdRequest request = new BannerAdRequest.Builder(TEST_BANNER_ID, size).build();
        bannerAdView.loadAd(request, new AdLoadCallback<BannerAd>() {
            @Override
            public void onAdLoaded(BannerAd ad) {
                bannerContainer.setVisibility(View.VISIBLE);
            }
            @Override
            public void onAdFailedToLoad(LoadAdError error) {
                bannerContainer.setVisibility(View.GONE);
            }
        });
    }

    private void loadInterstitialAd() {
        InterstitialAd.load(
                new AdRequest.Builder(TEST_INTERSTITIAL_ID).build(),
                new AdLoadCallback<InterstitialAd>() {
                    @Override
                    public void onAdLoaded(InterstitialAd ad) {
                        interstitialAd = ad;
                    }
                    @Override
                    public void onAdFailedToLoad(LoadAdError error) {
                        interstitialAd = null;
                    }
                });
    }

    private void scheduleInterstitial(long delayMs) {
        if (interstitialRunnable != null) adHandler.removeCallbacks(interstitialRunnable);
        interstitialRunnable = () -> {
            if (webView == null || isFinishing()) return;
            webView.evaluateJavascript(
                    "window.isSafeAdMoment ? window.isSafeAdMoment() : false",
                    value -> {
                        if ("true".equals(value) && interstitialAd != null) {
                            InterstitialAd ad = interstitialAd;
                            interstitialAd = null;
                            ad.setAdEventCallback(new InterstitialAdEventCallback() {
                                @Override
                                public void onAdDismissedFullScreenContent() { loadInterstitialAd(); }
                                @Override
                                public void onAdFailedToShowFullScreenContent(FullScreenContentError error) { loadInterstitialAd(); }
                                @Override
                                public void onAdShowedFullScreenContent() {}
                                @Override
                                public void onAdImpression() {}
                                @Override
                                public void onAdClicked() {}
                            });
                            ad.show(MainActivity.this);
                            scheduleInterstitial(7 * 60 * 1000L);
                        } else {
                            if (interstitialAd == null) loadInterstitialAd();
                            scheduleInterstitial(30 * 1000L);
                        }
                    });
        };
        adHandler.postDelayed(interstitialRunnable, delayMs);
    }

    private void loadAssetPage(String assetName) {
        try (InputStream input = getAssets().open(assetName);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            String html = output.toString("UTF-8");
            webView.loadDataWithBaseURL(FIREBASE_BASE_URL, html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            Toast.makeText(this, "অ্যাপের পৃষ্ঠা লোড করা যায়নি।", Toast.LENGTH_LONG).show();
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
    protected void onDestroy() {
        if (interstitialRunnable != null) adHandler.removeCallbacks(interstitialRunnable);
        if (bannerAdView != null) {
            if (bannerAdView.getParent() instanceof ViewGroup) {
                ((ViewGroup) bannerAdView.getParent()).removeView(bannerAdView);
            }
            bannerAdView.destroy();
            bannerAdView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && Build.VERSION.SDK_INT >= 19) {
            webView.evaluateJavascript("(function(){if(window.handleAndroidBack){return window.handleAndroidBack();}return false;})()", value -> {
                if (!"true".equals(value)) {
                    if (webView.canGoBack()) webView.goBack();
                    else MainActivity.super.onBackPressed();
                }
            });
            return;
        }
        if (webView != null && webView.canGoBack()) { webView.goBack(); return; }
        super.onBackPressed();
    }
}
