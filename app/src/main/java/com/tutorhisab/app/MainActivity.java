package com.tutorhisab.app;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
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
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.graphics.Color;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.view.View;
import android.view.ViewGroup;
import android.util.DisplayMetrics;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.AdError;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

public class MainActivity extends Activity {
    private WebView webView;
    private TextView startupFallback;
    private boolean pageReady = false;
    private boolean pageCommitted = false;
    private AdView bannerAdView;
    private InterstitialAd interstitialAd;
    private ConsentInformation consentInformation;
    private final Handler adHandler = new Handler(Looper.getMainLooper());
    private int bannerRetryCount = 0;
    private int interstitialRetryCount = 0;
    private boolean adsInitialized = false;
    private boolean appContentVisible = false;
    private boolean bannerLoaded = false;
    private boolean bannerRequestInProgress = false;
    private boolean interstitialLoadInProgress = false;
    private boolean interstitialTimerStarted = false;
    private boolean activityResumed = false;
    private final Runnable interstitialCheckRunnable = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (!appContentVisible || !activityResumed) {
                adHandler.postDelayed(this, 30_000L);
                return;
            }
            long remaining = nextInterstitialAt - SystemClock.elapsedRealtime();
            if (remaining <= 0L) {
                if (interstitialAd != null) {
                    showInterstitialIfDue();
                    return;
                }
                loadInterstitial();
            }
            adHandler.postDelayed(this, remaining > 0L ? Math.min(30_000L, remaining) : 15_000L);
        }
    };
    private boolean consentInfoUpdated = false;
    private boolean consentUpdateSucceeded = false;
    private boolean consentFormFlowStarted = false;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private OnBackInvokedCallback backInvokedCallback;
    private boolean backDispatchPending = false;

    // Tutor Hisab production AdMob ad-unit IDs supplied by the app owner.
    private static final String BANNER_AD_UNIT_ID = "ca-app-pub-7526847355861260/7040429257";
    // Ad unit name in AdMob: TutorHisab_Interstitial
    private static final String INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-7526847355861260/9835226943";
    private static final long INTERSTITIAL_INTERVAL_MS = 8L * 60L * 1000L;
    private long nextInterstitialAt = 0L;

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

    public class AdsBridge {
        @JavascriptInterface
        public void setAppContentVisible(boolean visible) {
            runOnUiThread(() -> setAdsContentVisible(visible));
        }
        @JavascriptInterface
        public void showPrivacyOptions() {
            runOnUiThread(() -> showAdPrivacyOptions());
        }
        @JavascriptInterface
        public void onNavigation() {
            runOnUiThread(() -> { if (appContentVisible) showInterstitialIfDue(); });
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
        webView.addJavascriptInterface(new ShareBridge(), "Share");
        webView.addJavascriptInterface(new AdsBridge(), "Ads");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                android.util.Log.e("TutorHisabWebView", message.message() + " at " +
                        message.sourceId() + ":" + message.lineNumber());
                return true;
            }
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
        FrameLayout webContainer = new FrameLayout(this);
        webContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        startupFallback = new TextView(this);
        startupFallback.setText("Tutor Hisab — স্থানীয় স্ক্রিন লোড হয়নি। আবার চেষ্টা করতে এখানে চাপ দিন।");
        startupFallback.setTextColor(Color.rgb(50, 36, 95));
        startupFallback.setTextSize(14);
        startupFallback.setGravity(Gravity.CENTER);
        startupFallback.setPadding(18, 12, 18, 12);
        startupFallback.setBackgroundColor(Color.rgb(245, 241, 251));
        startupFallback.setVisibility(View.GONE);
        startupFallback.setOnClickListener(v -> {
            pageReady = false;
            startupFallback.setVisibility(View.GONE);
            webView.reload();
        });
        FrameLayout.LayoutParams fallbackParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        webContainer.addView(startupFallback, fallbackParams);
        root.addView(webContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleExternalScheme(request.getUrl());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalScheme(Uri.parse(url));
            }
            @Override
            public void onPageCommitVisible(WebView view, String url) {
                pageCommitted = true;
                startupFallback.setVisibility(View.GONE);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript("window.__tutorHisabAppReady === true", value -> {
                    pageReady = "true".equals(value);
                    // A late Firebase/network failure must never cover the locally rendered app.
                    if (pageCommitted || pageReady) startupFallback.setVisibility(View.GONE);
                });
                // Native failsafe: recover the visible login/app surface even if the inline
                // application script throws before it can dismiss the session overlay.
                view.postDelayed(() -> {
                    // If the document committed but its app script never finished, keep a
                    // native retry action visible instead of leaving a white/blocked screen.
                    if (!pageReady && startupFallback != null) {
                        startupFallback.setText("Tutor Hisab — অ্যাপ চালু হতে সমস্যা হয়েছে। আবার চেষ্টা করতে এখানে চাপ দিন।");
                        startupFallback.setVisibility(View.VISIBLE);
                    }
                    view.evaluateJavascript(
                        "(function(){try{" +
                        "if(window.__tutorHisabAppReady===true)return 'app-ready';" +
                        "var overlay=document.getElementById('sessionRestoreOverlay');if(overlay)overlay.hidden=true;" +
                        "if(window.__tutorHisabSignedIn!==true){" +
                        "var firstRun=document.getElementById('firstRunOverlay');if(firstRun)firstRun.hidden=false;" +
                        "var language=document.getElementById('firstLanguageStep');" +
                        "var authStep=document.getElementById('firstAuthStep');" +
                        "var chosen=false;try{chosen=localStorage.getItem('tutorHisabOnboardingDone')==='yes'||localStorage.getItem('tutorHisabLanguageChosen')==='yes';}catch(storageError){}" +
                        "if(language)language.hidden=chosen;if(authStep)authStep.hidden=!chosen;" +
                        "}" +
                        "var panel=document.getElementById('thNativeStartupRecovery');" +
                        "if(!panel){panel=document.createElement('div');panel.id='thNativeStartupRecovery';" +
                        "panel.style.cssText='position:fixed;left:10px;right:10px;bottom:80px;z-index:10000;background:#fff4d6;color:#624500;padding:10px;border-radius:9px;font:13px Arial,sans-serif';" +
                        "panel.textContent='অ্যাপের স্ক্রিন চালু করতে সমস্যা হয়েছে। লগইন পর্দা দেখানো হয়েছে।';document.body.appendChild(panel);}" +
                        "return 'native-recovery-shown';" +
                        "}catch(e){return 'native-recovery-error:'+String(e);}})()",
                        value -> android.util.Log.w("TutorHisabWebView", "Startup failsafe: " + value));
                }, 8000);
            }
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    pageReady = false;
                    startupFallback.setText("Tutor Hisab — অ্যাপের স্থানীয় ফাইল লোড হয়নি। আবার চেষ্টা করতে এখানে চাপ দিন।");
                    startupFallback.setVisibility(View.VISIBLE);
                }
            }
        });

        bannerAdView = new AdView(this);
        bannerAdView.setAdUnitId(BANNER_AD_UNIT_ID);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int widthDp = Math.max(1, (int) (metrics.widthPixels / metrics.density));
        bannerAdView.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, widthDp));
        bannerAdView.setVisibility(View.GONE);
        bannerAdView.setAdListener(new AdListener() {
            @Override public void onAdLoaded() {
                bannerLoaded = true;
                bannerRequestInProgress = false;
                bannerRetryCount = 0;
                bannerAdView.setVisibility(appContentVisible ? View.VISIBLE : View.GONE);
            }
            @Override public void onAdFailedToLoad(LoadAdError error) {
                bannerLoaded = false;
                bannerRequestInProgress = false;
                bannerAdView.setVisibility(View.GONE);
                if (appContentVisible) scheduleBannerRetry();
            }
        });
        root.addView(bannerAdView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        boolean restoredWebViewState = false;
        if (savedInstanceState != null) {
            // WebView.restoreState can return null when Android has discarded the old renderer.
            // Never leave a newly created WebView blank in that case.
            restoredWebViewState = webView.restoreState(savedInstanceState) != null;
        }
        if (!restoredWebViewState) {
            pageReady = false;
            pageCommitted = false;
            webView.loadUrl("file:///android_asset/index.html");
        }
        webView.postDelayed(() -> {
            // Only show a retry notice if the local HTML document itself never committed.
            // Do not replace a rendered app with a full-screen network warning.
            if (!pageCommitted && !pageReady && startupFallback != null) {
                startupFallback.setVisibility(View.VISIBLE);
            }
        }, 12000);

        // Ads are initialized only after the notification permission prompt and UMP consent flow.


        if (Build.VERSION.SDK_INT >= 33) {
            backInvokedCallback = this::dispatchBackAction;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2002);
        } else {
            initializeAdsPrivacy();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 2002) initializeAdsPrivacy();
    }

    private boolean handleExternalScheme(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        if ("mailto".equalsIgnoreCase(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_SENDTO, uri));
            } catch (Exception e) {
                Toast.makeText(this, "ইমেইল অ্যাপ পাওয়া যায়নি।", Toast.LENGTH_LONG).show();
            }
            return true;
        }
        if ("tel".equalsIgnoreCase(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_DIAL, uri));
            } catch (Exception e) {
                Toast.makeText(this, "কল করার অ্যাপ পাওয়া যায়নি।", Toast.LENGTH_LONG).show();
            }
            return true;
        }
        return false;
    }

    private void initializeAdsPrivacy() {
        consentInformation = UserMessagingPlatform.getConsentInformation(this);
        ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
        // Refresh consent status on every app launch before requesting any ads.
        consentInformation.requestConsentInfoUpdate(
                this,
                params,
                () -> {
                    consentInfoUpdated = true;
                    consentUpdateSucceeded = true;
                    continueConsentFlowIfNeeded();
                },
                requestError -> {
                    consentInfoUpdated = true;
                    consentUpdateSucceeded = false;
                    android.util.Log.w("TutorHisabAds", "Consent info update: " + requestError.getMessage());
                    // UMP may still allow requests based on a valid prior consent decision.
                    continueConsentFlowIfNeeded();
                });
    }

    private void continueConsentFlowIfNeeded() {
        if (!appContentVisible || !consentInfoUpdated || isFinishing() || isDestroyed()) return;
        if (!consentUpdateSucceeded) {
            startMobileAdsIfAllowed();
            return;
        }
        if (consentFormFlowStarted) return;
        consentFormFlowStarted = true;
        UserMessagingPlatform.loadAndShowConsentFormIfRequired(this, formError -> {
            if (formError != null) {
                android.util.Log.w("TutorHisabAds", "Consent form: " + formError.getMessage());
            }
            startMobileAdsIfAllowed();
        });
    }

    private void startMobileAdsIfAllowed() {
        if (isFinishing() || isDestroyed() || adsInitialized || consentInformation == null
                || !consentInformation.canRequestAds()) return;
        MobileAds.initialize(this, initializationStatus -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            adsInitialized = true;
            if (appContentVisible) {
                startInterstitialTimerIfNeeded();
                loadBannerAd();
                loadInterstitial();
            }
        }));
    }

    private void setAdsContentVisible(boolean visible) {
        appContentVisible = visible;
        if (bannerAdView == null) return;
        if (!visible) {
            bannerAdView.setVisibility(View.GONE);
            adHandler.removeCallbacks(interstitialCheckRunnable);
            return;
        }
        if (adsInitialized) {
            startInterstitialTimerIfNeeded();
            loadBannerAd();
            if (interstitialAd == null) loadInterstitial();
        } else {
            // Keep first-run language and sign-in screens uninterrupted; request consent
            // only after the authenticated app becomes visible.
            continueConsentFlowIfNeeded();
        }
    }

    private void startInterstitialTimerIfNeeded() {
        if (!interstitialTimerStarted) {
            interstitialTimerStarted = true;
            nextInterstitialAt = SystemClock.elapsedRealtime() + INTERSTITIAL_INTERVAL_MS;
        }
        adHandler.removeCallbacks(interstitialCheckRunnable);
        adHandler.postDelayed(interstitialCheckRunnable, 30_000L);
    }

    private void loadBannerAd() {
        if (!adsInitialized || !appContentVisible || bannerAdView == null
                || bannerLoaded || bannerRequestInProgress) return;
        bannerRequestInProgress = true;
        bannerAdView.loadAd(new AdRequest.Builder().build());
    }

    private void showAdPrivacyOptions() {
        if (consentInformation == null
                || consentInformation.getPrivacyOptionsRequirementStatus()
                != ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED) {
            Toast.makeText(this, "বর্তমানে আলাদা বিজ্ঞাপন-গোপনীয়তা অপশন প্রয়োজন নেই।", Toast.LENGTH_LONG).show();
            return;
        }
        UserMessagingPlatform.showPrivacyOptionsForm(this, formError -> {
            if (formError != null) {
                Toast.makeText(this, "বিজ্ঞাপন গোপনীয়তার সেটিংস খোলা যায়নি।", Toast.LENGTH_LONG).show();
            }
            startMobileAdsIfAllowed();
        });
    }

    private void loadInterstitial() {
        if (!adsInitialized || !appContentVisible || interstitialAd != null || interstitialLoadInProgress) return;
        interstitialLoadInProgress = true;
        InterstitialAd.load(this, INTERSTITIAL_AD_UNIT_ID, new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override public void onAdLoaded(InterstitialAd ad) {
                        interstitialLoadInProgress = false;
                        interstitialAd = ad;
                        interstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                            @Override public void onAdDismissedFullScreenContent() {
                                interstitialAd = null;
                                interstitialRetryCount = 0;
                                nextInterstitialAt = SystemClock.elapsedRealtime() + INTERSTITIAL_INTERVAL_MS;
                                loadInterstitial();
                                adHandler.removeCallbacks(interstitialCheckRunnable);
                                adHandler.postDelayed(interstitialCheckRunnable, 30_000L);
                            }
                            @Override public void onAdFailedToShowFullScreenContent(AdError error) {
                                interstitialAd = null;
                                nextInterstitialAt = SystemClock.elapsedRealtime() + INTERSTITIAL_INTERVAL_MS;
                                scheduleInterstitialRetry();
                                adHandler.removeCallbacks(interstitialCheckRunnable);
                                adHandler.postDelayed(interstitialCheckRunnable, 30_000L);
                            }
                        });
                    }
                    @Override public void onAdFailedToLoad(LoadAdError error) {
                        interstitialLoadInProgress = false;
                        interstitialAd = null;
                        if (appContentVisible) scheduleInterstitialRetry();
                    }
                });
    }

    private long adRetryDelay(int retryCount) {
        // Exponential backoff: 30s, 60s, 120s ... capped at 15 minutes.
        return Math.min(30_000L * (1L << Math.min(retryCount, 5)), 15L * 60L * 1000L);
    }

    private void scheduleBannerRetry() {
        if (isFinishing() || isDestroyed() || bannerAdView == null || !appContentVisible || !adsInitialized) return;
        long delay = adRetryDelay(bannerRetryCount++);
        adHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && bannerAdView != null) {
                loadBannerAd();
            }
        }, delay);
    }

    private void scheduleInterstitialRetry() {
        if (isFinishing() || isDestroyed() || !appContentVisible || !adsInitialized) return;
        long delay = adRetryDelay(interstitialRetryCount++);
        adHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && interstitialAd == null) loadInterstitial();
        }, delay);
    }

    private void showInterstitialIfDue() {
        if (isFinishing() || isDestroyed() || !appContentVisible || interstitialAd == null) return;
        if (!interstitialTimerStarted || nextInterstitialAt == 0L || SystemClock.elapsedRealtime() < nextInterstitialAt) return;
        InterstitialAd ready = interstitialAd;
        interstitialAd = null;
        ready.show(this);
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
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (bannerAdView != null) bannerAdView.resume();
        if (appContentVisible && interstitialTimerStarted) {
            adHandler.removeCallbacks(interstitialCheckRunnable);
            adHandler.postDelayed(interstitialCheckRunnable, 1_000L);
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        adHandler.removeCallbacks(interstitialCheckRunnable);
        if (bannerAdView != null) bannerAdView.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        adHandler.removeCallbacksAndMessages(null);
        if (bannerAdView != null) bannerAdView.destroy();
        if (Build.VERSION.SDK_INT >= 33 && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
        }
        super.onDestroy();
    }
}
