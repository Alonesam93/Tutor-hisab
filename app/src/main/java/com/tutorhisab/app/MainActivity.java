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
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.graphics.Color;
import android.widget.Toast;
import android.view.View;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.os.SystemClock;
import java.util.concurrent.ThreadLocalRandom;

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
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private OnBackInvokedCallback backInvokedCallback;
    private boolean backDispatchPending = false;
    private FrameLayout bannerContainer;
    private AdView bannerAdView;
    private InterstitialAd interstitialAd;
    private long nextInterstitialEligibleAt;
    private boolean adsInitialized = false;

    private static final String APP_ID = BuildConfig.DEBUG
            ? "ca-app-pub-3940256099942544~3347511713"
            : "ca-app-pub-7526847355861260~8505766194";
    private static final String BANNER_ID = BuildConfig.DEBUG
            ? "ca-app-pub-3940256099942544/9214589741"
            : "ca-app-pub-7526847355861260/7040429257";
    private static final String INTERSTITIAL_ID = BuildConfig.DEBUG
            ? "ca-app-pub-3940256099942544/1033173712"
            : "ca-app-pub-7526847355861260/9835226943";

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
        public void onSafeAdOpportunity() {
            runOnUiThread(() -> showInterstitialAtSafePoint());
        }

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
        getWindow().setStatusBarColor(Color.rgb(41, 36, 79));
        getWindow().setNavigationBarColor(Color.rgb(245, 246, 251));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.addJavascriptInterface(new BackupBridge(), "Android");
        webView.addJavascriptInterface(new ShareBridge(), "Share");
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        bannerContainer = new FrameLayout(this);
        bannerContainer.setBackgroundColor(Color.WHITE);
        bannerContainer.setVisibility(View.GONE);
        root.addView(bannerContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            backInvokedCallback = this::dispatchBackAction;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2002);
        }
        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }
        // Never show an interstitial on launch; wait 5-10 minutes and a safe Store navigation.
        scheduleNextInterstitialWindow();
        initializeAds();
    }

    private void scheduleNextInterstitialWindow() {
        long delay = ThreadLocalRandom.current().nextLong(5L * 60L * 1000L, 10L * 60L * 1000L + 1L);
        nextInterstitialEligibleAt = SystemClock.elapsedRealtime() + delay;
    }

    private void initializeAds() {
        new Thread(() -> {
            try {
                MobileAds.initialize(this,
                        new InitializationConfig.Builder(APP_ID).build(),
                        status -> runOnUiThread(() -> {
                            adsInitialized = true;
                            loadBannerAd();
                            loadInterstitialAd();
                        }));
            } catch (Exception ignored) {
                // Ads are optional; core app functionality must remain available.
            }
        }).start();
    }

    private void loadBannerAd() {
        if (!adsInitialized || isFinishing()) return;
        int widthDp = (int) (getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
        AdSize size = AdSize.getLargeAnchoredAdaptiveBannerAdSize(this, widthDp);
        bannerAdView = new AdView(this);
        bannerContainer.removeAllViews();
        bannerContainer.addView(bannerAdView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        BannerAdRequest request = new BannerAdRequest.Builder(BANNER_ID, size).build();
        bannerAdView.loadAd(request, new AdLoadCallback<BannerAd>() {
            @Override
            public void onAdLoaded(BannerAd ad) {
                runOnUiThread(() -> {
                    if (!isFinishing()) bannerContainer.setVisibility(View.VISIBLE);
                });
            }
            @Override
            public void onAdFailedToLoad(LoadAdError error) {
                runOnUiThread(() -> bannerContainer.setVisibility(View.GONE));
            }
        });
    }

    private void loadInterstitialAd() {
        if (!adsInitialized || isFinishing()) return;
        InterstitialAd.load(new AdRequest.Builder(INTERSTITIAL_ID).build(),
                new AdLoadCallback<InterstitialAd>() {
                    @Override
                    public void onAdLoaded(InterstitialAd ad) {
                        runOnUiThread(() -> interstitialAd = ad);
                    }
                    @Override
                    public void onAdFailedToLoad(LoadAdError error) {
                        runOnUiThread(() -> interstitialAd = null);
                    }
                });
    }

    private void showInterstitialAtSafePoint() {
        if (!adsInitialized || isFinishing()
                || SystemClock.elapsedRealtime() < nextInterstitialEligibleAt
                || interstitialAd == null) return;
        InterstitialAd ad = interstitialAd;
        interstitialAd = null;
        ad.setAdEventCallback(new InterstitialAdEventCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                runOnUiThread(() -> loadInterstitialAd());
            }
            @Override
            public void onAdFailedToShowFullScreenContent(FullScreenContentError error) {
                runOnUiThread(() -> loadInterstitialAd());
            }
        });
        scheduleNextInterstitialWindow();
        ad.show(this);
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

    private void dispatchBackAction() {
        if (backDispatchPending) return;
        if (webView == null) {
            finish();
            return;
        }
        backDispatchPending = true;
        webView.evaluateJavascript(
                "(function(){try{return window.handleAppBack ? window.handleAppBack() : false;}catch(e){return false;}})()",
                value -> {
                    backDispatchPending = false;
                    if (isFinishing()) return;
                    if (!"true".equals(value)) {
                        // Legal/help pages are normal WebView pages; return to the app first.
                        if (webView != null && webView.canGoBack()) webView.goBack();
                        else finish();
                    }
                }
        );
    }

    @Override
    public void onBackPressed() {
        dispatchBackAction();
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
        }
        if (bannerAdView != null) bannerAdView.destroy();
        super.onDestroy();
    }
}
