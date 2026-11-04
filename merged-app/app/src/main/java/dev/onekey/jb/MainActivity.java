package dev.onekey.jb;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import roro.stellar.Stellar;

/**
 * 一加16提权越狱 — one-tap, on-device re-jailbreak for OnePlus 16 (PYB110) /
 * CVE-2026-64560, driven entirely through Stellar (Shizuku fork, shell uid 2000).
 * 作者：星苒鸭  ·  github.com/xingranya  ·  xran.uk
 *
 * No PC needed after the first Stellar start: the app stages the exploit
 * binaries, captures the current boot's ftrace data, recovers the KASLR slide,
 * patches the KernelSU-Next LKM, runs the whole UAF chain and finally installs
 * the KernelSU userland — all by tapping one button.
 */
public class MainActivity extends Activity {

    private static final String WORK = ShizukuShell.WORK;
    private static final String STELLAR_PKG = "roro.stellar.manager";
    private static final String MANAGER_PKG = "com.rifsxd.ksunext";
    private static final String MANAGER_EXPECTED_VERSION = "3.4.2";
    private static final String SUKISU_PKG = "com.sukisu.ultra";
    /** 内置 SukiSU LKM 来自 v4.3.0（UAPI 5）；管理器必须同版本，否则会显示"未安装" */
    private static final String SUKISU_EXPECTED_VERSION = "v4.3.0";
    private static final String SUKISU_CERT_SHA256 =
            "947ae944f3de4ed4c21a7e4f7953ecf351bfa2b36239da37a34111ad29993eef";
    private static final String PREF_NAME = "onekey";
    private static final String PREF_MANAGER = "manager";
    private static final String PREF_NOTICE = "free_notice_v1";
    private static final String QQ_GROUP = "1121682811";
    private static final String KERNEL_RELEASE = "6.18.21-android17-5-gf957accaf563-ab15926176-4k";
    private static final String DEVICE_MODEL = "PYB110";
    private static final String MANAGER_CERT_SHA256 =
            "79e590113c4c4c0c222978e413a5faa801666957b1212a328e46c00c69821bf7";
    private static final String AUTHOR = "星苒鸭";
    private static final String GITHUB_URL = "https://github.com/xingranya";
    private static final String SITE_URL = "https://xran.uk";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ScrollView logScrollView;
    private TextView logView;
    private Button btnRun, btnPrep, btnOpen, btnClear, btnDiagnose;
    private TextView statusView;
    private TextView statusDot, statusSub;
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView stageView;
    private TextView elapsedView;
    private TextView deviceInfoView;
    private boolean moduleReady;
    private boolean refreshingDevice;
    private AlertDialog noticeDialog;
    private int progressPct = 0;
    private int stepIndex = 0;
    private long runStart = 0;
    private boolean ticking = false;
    private LinearLayout[] navItems;
    private FrameLayout[] navIconWraps;
    private TextView[] navIcons;
    private TextView[] navLabels;
    private View tabHomeView, tabLogView, tabAboutView;
    /** Process-wide guard: prevents two activity instances from running two pipelines. */
    private static volatile boolean running = false;
    private PowerManager.WakeLock wakeLock;
    /** "ksu" = KernelSU-Next, "sukisu" = SukiSU Ultra. Chosen before each run. */
    private String selectedManager = "ksu";
    private LinearLayout mgrKsuRow, mgrSukiRow;
    private TextView mgrKsuCheck, mgrSukiCheck, mgrKsuStatus, mgrSukiStatus;

    private final Stellar.OnBinderReceivedListener binderListener = this::refreshShizuku;
    private final Stellar.OnBinderDeadListener deadListener = this::refreshShizuku;
    private final Stellar.OnRequestPermissionResultListener permListener =
            (requestCode, allowed, onetime) -> {
                log(allowed ? "Stellar 权限已授予（" + (onetime ? "一次性" : "永久") + "）"
                        : "Stellar 权限被拒绝（请在 Stellar 管理器中允许本应用）");
                refreshShizuku();
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        applySystemColors(dark);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OneKeyJB:run");
        wakeLock.setReferenceCounted(false);
        selectedManager = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getString(PREF_MANAGER, "ksu");
        buildUi();
        Stellar.INSTANCE.addBinderReceivedListenerSticky(binderListener, null);
        Stellar.INSTANCE.addBinderDeadListener(deadListener, null);
        Stellar.INSTANCE.addRequestPermissionResultListener(permListener, null);
        refreshShizuku();
        log("先检查准备状态，再执行或修复 root；完成后为目标应用授权。");
        if (!getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(PREF_NOTICE, false)) {
            ui.post(this::showFreeNotice);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShizuku();
        refreshDeviceInfo();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 导出的启动入口只打开界面，高权限任务必须由用户点击。
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        if (running) moveTaskToBack(true);
        else if (tabHomeView.getVisibility() != View.VISIBLE) switchTab(0);
        else super.onBackPressed();
    }

    private void showFreeNotice() {
        if (isFinishing() || isDestroyed() || (noticeDialog != null && noticeDialog.isShowing())) return;
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(8));
        scroll.addView(content);
        TextView statement = new TextView(this);
        statement.setText("倒卖死全家");
        statement.setTextSize(18);
        statement.setTypeface(Typeface.DEFAULT_BOLD);
        statement.setTextColor(MD_ERROR);
        content.addView(statement);
        TextView details = new TextView(this);
        details.setText("禁止倒卖，群内免费。\nQQ群：" + QQ_GROUP + "\n\n下面是作者的赞赏码。赞赏完全自愿，不影响任何功能。");
        details.setTextSize(14);
        details.setTextColor(MD_ON_SURFACE);
        details.setLineSpacing(dp(4), 1f);
        content.addView(details, lp(MATCH, WRAP, dp(8), 0));
        ImageView code = new ImageView(this);
        code.setContentDescription("作者赞赏码，可使用保存赞赏码按钮保存原图");
        code.setScaleType(ImageView.ScaleType.FIT_CENTER);
        code.setBackgroundColor(Color.WHITE);
        try (InputStream in = getAssets().open("appreciation.png")) {
            code.setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (Exception e) { details.append("\n赞赏码读取失败，请通过QQ群联系作者。"); }
        content.addView(code, lp(MATCH, dp(270), dp(12), 0));
        noticeDialog = new AlertDialog.Builder(this)
                .setTitle("群内免费 · 禁止倒卖")
                .setView(scroll)
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制群号", null)
                .setNegativeButton("保存赞赏码", null)
                .create();
        noticeDialog.setOnDismissListener(d -> {
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(PREF_NOTICE, true).apply();
            noticeDialog = null;
        });
        noticeDialog.show();
        for (int button : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE}) {
            noticeDialog.getButton(button).setTextColor(MD_PRIMARY);
        }
        scroll.getLayoutParams().height = Math.min(dp(460), (int) (getResources().getDisplayMetrics().heightPixels * 0.64f));
        scroll.requestLayout();
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int available = scroll.getHeight() - statement.getHeight() - details.getHeight() - dp(36);
                code.getLayoutParams().height = Math.max(dp(180), Math.min(dp(270), available));
                code.requestLayout();
            }
        });
        noticeDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QQ群", QQ_GROUP));
            toast("已复制QQ群号 " + QQ_GROUP);
        });
        noticeDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> saveAppreciation((Button) v));
    }

    private void saveAppreciation(Button button) {
        button.setEnabled(false);
        new Thread(() -> {
            Uri image = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "星苒鸭-赞赏码-" + System.currentTimeMillis() + ".png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OneKeyJB");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                image = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (image == null) throw new java.io.IOException("无法创建图片");
                try (InputStream in = getAssets().open("appreciation.png");
                     OutputStream out = getContentResolver().openOutputStream(image)) {
                    if (out == null) throw new java.io.IOException("无法写入相册");
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (getContentResolver().update(image, values, null, null) != 1) throw new java.io.IOException("相册保存未完成");
                toast("赞赏码原图已保存到相册");
            } catch (Exception e) {
                if (image != null) {
                    try { getContentResolver().delete(image, null, null); } catch (Exception ignored) { }
                }
                toast("保存失败：" + e.getMessage());
            } finally {
                ui.post(() -> button.setEnabled(true));
            }
        }, "save-appreciation").start();
    }


    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    // Material 3 baseline light color scheme
    private int MD_BG = Color.rgb(254, 247, 255);
    private int MD_SURFACE = Color.rgb(247, 242, 250);
    private int MD_SURFACE_2 = Color.rgb(243, 237, 247);
    private int MD_SURFACE_HIGH = Color.rgb(236, 230, 240);
    private int MD_ON_SURFACE = Color.rgb(29, 27, 32);
    private int MD_ON_VARIANT = Color.rgb(73, 69, 79);
    private int MD_PRIMARY = Color.rgb(103, 80, 164);
    private int MD_ON_PRIMARY = Color.WHITE;
    private int MD_PRIMARY_CONTAINER = Color.rgb(234, 221, 255);
    private int MD_ON_PRIMARY_CONTAINER = Color.rgb(33, 0, 93);
    private int MD_OUTLINE = Color.rgb(121, 116, 126);
    private int MD_OUTLINE_VARIANT = Color.rgb(202, 196, 208);
    private int MD_ERROR = Color.rgb(179, 38, 30);

    private void applySystemColors(boolean dark) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            MD_BG = getColor(dark ? android.R.color.system_surface_dark : android.R.color.system_surface_light);
            MD_SURFACE = getColor(dark ? android.R.color.system_surface_container_low_dark : android.R.color.system_surface_container_low_light);
            MD_SURFACE_2 = getColor(dark ? android.R.color.system_surface_container_dark : android.R.color.system_surface_container_light);
            MD_SURFACE_HIGH = getColor(dark ? android.R.color.system_surface_container_high_dark : android.R.color.system_surface_container_high_light);
            MD_ON_SURFACE = getColor(dark ? android.R.color.system_on_surface_dark : android.R.color.system_on_surface_light);
            MD_ON_VARIANT = getColor(dark ? android.R.color.system_on_surface_variant_dark : android.R.color.system_on_surface_variant_light);
            MD_PRIMARY = getColor(dark ? android.R.color.system_primary_dark : android.R.color.system_primary_light);
            MD_ON_PRIMARY = getColor(dark ? android.R.color.system_on_primary_dark : android.R.color.system_on_primary_light);
            MD_PRIMARY_CONTAINER = getColor(dark ? android.R.color.system_primary_container_dark : android.R.color.system_primary_container_light);
            MD_ON_PRIMARY_CONTAINER = getColor(dark ? android.R.color.system_on_primary_container_dark : android.R.color.system_on_primary_container_light);
            MD_OUTLINE = getColor(dark ? android.R.color.system_outline_dark : android.R.color.system_outline_light);
            MD_OUTLINE_VARIANT = getColor(dark ? android.R.color.system_outline_variant_dark : android.R.color.system_outline_variant_light);
            MD_ERROR = getColor(dark ? android.R.color.system_error_dark : android.R.color.system_error_light);
        } else if (dark) {
            MD_BG = 0xff141218; MD_SURFACE = 0xff1d1b20; MD_SURFACE_2 = 0xff211f26;
            MD_SURFACE_HIGH = 0xff2b2930; MD_ON_SURFACE = 0xffe6e0e9; MD_ON_VARIANT = 0xffcac4d0;
            MD_PRIMARY = 0xffd0bcff; MD_ON_PRIMARY = 0xff381e72;
            MD_PRIMARY_CONTAINER = 0xff4f378b; MD_ON_PRIMARY_CONTAINER = 0xffeaddff;
            MD_OUTLINE = 0xff938f99; MD_OUTLINE_VARIANT = 0xff49454f; MD_ERROR = 0xfff2b8b5;
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(MD_BG);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        tabHomeView = buildHomeView();
        tabLogView = buildLogView();
        tabAboutView = buildAboutView();
        content.addView(tabHomeView, new FrameLayout.LayoutParams(MATCH, MATCH));
        content.addView(tabLogView, new FrameLayout.LayoutParams(MATCH, MATCH));
        content.addView(tabAboutView, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout bottom = buildBottomBar();
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(MATCH, dp(62));
        bLp.gravity = Gravity.BOTTOM;
        root.addView(bottom, bLp);

        final int barSpace = dp(62);
        tabHomeView.setPadding(0, 0, 0, barSpace);
        tabLogView.setPadding(0, 0, 0, barSpace);
        tabAboutView.setPadding(0, 0, 0, barSpace);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset = 0, bottomInset = 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                topInset = bars.top;
                bottomInset = bars.bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, topInset, 0, bottomInset);
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
        switchTab(0);
        refreshDeviceInfo();
    }

    // ---------------------------------------------------------------- home
    private View buildHomeView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(18), dp(20), dp(20));
        scroll.addView(col, new ScrollView.LayoutParams(MATCH, WRAP));

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(avatarView(52), lp(dp(52), dp(52), 0, dp(12)));
        LinearLayout hcol = new LinearLayout(this);
        hcol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("一加16提权越狱");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(MD_ON_SURFACE);
        TextView sub = new TextView(this);
        sub.setText("v2.3.3  ·  OnePlus 16 / PYB110");
        sub.setTextSize(11);
        sub.setTextColor(MD_ON_VARIANT);
        sub.setPadding(0, dp(3), 0, 0);
        hcol.addView(title);
        hcol.addView(sub);
        head.addView(hcol, new LinearLayout.LayoutParams(0, WRAP, 1f));
        Button infoBtn = flatButton("关于");
        infoBtn.setTextSize(13);
        infoBtn.setOnClickListener(v -> switchTab(2));
        head.addView(infoBtn, lp(dp(56), dp(48), 0, 0));
        col.addView(head);

        // status card
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(16), dp(13), dp(16), dp(13));
        statusCard.setBackground(surface(MD_SURFACE_2, 12));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        scLp.topMargin = dp(16);
        col.addView(statusCard, scLp);

        statusDot = new TextView(this);
        statusDot.setText("●");
        statusDot.setTextSize(14);
        statusDot.setTextColor(Color.rgb(255, 179, 0));
        statusDot.setPadding(0, 0, dp(10), 0);
        statusCard.addView(statusDot);

        LinearLayout statusCol = new LinearLayout(this);
        statusCol.setOrientation(LinearLayout.VERTICAL);
        statusCol.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1f));
        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setTextColor(MD_ON_SURFACE);
        statusSub = new TextView(this);
        statusSub.setTextSize(11);
        statusSub.setTextColor(MD_ON_VARIANT);
        statusSub.setPadding(0, dp(2), 0, 0);
        statusCol.addView(statusView);
        statusCol.addView(statusSub);
        statusCard.addView(statusCol);
        statusCard.setOnClickListener(v -> connectStellar());

        // device info card
        LinearLayout devCard = new LinearLayout(this);
        devCard.setOrientation(LinearLayout.VERTICAL);
        devCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable devBg = surface(MD_BG, 12);
        devBg.setStroke(dp(1), MD_OUTLINE_VARIANT);
        devCard.setBackground(devBg);
        LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        dcLp.topMargin = dp(10);
        col.addView(devCard, dcLp);
        deviceInfoView = new TextView(this);
        deviceInfoView.setTextSize(11);
        deviceInfoView.setTextColor(MD_ON_VARIANT);
        deviceInfoView.setLineSpacing(dp(3), 1f);
        deviceInfoView.setText("设备  " + android.os.Build.MODEL + "\n内核  读取中…\n管理器  读取中…");
        devCard.addView(deviceInfoView);

        // manager selection
        TextView mgrTitle = sectionLabel("1  选择配套管理器");
        LinearLayout.LayoutParams mtLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        mtLp.topMargin = dp(20);
        col.addView(mgrTitle, mtLp);

        LinearLayout mgrCard = new LinearLayout(this);
        mgrCard.setOrientation(LinearLayout.VERTICAL);
        mgrCard.setPadding(dp(6), dp(6), dp(6), dp(6));
        mgrCard.setBackground(surface(MD_SURFACE_2, 16));
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        mcLp.topMargin = dp(8);
        col.addView(mgrCard, mcLp);
        mgrKsuRow = managerOption("KernelSU-Next", "需官方 3.4.2 管理器", "ksu");
        mgrSukiRow = managerOption("SukiSU Ultra", "实验性 · 需官方 v4.3.0 管理器", "sukisu");
        mgrCard.addView(mgrKsuRow);
        View mgrDivider = new View(this);
        mgrDivider.setBackgroundColor(MD_OUTLINE_VARIANT);
        LinearLayout.LayoutParams dvLp = new LinearLayout.LayoutParams(MATCH, dp(1));
        dvLp.leftMargin = dp(14);
        dvLp.rightMargin = dp(14);
        mgrCard.addView(mgrDivider, dvLp);
        mgrCard.addView(mgrSukiRow);

        // primary action
        col.addView(sectionLabel("2  检查准备，再执行"), lp(MATCH, WRAP, dp(16), 0));
        btnRun = filledButton("开始越狱");
        LinearLayout.LayoutParams runLp = new LinearLayout.LayoutParams(MATCH, dp(48));
        runLp.topMargin = dp(16);
        col.addView(btnRun, runLp);
        updateManagerUi();

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        r2Lp.topMargin = dp(10);
        col.addView(row2, r2Lp);
        btnPrep = outlinedButton("检查准备（只读）");
        btnOpen = outlinedButton("打开管理器");
        LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        w1.rightMargin = dp(6);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        w2.leftMargin = dp(6);
        row2.addView(btnPrep, w1);
        row2.addView(btnOpen, w2);
        col.addView(sectionLabel("3  授权应用，验证 root"), lp(MATCH, WRAP, dp(16), 0));
        btnDiagnose = outlinedButton("验证 root（先授权本应用）");
        col.addView(btnDiagnose, lp(MATCH, dp(48), dp(8), 0));
        btnDiagnose.setOnClickListener(v -> start(this::diagnoseRoot));

        // progress card
        LinearLayout progCard = new LinearLayout(this);
        progCard.setOrientation(LinearLayout.VERTICAL);
        progCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        progCard.setBackground(surface(MD_SURFACE_2, 12));
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        pcLp.topMargin = dp(16);
        col.addView(progCard, pcLp);

        LinearLayout progHead = new LinearLayout(this);
        progHead.setOrientation(LinearLayout.HORIZONTAL);
        progHead.setGravity(Gravity.CENTER_VERTICAL);
        stageView = new TextView(this);
        stageView.setText("就绪");
        stageView.setTextSize(13);
        stageView.setTypeface(Typeface.DEFAULT_BOLD);
        stageView.setTextColor(MD_ON_SURFACE);
        stageView.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1f));
        elapsedView = new TextView(this);
        elapsedView.setTextSize(11);
        elapsedView.setTextColor(MD_ON_VARIANT);
        progHead.addView(stageView);
        progHead.addView(elapsedView);
        progCard.addView(progHead);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        progressBar.setProgressTintList(ColorStateList.valueOf(MD_PRIMARY));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(MD_SURFACE_HIGH));
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(MATCH, dp(4));
        pbLp.topMargin = dp(12);
        progCard.addView(progressBar, pbLp);

        progressText = new TextView(this);
        progressText.setText("等待开始");
        progressText.setTextSize(11);
        progressText.setTextColor(MD_ON_VARIANT);
        progressText.setPadding(0, dp(1), 0, 0);
        progCard.addView(progressText);

        // tips card
        LinearLayout tips = new LinearLayout(this);
        tips.setOrientation(LinearLayout.VERTICAL);
        tips.setPadding(dp(16), dp(14), dp(16), dp(14));
        tips.setBackground(surface(MD_SURFACE, 12));
        LinearLayout.LayoutParams tpLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        tpLp.topMargin = dp(16);
        col.addView(tips, tpLp);
        TextView tipsTitle = new TextView(this);
        tipsTitle.setText("使用提示");
        tipsTitle.setTextSize(12);
        tipsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tipsTitle.setTextColor(MD_ON_SURFACE);
        tips.addView(tipsTitle);
        TextView tipsBody = new TextView(this);
        tipsBody.setText("· 管理器需与内置模块配套：KernelSU-Next 3.4.2 或 SukiSU Ultra v4.3.0\n· 切换两种内核前需重启手机；应用的 root 权限需在对应管理器中单独开启\n· 授权后仍不可用时，先彻底关闭目标应用再打开，或运行「root 诊断」\n· 临时 root，重启失效；内核竞态有概率导致重启");
        tipsBody.setTextSize(11);
        tipsBody.setTextColor(MD_ON_VARIANT);
        tipsBody.setLineSpacing(dp(3), 1f);
        tipsBody.setPadding(0, dp(6), 0, 0);
        tips.addView(tipsBody);

        btnRun.setOnClickListener(v -> {
            if (!ShizukuShell.available() || !ShizukuShell.hasPermission()) connectStellar();
            else start(() -> runPipeline(false));
        });
        btnPrep.setOnClickListener(v -> start(() -> runPipeline(true)));
        btnOpen.setOnClickListener(v -> openManager());
        return scroll;
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setTextColor(MD_ON_VARIANT);
        return t;
    }

    private LinearLayout managerOption(String title, String desc, String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView check = new TextView(this);
        check.setText("○");
        check.setTextSize(18);
        check.setTextColor(MD_PRIMARY);
        check.setPadding(0, 0, dp(12), 0);
        row.addView(check);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(MD_ON_SURFACE);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(11);
        d.setTextColor(MD_ON_VARIANT);
        d.setPadding(0, dp(2), 0, 0);
        TextView status = new TextView(this);
        status.setText("检测中…");
        status.setTextSize(10);
        status.setTextColor(MD_PRIMARY);
        status.setPadding(0, dp(3), 0, 0);
        col.addView(t);
        col.addView(d);
        col.addView(status);
        row.addView(col);
        row.setOnClickListener(v -> selectManager(key));
        if ("ksu".equals(key)) {
            mgrKsuCheck = check;
            mgrKsuStatus = status;
        } else {
            mgrSukiCheck = check;
            mgrSukiStatus = status;
        }
        return row;
    }

    private void selectManager(String key) {
        if (running) {
            toast("正在运行，结束后再切换");
            return;
        }
        if (key.equals(selectedManager)) return;
        selectedManager = key;
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(PREF_MANAGER, key).apply();
        updateManagerUi();
        refreshDeviceInfo();
        log("已选择 " + managerLabel() + "；若本次启动已加载另一套内核，需要重启手机后切换。");
        toast("已选择 " + managerLabel());
    }

    private void updateManagerUi() {
        if (mgrKsuRow == null) return;
        boolean ksu = "ksu".equals(selectedManager);
        styleManagerRow(mgrKsuRow, ksu);
        styleManagerRow(mgrSukiRow, !ksu);
        if (mgrKsuCheck != null) mgrKsuCheck.setText(ksu ? "◉" : "○");
        if (mgrSukiCheck != null) mgrSukiCheck.setText(ksu ? "○" : "◉");
        if (mgrKsuStatus != null) {
            mgrKsuStatus.setText(pkgStatus(MANAGER_PKG));
            mgrKsuStatus.setTextColor(ksu ? MD_ON_PRIMARY_CONTAINER : MD_PRIMARY);
        }
        if (mgrSukiStatus != null) {
            mgrSukiStatus.setText(pkgStatus(SUKISU_PKG));
            mgrSukiStatus.setTextColor(ksu ? MD_PRIMARY : MD_ON_PRIMARY_CONTAINER);
        }
        updatePrimaryAction();
        boolean canSelect = !running;
        for (LinearLayout row : new LinearLayout[]{mgrKsuRow, mgrSukiRow}) {
            if (row == null) continue;
            row.setEnabled(canSelect);
            row.setAlpha(canSelect ? 1f : 0.5f);
        }
    }

    private void styleManagerRow(LinearLayout row, boolean selected) {
        if (row == null) return;
        GradientDrawable d = surface(selected ? MD_PRIMARY_CONTAINER : Color.TRANSPARENT, 12);
        if (selected) d.setStroke(dp(1), MD_PRIMARY);
        row.setBackground(d);
        LinearLayout labels = (LinearLayout) row.getChildAt(1);
        ((TextView) labels.getChildAt(0)).setTextColor(selected ? MD_ON_PRIMARY_CONTAINER : MD_ON_SURFACE);
        ((TextView) labels.getChildAt(1)).setTextColor(selected ? MD_ON_PRIMARY_CONTAINER : MD_ON_VARIANT);
    }

    private String pkgStatus(String pkg) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            String expected = SUKISU_PKG.equals(pkg) ? SUKISU_EXPECTED_VERSION : MANAGER_EXPECTED_VERSION;
            if (!sameVersion(expected, pi.versionName)) {
                return "版本不匹配（需 " + expected + "，当前 " + pi.versionName + "）";
            }
            return "已安装  " + pi.versionName;
        } catch (Throwable t) {
            return "未安装";
        }
    }

    // ---------------------------------------------------------------- log
    private View buildLogView() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(18), dp(20), dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText("运行日志");
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(MD_ON_SURFACE);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(t);
        Button share = flatButton("分享 TXT");
        share.setTextSize(12);
        share.setOnClickListener(v -> shareLog(share));
        head.addView(share);
        Button copy = flatButton("复制");
        copy.setTextSize(12);
        copy.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("onekey-log", logView.getText()));
                toast("日志已复制");
            } catch (Throwable ignored) { }
        });
        head.addView(copy);
        btnClear = flatButton("清空");
        btnClear.setTextSize(12);
        btnClear.setOnClickListener(v -> logView.setText(""));
        head.addView(btnClear);
        col.addView(head);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(surface(MD_SURFACE, 12));
        scroll.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        sLp.topMargin = dp(10);
        col.addView(scroll, sLp);
        logScrollView = scroll;
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(MD_ON_SURFACE);
        logView.setLineSpacing(dp(2), 1f);
        logView.setTextIsSelectable(true);
        scroll.addView(logView, new ScrollView.LayoutParams(MATCH, WRAP));
        return col;
    }

    private void shareLog(Button button) {
        String text = logView.getText().toString();
        if (text.trim().isEmpty()) { toast("暂无可分享的日志"); return; }
        button.setEnabled(false);
        new Thread(() -> {
            Uri file = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, "OneKeyJB-log-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date()) + ".txt");
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/OneKeyJB");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                file = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (file == null) throw new java.io.IOException("无法创建日志文件");
                try (OutputStream out = getContentResolver().openOutputStream(file)) {
                    if (out == null) throw new java.io.IOException("无法写入日志文件");
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                if (getContentResolver().update(file, values, null, null) != 1)
                    throw new java.io.IOException("日志保存未完成");
                Uri saved = file;
                ui.post(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_STREAM, saved)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    send.setClipData(android.content.ClipData.newRawUri("运行日志", saved));
                    try { startActivity(Intent.createChooser(send, "分享日志 TXT")); }
                    catch (android.content.ActivityNotFoundException e) { toast("日志已保存至 Download/OneKeyJB"); }
                });
            } catch (Exception e) {
                if (file != null) {
                    try { getContentResolver().delete(file, null, null); } catch (Exception ignored) { }
                }
                toast("日志导出失败：" + e.getMessage());
            } finally { ui.post(() -> button.setEnabled(true)); }
        }, "share-log").start();
    }

    // ---------------------------------------------------------------- about
    private View buildAboutView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(28), dp(24), dp(24));
        scroll.addView(col, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout avatarWrap = new LinearLayout(this);
        avatarWrap.setGravity(Gravity.CENTER);
        avatarWrap.addView(avatarView(88));
        col.addView(avatarWrap);

        TextView name = new TextView(this);
        name.setText("星苒鸭");
        name.setTextSize(20);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(MD_ON_SURFACE);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(12), 0, dp(2));
        col.addView(name);

        TextView tagline = new TextView(this);
        tagline.setText("一加16提权越狱  ·  v2.3.3");
        tagline.setTextSize(12);
        tagline.setTextColor(MD_ON_VARIANT);
        tagline.setGravity(Gravity.CENTER);
        col.addView(tagline);

        TextView community = new TextView(this);
        community.setText("群内免费 · 禁止倒卖\nQQ群：" + QQ_GROUP + "\n赞赏自愿，不影响任何功能");
        community.setTextSize(14);
        community.setTextColor(MD_ON_SURFACE);
        community.setGravity(Gravity.CENTER);
        community.setLineSpacing(dp(5), 1f);
        col.addView(community, lp(MATCH, WRAP, dp(20), 0));
        Button support = outlinedButton("免费声明与赞赏码");
        support.setOnClickListener(v -> showFreeNotice());
        col.addView(support, lp(MATCH, dp(48), dp(12), 0));

        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.VERTICAL);
        links.setPadding(dp(16), dp(8), dp(16), dp(8));
        links.setBackground(surface(MD_SURFACE_2, 16));
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lLp.topMargin = dp(20);
        col.addView(links, lLp);
        links.addView(linkRow("GitHub", "github.com/xingranya", GITHUB_URL));
        links.addView(linkRow("个人主页", "xran.uk", SITE_URL));
        links.addView(linkRow("KernelSU-Next", "github.com/KernelSU-Next", "https://github.com/KernelSU-Next/KernelSU-Next"));
        links.addView(linkRow("SukiSU Ultra", "github.com/SukiSU-Ultra", "https://github.com/SukiSU-Ultra/SukiSU-Ultra"));
        links.addView(linkRow("Stellar", "github.com/roro2239/Stellar", "https://github.com/roro2239/Stellar"));
        links.addView(linkRow("使用教程", "README / docs", GITHUB_URL + "/OnePlus16-Jailbreak"));

        LinearLayout noteCard = new LinearLayout(this);
        noteCard.setOrientation(LinearLayout.VERTICAL);
        noteCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        noteCard.setBackground(surface(MD_SURFACE, 14));
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        nLp.topMargin = dp(16);
        col.addView(noteCard, nLp);
        TextView note = new TextView(this);
        note.setText("免责声明\n本工具仅供本人设备的安全研究与漏洞验证使用。临时 root 重启失效，不写任何分区；内核竞态有小概率导致重启，属正常现象。请勿用于未经授权的设备。");
        note.setTextSize(11);
        note.setTextColor(MD_ON_VARIANT);
        note.setLineSpacing(dp(3), 1f);
        noteCard.addView(note);
        return scroll;
    }

    // ---------------------------------------------------------------- bottom bar
    private LinearLayout buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(MD_SURFACE_2);
        bar.setPadding(0, 0, 0, dp(6));

        navItems = new LinearLayout[3];
        navIconWraps = new FrameLayout[3];
        navIcons = new TextView[3];
        navLabels = new TextView[3];
        String[] icons = new String[]{"⌂", "≡", "ⓘ"};
        String[] labels = new String[]{"首页", "日志", "关于"};
        for (int i = 0; i < 3; i++) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(4), 0, dp(4));

            FrameLayout wrap = new FrameLayout(this);
            wrap.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(32)));
            TextView icon = new TextView(this);
            icon.setText(icons[i]);
            icon.setTextSize(18);
            icon.setGravity(Gravity.CENTER);
            wrap.addView(icon, new FrameLayout.LayoutParams(MATCH, dp(32)));
            item.addView(wrap);

            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextSize(12);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(2), 0, 0);
            item.addView(label);

            bar.addView(item, new LinearLayout.LayoutParams(0, MATCH, 1f));
            final int idx = i;
            item.setOnClickListener(v -> switchTab(idx));
            navItems[i] = item;
            navIconWraps[i] = wrap;
            navIcons[i] = icon;
            navLabels[i] = label;
        }
        return bar;
    }

    private void switchTab(int idx) {
        if (tabHomeView == null || navItems == null) return;
        tabHomeView.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        tabLogView.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        tabAboutView.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < navItems.length; i++) {
            boolean selected = i == idx;
            GradientDrawable d = new GradientDrawable();
            d.setColor(selected ? MD_PRIMARY_CONTAINER : Color.TRANSPARENT);
            d.setCornerRadius(dpF(16));
            navIconWraps[i].setBackground(d);
            navIcons[i].setTextColor(selected ? MD_ON_PRIMARY_CONTAINER : MD_ON_VARIANT);
            navLabels[i].setTextColor(selected ? MD_ON_PRIMARY_CONTAINER : MD_ON_VARIANT);
            navLabels[i].setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }
    // ---------------------------------------------------------------- widgets
    private LinearLayout card(int color, int radiusDp) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(surface(color, radiusDp));
        return l;
    }

    private Button filledButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(MD_ON_PRIMARY);
        b.setBackground(surface(MD_PRIMARY, 24));
        return b;
    }

    private Button flatButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(MD_PRIMARY);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private LinearLayout linkRow(String name, String caption, String url) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(11), 0, dp(11));
        TextView t = new TextView(this);
        t.setText(name + "   " + caption);
        t.setTextSize(13);
        t.setTextColor(MD_ON_SURFACE);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(t);
        TextView arrow = new TextView(this);
        arrow.setText("↗");
        arrow.setTextSize(16);
        arrow.setTextColor(MD_PRIMARY);
        row.addView(arrow);
        row.setOnClickListener(v -> openUrl(url));
        return row;
    }

    private ImageView avatarView(int sizeDp) {
        ImageView avatar = new ImageView(this);
        try (InputStream in = getAssets().open("avatar.png")) {
            Bitmap srcBm = BitmapFactory.decodeStream(in);
            if (srcBm != null) {
                int s = dp(sizeDp);
                Bitmap scaled = Bitmap.createScaledBitmap(srcBm, s, s, true);
                Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(out);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                canvas.drawCircle(s / 2f, s / 2f, s / 2f, paint);
                avatar.setImageBitmap(out);
            }
        } catch (Throwable ignored) { }
        return avatar;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topMargin, int rightMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = topMargin;
        p.rightMargin = rightMargin;
        return p;
    }

    private GradientDrawable surface(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dpF(radiusDp));
        return d;
    }

    private float dpF(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private TextView mdChip(String text, String url) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(MD_PRIMARY);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(dpF(8));
        d.setStroke(dp(1), MD_OUTLINE);
        t.setBackground(d);
        t.setPadding(dp(10), dp(5), dp(10), dp(5));
        t.setOnClickListener(v -> openUrl(url));
        return t;
    }

    private Button outlinedButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(MD_PRIMARY);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(dpF(20));
        d.setStroke(dp(1), MD_OUTLINE);
        b.setBackground(d);
        return b;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) { toast("无法打开链接，请先安装可用的浏览器。"); }
    }

    private void refreshDeviceInfo() {
        if (deviceInfoView == null || refreshingDevice || running) return;
        refreshingDevice = true;
        final String model = android.os.Build.MODEL;
        final String selected = selectedManager;
        new Thread(() -> {
            String kernel = "Stellar 未连接";
            boolean ready = false;
            if (ShizukuShell.available() && ShizukuShell.hasPermission()) {
                ShizukuShell.Result state = ShizukuShell.run("uname -r && cat /proc/modules", 10);
                kernel = state.code == 0 ? state.output.split("\n")[0].trim() : "读取失败，可点击检查准备重试";
                for (String line : state.output.split("\n")) ready |= RootDiagnostics.moduleLive(line);
            }
            String mgr = "未安装";
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(managerPkg(), 0);
                mgr = managerLabel() + " " + pi.versionName;
            } catch (Throwable ignored) { }
            final String txt = "设备  " + model + "\n内核  " + kernel + "\n管理器  " + mgr;
            final boolean loaded = ready;
            ui.post(() -> {
                refreshingDevice = false;
                if (!selected.equals(selectedManager)) { refreshDeviceInfo(); return; }
                moduleReady = loaded;
                if (deviceInfoView != null) deviceInfoView.setText(txt);
                updateManagerUi();
            });
        }, "device-info").start();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            updateElapsed();
            ui.postDelayed(this, 1000);
        }
    };

    private void startElapsedTicker() {
        runStart = System.currentTimeMillis();
        ticking = true;
        ui.post(tick);
    }

    private void stopElapsedTicker() {
        ticking = false;
    }

    private void updateElapsed() {
        if (elapsedView == null || runStart == 0) return;
        long s = (System.currentTimeMillis() - runStart) / 1000;
        elapsedView.setText(String.format(Locale.US, "%d:%02d", s / 60, s % 60));
    }
    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        return p;
    }

    private Button mkButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void start(Runnable job) {
        if (running) { toast("正在运行，请稍候"); return; }
        running = true;
        stepIndex = 0;
        progress(0, "开始…");
        startElapsedTicker();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setButtonsEnabled(false);
        switchTab(1);
        if (!wakeLock.isHeld()) wakeLock.acquire(60 * 60 * 1000L);
        new Thread(() -> {
            try { job.run(); }
            catch (Throwable t) { log("异常: " + t); progress(100, "执行失败，请查看日志"); }
            finally {
                running = false;
                stopElapsedTicker();
                if (progressPct < 100) {
                    progress(100, "未完成，请查看日志");
                }
                ui.post(() -> {
                    setButtonsEnabled(true);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    progressBar.setVisibility(View.GONE);
                    refreshDeviceInfo();
                    if (wakeLock.isHeld()) wakeLock.release();
                });
            }
        }, "onekey-pipeline").start();
    }

    private void setButtonsEnabled(boolean enabled) {
        ui.post(() -> {
            Button[] all = new Button[]{btnRun, btnPrep, btnOpen, btnClear, btnDiagnose};
            for (Button b : all) {
                if (b == null) continue;
                b.setEnabled(enabled);
                b.setAlpha(enabled ? 1f : 0.45f);
            }
            updateManagerUi();
        });
    }

    // ------------------------------------------------------------------ UI
    private void log(String msg) {
        android.util.Log.i("OneKeyJB", msg);
        String compact = msg.trim();
        if (compact.contains("attempt=") || compact.startsWith("LANDED") || compact.contains("landed=1")
                || compact.contains("MODULE_LOADED") || compact.contains("HIT pid=")) {
            progressLine(compact.length() > 60 ? compact.substring(0, 60) + "…" : compact);
        }
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + msg;
        ui.post(() -> {
            CharSequence old = logView.getText();
            if (old != null && old.length() > 120_000) {
                logView.setText(old.subSequence(old.length() - 60_000, old.length()));
            }
            logView.append(line + "\n");
            if (logScrollView != null) {
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private void refreshShizuku() {
        boolean up = ShizukuShell.available();
        boolean granted = up && ShizukuShell.hasPermission();
        final String text;
        final int dot;
        if (!up) {
            text = "Stellar 未运行";
            dot = Color.rgb(239, 83, 80);
        } else if (!granted) {
            text = "Stellar 已启动 · 点击授权";
            dot = Color.rgb(255, 193, 7);
        } else {
            text = "Stellar 已连接 · 可以检查准备";
            dot = Color.rgb(0, 200, 130);
        }
        ui.post(() -> {
            if (statusView != null) statusView.setText(text);
            if (statusSub != null) statusSub.setText("一加 16 / PYB110 · 作者 星苒鸭 · 越狱后重启失效");
            if (statusDot != null) statusDot.setTextColor(dot);
            updatePrimaryAction();
        });
    }

    private void openShizuku() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(STELLAR_PKG);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } else {
                toast("未安装 Stellar（github.com/roro2239/Stellar）");
            }
        } catch (Throwable t) {
            toast("打开 Stellar 失败: " + t.getMessage());
        }
    }

    private void connectStellar() {
        if (running) return;
        if (!ShizukuShell.available()) openShizuku();
        else if (!ShizukuShell.hasPermission()) ShizukuShell.requestPermission(1001);
        else openShizuku();
    }

    private void updatePrimaryAction() {
        if (btnRun == null) return;
        if (running) btnRun.setText("任务执行中");
        else if (!ShizukuShell.available()) btnRun.setText("连接 Stellar");
        else if (!ShizukuShell.hasPermission()) btnRun.setText("授予 Stellar 权限");
        else btnRun.setText(moduleReady ? "修复 root 环境" : "开始越狱");
    }

    private void openManager() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(managerPkg());
        if (intent != null) startActivity(intent);
        else openUrl(isSukisu() ? "https://github.com/SukiSU-Ultra/SukiSU-Ultra/releases/tag/v4.3.0"
                : "https://github.com/KernelSU-Next/KernelSU-Next/releases/tag/v3.4.2");
    }

    // -------------------------------------------------------------- pipeline
    private void runPipeline(boolean prepOnly) {
        log("========== 一加16提权越狱 开始 ==========");
        log("设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " / Android " + android.os.Build.VERSION.RELEASE
                + " (SDK " + android.os.Build.VERSION.SDK_INT + ")");
        if (!ShizukuShell.available()) {
            log("Stellar 未运行。请先打开 Stellar，用「无线调试」启动服务后再回来。");
            return;
        }
        if (!ShizukuShell.hasPermission()) {
            log("尚未获得 Stellar 权限，请回首页点击「授予 Stellar 权限」。");
            return;
        }
        log("Stellar 已连接，shell uid=" + ShizukuShell.serviceUid());
        ShizukuShell.Result kernel = ShizukuShell.run("uname -r", 15);
        if (kernel.code != 0 || kernel.output.trim().isEmpty()) {
            log("读取内核版本失败：" + kernel.output.trim());
            progress(100, "设备信息读取失败，请检查 Stellar 连接后重试");
            return;
        }
        String kernelRel = kernel.output.trim();
        log("内核: " + kernelRel);
        if (!DEVICE_MODEL.equals(android.os.Build.MODEL) || !kernelRel.equals(KERNEL_RELEASE)) {
            log("✗ 机型/内核不在支持范围（" + android.os.Build.MODEL + " / " + kernelRel + "）");
            log("  本工具的符号与内核偏移只适配 OnePlus 16 PYB110 + " + KERNEL_RELEASE + "；");
            log("  在其他内核上继续会造成内核崩溃/自动重启，已安全终止。");
            return;
        }

        // Current state
        ShizukuShell.Result st = ShizukuShell.run(
                "id && getenforce && cat /proc/modules", 30);
        if (st.code != 0 || RootDiagnostics.hasLine(st.output, "Disabled")) {
            log("无法确认内核和 SELinux 状态，停止执行：" + st.output);
            return;
        }
        for (String l : st.output.split("\n")) if (!l.trim().isEmpty()) log("  " + l.trim());
        String loadedModule = null;
        for (String line : st.output.split("\n")) {
            if (line.startsWith("kernelsu ")) loadedModule = line;
        }
        boolean moduleLoaded = loadedModule != null;
        boolean permissive = st.output.contains("Permissive");
        if (moduleLoaded && !RootDiagnostics.moduleLive(loadedModule)) {
            log("内核模块尚未就绪，停止操作；请稍后再试。");
            return;
        }
        if (moduleLoaded) {
            ShizukuShell.Result info = ShizukuShell.run(
                    "timeout 10 /system/bin/su -c '/data/adb/ksud debug info'", 15);
            String backend = RootDiagnostics.loadedBackend(info.code, info.output);
            if (backend == null) {
                log("无法确认当前 root 的配套版本，停止修复：" + info.output.trim());
                return;
            }
            selectedManager = backend;
            ui.post(this::updateManagerUi);
            log("当前内核运行 " + managerLabel() + "，本次按当前内核修复；切换另一种内核需先重启。");
        }
        if (!checkManager()) return;
        if (prepOnly) {
            progress(100, moduleLoaded ? "准备检查通过：可修复现有 root" : "准备检查通过：可以开始越狱");
            log("本次只读取设备、内核和管理器状态，没有投放组件或运行利用链。");
            return;
        }
        // 修复模式也必须先更新本次要执行的脚本和配套用户态。
        if (!stageRootUserspace()) return;
        if (moduleLoaded) {
            log("已加载 KernelSU：检查配套关系并修复 root 环境。");
            boolean fixed = rootStageAndVerify();
            if (fixed) {
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(PREF_MANAGER, selectedManager).apply();
                restartManager();
                progress(100, "root 环境修复完成，应用授权待检查");
                log("root 环境检查通过。请在管理器中授权目标应用，再彻底关闭并重新打开该应用。");
            } else {
                progress(100, "修复失败");
                log("✗ 修复未通过：请把日志发给开发者，或重启手机后再试。");
            }
            return;
        }

        // 0) kill stale exploit processes from a previous app instance (they live as shell processes)
        step("清理上一次残留的漏洞进程");
        ShizukuShell.run("for p in $(pgrep -f '(^|/)(perf_cred|t64560_v36|t64560_v42)( |$)'); do "
                + "[ \"$(readlink /proc/$p/cwd)\" = " + WORK + " ] && kill -9 $p; done; true", 10);

        // 1) workspace
        step("准备目录");
        ShizukuShell.run("mkdir -p " + WORK + " && chmod 755 " + WORK, 30);

        // 2) stage payloads
        step("投放漏洞组件（首次约 10-30 秒）");
        String[][] assets = {
                {"t64560_v36", WORK + "/t64560_v36", "755"},
                {"t64560_v42", WORK + "/t64560_v42", "755"},
                {"forker", WORK + "/forker", "755"},
                {"grab.sh", WORK + "/grab.sh", "755"},
        };
        for (String[] a : assets) {
            ShizukuShell.Result r = ShizukuShell.stageAsset(this, a[0], a[1], Integer.parseInt(a[2]));
            log("  " + r.output);
            if (r.code != 0) { log("投放失败，终止"); return; }
        }
        // 2.1) 所选内核的加载器
        String loaderAsset = isSukisu() ? "perf_cred_sukisu" : "perf_cred";
        ShizukuShell.Result ld = ShizukuShell.stageAsset(this, loaderAsset, WORK + "/perf_cred", 755);
        log("  " + ld.output);
        if (ld.code != 0) { log("投放 loader 失败，终止"); return; }

        // 4) ftrace grab
        step("抓取 sched_blocked_reason（约 60 秒，不动内核）");
        ShizukuShell.Result grab = ShizukuShell.runStreaming(
                "cd " + WORK + " && exec sh grab.sh", 300, line -> log("  " + line));
        log("  grab 退出码=" + grab.code);
        if (grab.code != 0) { log("抓取失败，终止"); return; }

        // 4) recover slide
        step("反推 KASLR slide");
        Map<String, Long> syms;
        try (InputStream in = getAssets().open("syms.txt")) {
            syms = Prep.loadSymbols(in);
        } catch (Exception e) {
            log("读取符号表失败: " + e); return;
        }
        log("  符号表加载完成: " + syms.size() + " 个");
        byte[][] bins = new byte[8][];
        int usableBins = 0;
        try {
            for (int i = 0; i < 8; i++) {
                java.io.File bf = new java.io.File(WORK + "/bl_" + i + ".bin");
                if (!bf.isFile() || bf.length() <= 0) {
                    log("  bl_" + i + ".bin 为空/不存在（该 CPU 无事件），跳过");
                    continue;
                }
                bins[i] = ShizukuShell.readDeviceFile(WORK + "/bl_" + i + ".bin");
                usableBins++;
            }
        } catch (Exception e) {
            log("读取 raw 抓包失败: " + e); return;
        }
        if (usableBins == 0) { log("没有任何可用的 raw 抓包（bl_*.bin 全空），请重试"); return; }
        byte[] textBytes;
        try { textBytes = ShizukuShell.readDeviceFile(WORK + "/bl_text.txt"); }
        catch (Exception e) { log("读取文本锚点失败: " + e); return; }
        String text = new String(textBytes, java.nio.charset.StandardCharsets.UTF_8);
        Prep.SlideResult sr = Prep.solveSlideBytes(bins, text, syms);
        if (!sr.ok) { log("slide 反推失败: " + sr.error); return; }
        log("  slide=0x" + Long.toHexString(sr.slide)
                + "  selinux_state=0x" + Long.toHexString(sr.selinuxState)
                + "  (anchors=" + sr.anchors + ", votes=" + sr.votes + ")");

        // 5) patch LKM
        step("按当前 slide 重打 " + managerLabel() + " LKM");
        byte[] ko;
        String templateName = isSukisu() ? "sukisu-template.ko" : "ksu-template.ko";
        try (InputStream in = getAssets().open(templateName)) {
            ko = Prep.readAsset(in);
        } catch (Exception e) { log("读取 ko 模板失败: " + e); return; }
        int patched = Prep.patchKo(ko, syms, sr.slide);
        if (patched <= 0) { log("ko 打补丁失败"); return; }
        log("  已补 " + patched + " 个符号（SHN_ABS）");
        ShizukuShell.Result koR = ShizukuShell.stageBytes(ko, "/data/local/tmp/ksu-op3.ko", 644);
        log("  " + koR.output);
        if (koR.code != 0) { log("推送 ko 失败"); return; }

        // 5.5) SELinux permissive（PC 流程必需：perf_cred 读 tracepoint/kmem 需要）
        if (!permissive) {
            step("SELinux -> Permissive（零写 selinux_state-7）");
            String permTarget = hexAdd(sr.selinuxState, -7);
            ShizukuShell.Result pr = ShizukuShell.runStreaming(
                    "cd " + WORK + " && exec ./t64560_v36 0 " + permTarget
                            + " 120 400 16 6000 12000 4500", 1500, line -> log("  " + line));
            log("  v36 退出码=" + pr.code);
            if (pr.code != 0) { log("内核写入步骤失败，停止后续操作。"); return; }
            String e2 = ShizukuShell.run("getenforce", 15).output.trim();
            log("  getenforce=" + e2);
            if (!e2.contains("Permissive")) { log("✗ permissive 未生效，终止"); return; }
        } else {
            log("已是 Permissive，跳过");
        }

        // 6) cred leak: pick a cred whose (cred+0x40) bit16 == 1 -> CAP_SYS_MODULE
        step("启动 perf_cred，挑选 bit16=1 的 cred");
        CredPick cp = pickCred();
        if (cp == null) {
            String enf = ShizukuShell.run("getenforce", 15).output.trim();
            log("✗ 取 cred 失败（当前 SELinux=" + enf + "）");
            if (!enf.contains("Permissive")) {
                log("→ 自动补做 SELinux permissive 后重试");
                if (runPermissive(sr)) cp = pickCred();
            }
            if (cp == null) {
                log("✗ 流程中止：perf_cred 拿不到 cred。请重启手机后重试，或把日志发给开发者（github.com/xingranya）");
                return;
            }
        }
        ShizukuShell.LineProc perf = cp.perf;
        ShizukuShell.LineProc caps = null;
        try {
            String cred = cp.cred;

            // 8) fsuid=0：PC 流程必需，让 ksu_cred 继承 fsuid=0（sucompat 才能打开 /data/adb/ksud）
            step("零写 cred+0x20（fsuid=fsgid=0）");
            String fsuidTarget = hexAdd(parseHex(cred), 0x20);
            ShizukuShell.Result f1 = ShizukuShell.runStreaming(
                    "cd " + WORK + " && exec ./t64560_v36 0 " + fsuidTarget
                            + " 150 400 16 6000 12000 4500", 1500, line -> log("  " + line));
            log("  v36 退出码=" + f1.code);
            if (f1.code != 0) { log("布局准备失败，停止后续操作。"); return; }
            String layout = null;
            try { layout = perf.waitFor("LAYOUT_DONE", 180); } catch (InterruptedException ie) { layout = null; }
            if (layout == null) {
                log("✗ perf_cred 未到 LAYOUT_DONE（fsuid 未生效或 /data/adb 布局失败）");
                log("  perf 尾部: " + perf.tail(8));
                return;
            }
            log("  perf_cred 已布局 /data/adb");

            // 7) cap_effective + module load
            step("写 cap_effective 并加载 LKM（最快约 1-3 分钟）");
            try {
                caps = ShizukuShell.start("cd " + WORK + " && exec ./t64560_v42 " + cred
                        + " 90 90 400 16 6000 12000", line -> log("  " + line));
            } catch (Exception e) { log("启动 v42 失败: " + e); return; }
            String mod = null;
            long end = android.os.SystemClock.elapsedRealtime() + 1500_000L;
            while (android.os.SystemClock.elapsedRealtime() < end) {
                // 被动等待：不频繁 fork shell 去 grep /proc/modules，
                // 避免在 phase1/phase2 竞态期间扰动内核 slab 状态。
                try { mod = perf.waitFor("MODULE_LOADED", 2); } catch (InterruptedException ie) { mod = null; }
                if (mod != null) {
                    long waitStart = android.os.SystemClock.elapsedRealtime();
                    for (int w = 0; w < 360; w++) {
                        String l2 = kernelsuModuleLine();
                        if (RootDiagnostics.moduleLive(l2)) { mod = l2; break; }
                        if (android.os.SystemClock.elapsedRealtime() - waitStart > 180_000L) {
                            log("  ✗ 模块长时间未进入 Live：停止后续操作，请重启手机后重试");
                            caps.destroy();
                                        return;
                        }
                        try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                    }
                    break;
                }
                boolean capsAlive = caps.isRunning();
                boolean perfAlive = perf.isRunning();
                if (!perfAlive) {
                    String l3 = kernelsuModuleLine();
                    if (RootDiagnostics.moduleLive(l3)) mod = l3;
                    break;
                }
                if (!capsAlive) break;
            }
            caps.destroy();
            if (!RootDiagnostics.moduleLive(mod)) {
                log("模块未确认就绪；perf 尾部: " + perf.tail(4));
                return;
            }
            log("  ✓ " + mod.trim());
            // let the late-load init finish settling before any supercall

            try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            ShizukuShell.Result m = ShizukuShell.run("grep -i kernelsu /proc/modules; getenforce", 15);
            for (String l : m.output.split("\n")) if (!l.trim().isEmpty()) log("  " + l.trim());

            // 用户态失败保留日志，不重复运行内核写入流程。
            boolean ok = rootStageAndVerify();
            if (ok) {
                restartManager();
                progress(100, "root 环境已就绪，应用授权待检查");
                log("root 环境检查通过。请在 " + managerLabel() + " 中授权目标应用，再重新打开该应用。");
                log("重启后失效；下次重启只需：启动 Stellar → 点「一键越狱」。");
                log("作者：星苒鸭 · github.com/xingranya · xran.uk");
            } else {
                progress(100, "root 环境检查失败");
                log("验收未通过：请把上面的日志发给开发者（github.com/xingranya）。");
            }
        } finally {
            if (caps != null) caps.destroy();
            perf.destroy();
        }
    }

    /** 使用本次执行标识验证结果，旧日志和旧成功文件不能作为成功依据。 */
    private boolean rootStageAndVerify() {
        step("安装配套用户态并验证 su");
        String runId = java.util.UUID.randomUUID().toString();
        ShizukuShell.Result token = ShizukuShell.stageBytes(
                (runId + "\n").getBytes(StandardCharsets.UTF_8), WORK + "/root_run", 644);
        if (token.code != 0) { log(token.output); return false; }
        ShizukuShell.Result root = ShizukuShell.runStreaming(
                "exec " + WORK + "/ksu_grant", 300, line -> log("  " + line));
        log("  ksu_grant 退出码=" + root.code);
        ShizukuShell.Result detail = ShizukuShell.run("tail -60 " + WORK + "/root_stage.log 2>&1", 10);
        for (String line : detail.output.split("\n")) if (!line.trim().isEmpty()) log("  " + line);
        ShizukuShell.Result marker = ShizukuShell.run("cat " + WORK + "/root_stage.ok 2>/dev/null", 10);
        if (marker.code != 0 || !RootDiagnostics.stagePassed(root.code, marker.output, runId)) {
            log("root 环境检查失败；请查看安装、配套版本和 su 退出码。");
            return false;
        }
        return suSelfTest();
    }

    /** Restart the selected manager so it can fetch a fresh ksu fd. */
    private void restartManager() {
        if (isSukisu()) return; // SukiSU 已在 root 脚本中重启并检查内核连接。
        step("重启 " + managerLabel() + " 管理器（重新获取 ksu fd）");
        ShizukuShell.Result mr = ShizukuShell.run(
                "am force-stop " + managerPkg() + "; sleep 1; "
                        + "am start -n " + managerActivity() + " 2>&1 || "
                        + "monkey -p " + managerPkg() + " -c android.intent.category.LAUNCHER 1 2>&1", 30);
        log("  " + mr.output.trim().replace("\n", " | "));
    }

    /** 检查 Stellar 会话中的命令和管道；它不代表其他应用已获授权。 */
    private boolean suSelfTest() {
        String probe = "u=$(id -u) || exit 1; echo ROOT_UID=$u; [ \"$u\" = 0 ] || exit 1; "
                + "echo STDOUT_OK; echo STDERR_OK >&2; exit 0";
        ShizukuShell.Result command = ShizukuShell.run("timeout 10 /system/bin/su -c '" + probe + "' 2>&1", 15);
        ShizukuShell.Result pipe = ShizukuShell.run("printf '%s\\n' '" + probe
                + "' | timeout 10 /system/bin/su 2>&1", 15);
        ShizukuShell.Result search = ShizukuShell.run("PATH=/system/bin:/system/xbin:/system_ext/bin; "
                + "command -v su && timeout 10 su -c '" + probe + "' 2>&1", 15);
        log("Stellar 会话 UID=" + ShizukuShell.serviceUid() + "；命令退出码=" + command.code + "，管道退出码=" + pipe.code);
        log("默认 su 入口：退出码=" + search.code + "\n" + search.output.trim());
        boolean ok = RootDiagnostics.suPassed(command.code, command.output)
                && RootDiagnostics.suPassed(pipe.code, pipe.output)
                && RootDiagnostics.suPassed(search.code, search.output);
        if (!ok) log("su 会话失败：\n" + command.output + pipe.output);
        log("其他应用的授权需用其自身 UID 验证；可先使用首页的「root 诊断」。");
        return ok;
    }

    private void diagnoseRoot() {
        log("应用 UID=" + android.os.Process.myUid() + "；以下请求直接由本应用发起。");
        log("请先在当前 root 管理器中为「一加16提权越狱」开启 root；Stellar 授权不能替代 root 授权。");
        File entry = new File("/system/bin/su");
        log("su 入口：存在=" + entry.exists() + "，可执行=" + entry.canExecute());
        RootDiagnostics.Result lookup = RootDiagnostics.run(new String[]{"/system/bin/sh", "-c",
                "echo PATH=$PATH; command -v su"}, "", 12);
        log("终端查找 su：退出码=" + lookup.code + "\n" + lookup.output);
        String[][] commands = {{"/system/bin/su", "-c", RootDiagnostics.PROBE},
                {"/system/bin/su"}, {"/system/bin/su", "-M"},
                {"/system/bin/sh", "-c", "command -v su >/dev/null && exec su"}};
        String[] labels = {"绝对路径执行", "交互式管道", "全局挂载空间", "默认 su 入口"};
        int passed = 0;
        for (int i = 0; i < commands.length; i++) {
            RootDiagnostics.Result result = RootDiagnostics.run(commands[i], i == 0 ? "" : RootDiagnostics.PROBE, 12);
            log(labels[i] + "：退出码=" + result.code + "\n" + result.output.trim());
            if (RootDiagnostics.suPassed(result.code, result.output)) passed++;
        }
        log("su 诊断通过 " + passed + "/" + commands.length + " 项；PRIVATE_READ / STORAGE_READ 单独表示目录是否可读。");
        log("默认入口失败但绝对路径通过时，请运行末段修复，并彻底关闭后重新打开目标应用。");
        log("若只有全局挂载空间能读存储，请检查失败应用的挂载空间设置；若 UID 不是 0，请检查其自定义 root 配置。");
        log("Stellar 与存储空间清理需分别授权，并彻底退出后重新打开；本应用通过不代表它们已通过。");
        progress(100, "诊断完成：" + passed + "/" + commands.length + " 项通过");
    }

    /** SELinux -> permissive fallback (only used when the Enforcing fast path failed). */
    private boolean runPermissive(Prep.SlideResult sr) {
        step("补做 SELinux -> Permissive（fallback）");
        String permTarget = hexAdd(sr.selinuxState, -7);
        ShizukuShell.Result r = ShizukuShell.runStreaming(
                "cd " + WORK + " && exec ./t64560_v36 0 " + permTarget
                        + " 120 400 16 6000 12000 4500", 1500, line -> log("  " + line));
        if (r.code != 0) log("  v36 退出码=" + r.code);
        String enf = ShizukuShell.run("getenforce", 30).output.trim();
        log("  getenforce=" + enf);
        return r.code == 0 && enf.contains("Permissive");
    }

    private static final class CredPick {
        String cred;
        ShizukuShell.LineProc perf;
    }

    /** Start perf_cred (PC variant) and choose a cred whose (cred+0x40) bit16 == 1. */
    private CredPick pickCred() {
        for (int pick = 1; pick <= 20; pick++) {
            ShizukuShell.LineProc proc;
            try {
                proc = ShizukuShell.start("cd " + WORK + " && exec ./perf_cred", line -> log("    perf: " + line));
            } catch (Exception e) {
                log("  启动 perf_cred 失败: " + e);
                return null;
            }
            String hit;
            try { hit = proc.waitFor("HIT pid=", 90); } catch (InterruptedException ie) { hit = null; }
            if (hit == null) {
                log("  [" + pick + "] perf_cred 无 HIT，末尾输出: " + proc.tail(6).replace("\n", " | "));
                proc.destroy();
                return null;
            }
            log("  [" + pick + "] " + hit.trim());
            String c = extract(hit, "ptr=");
            if (c == null) { log("  cred 解析失败"); proc.destroy(); return null; }
            long eff = parseHex(c) + 0x40;
            int bit16 = (int) ((eff >>> 16) & 1);
            log("  cred=" + c + "  bit16(CAP_SYS_MODULE)=" + bit16);
            if (bit16 == 1) {
                CredPick res = new CredPick();
                res.cred = c;
                res.perf = proc;
                return res;
            }
            log("  该 cred bit16=0，换下一个 perf_cred…");
            proc.destroy();
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        }
        return null;
    }

    private boolean isSukisu() { return "sukisu".equals(selectedManager); }

    private String managerPkg() { return isSukisu() ? SUKISU_PKG : MANAGER_PKG; }

    private String managerLabel() { return isSukisu() ? "SukiSU Ultra" : "KernelSU-Next"; }

    private String managerActivity() {
        return isSukisu()
                ? SUKISU_PKG + "/com.sukisu.ultra.ui.MainActivity"
                : MANAGER_PKG + "/com.rifsxd.ksunext.ui.MainActivity";
    }

    private boolean managerIsOfficial() {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                pi = pm.getPackageInfo(managerPkg(), PackageManager.GET_SIGNING_CERTIFICATES);
                Signature[] sigs = pi.signingInfo != null ? pi.signingInfo.getApkContentsSigners() : null;
                if (sigs == null || sigs.length == 0) return false;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] h = md.digest(sigs[0].toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : h) sb.append(String.format("%02x", b));
                String expect = isSukisu() ? SUKISU_CERT_SHA256 : MANAGER_CERT_SHA256;
                return expect.equalsIgnoreCase(sb.toString());
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean sameVersion(String expected, String actual) {
        return actual != null && expected.replaceFirst("^v", "").equals(actual.replaceFirst("^v", ""));
    }

    /** 配套管理器缺失或不匹配时，不继续安装用户态或加载模块。 */
    private boolean checkManager() {
        step("检查 " + managerLabel() + " 管理器");
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(managerPkg(), 0);
            String expected = isSukisu() ? SUKISU_EXPECTED_VERSION : MANAGER_EXPECTED_VERSION;
            log("  已安装 " + managerLabel() + " " + pi.versionName);
            if (!managerIsOfficial()) {
                log("管理器签名不匹配，请安装官方版本后重试。");
                return false;
            }
            if (!sameVersion(expected, pi.versionName)) {
                log("内置模块需要 " + managerLabel() + " " + expected + "，请先安装匹配版本。");
                return false;
            }
            return true;
        } catch (PackageManager.NameNotFoundException ex) {
            log("请先安装官方 " + managerLabel() + " 管理器。");
            return false;
        }
    }

    private boolean stageRootUserspace() {
        step("准备 " + managerLabel() + " 配套用户态");
        String[][] assets = {{"ksu_grant", WORK + "/ksu_grant"},
                {"su_forward", WORK + "/su_forward"},
                {"su_path_compat.sh", WORK + "/su_path_compat.sh"},
                {"root_stage_mobile.sh", "/data/local/tmp/root_stage.sh"}};
        for (String[] asset : assets) {
            ShizukuShell.Result result = ShizukuShell.stageAsset(this, asset[0], asset[1], 755);
            if (result.code != 0) { log(result.output); return false; }
        }
        try {
            android.content.pm.ApplicationInfo info = getPackageManager().getApplicationInfo(managerPkg(), 0);
            byte[] daemon = null;
            File lib = new File(info.nativeLibraryDir, "libksud.so");
            if (lib.isFile()) {
                try (InputStream in = new FileInputStream(lib)) { daemon = Prep.readAsset(in); }
            } else {
                java.util.List<String> apks = new java.util.ArrayList<>();
                apks.add(info.sourceDir);
                if (info.splitSourceDirs != null) java.util.Collections.addAll(apks, info.splitSourceDirs);
                for (String apk : apks) {
                    try (ZipFile zip = new ZipFile(apk)) {
                        ZipEntry entry = zip.getEntry("lib/arm64-v8a/libksud.so");
                        if (entry == null) continue;
                        try (InputStream in = zip.getInputStream(entry)) { daemon = Prep.readAsset(in); }
                        break;
                    }
                }
            }
            if (daemon == null || daemon.length == 0) throw new java.io.IOException("管理器中缺少 arm64 ksud");
            ShizukuShell.Result binary = ShizukuShell.stageBytes(daemon, WORK + "/ksud_manager", 755);
            if (binary.code != 0) { log(binary.output); return false; }
            // 原生加载器仍读取各自固定路径，冷启动也必须补齐。
            String bootstrapPath = isSukisu() ? WORK + "/ksud_sukisu" : "/data/local/tmp/ksud342";
            ShizukuShell.Result bootstrap = ShizukuShell.stageBytes(daemon, bootstrapPath, 755);
            if (bootstrap.code != 0) { log(bootstrap.output); return false; }
            ShizukuShell.Result backend = ShizukuShell.stageBytes(
                    (selectedManager + "\n").getBytes(StandardCharsets.UTF_8), WORK + "/root_backend", 644);
            if (backend.code != 0) { log(backend.output); return false; }
            return true;
        } catch (Exception e) {
            log("准备配套用户态失败：" + e.getMessage());
            return false;
        }
    }


    /** Returns the /proc/modules line for kernelsu, or null. */
    private String kernelsuModuleLine() {
        ShizukuShell.Result chk = ShizukuShell.run("grep -i kernelsu /proc/modules", 8);
        for (String l : chk.output.split("\n")) {
            String t = l.trim();
            if (t.startsWith("kernelsu ")) return t;
        }
        return null;
    }

    private void step(String s) {
        log("");
        log("== " + s + " ==");
        stepIndex++;
        progress(Math.min(95, 8 + stepIndex * 7), s);
        ui.post(() -> { if (stageView != null) stageView.setText(s); });
    }

    private void progress(int pct, String label) {
        progressPct = pct;
        ui.post(() -> {
            if (progressBar != null) {
                progressBar.setVisibility(pct < 100 ? View.VISIBLE : View.GONE);
                progressBar.setIndeterminate(pct < 100);
            }
            if (progressText != null) progressText.setText(label);
            updateElapsed();
        });
    }

    private void progressLine(String label) {
        ui.post(() -> {
            if (progressText != null) progressText.setText(label);
        });
    }

    private static String extract(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return null;
        int s = i + key.length();
        int e = s;
        while (e < line.length()) {
            char c = line.charAt(e);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == 'x') e++;
            else break;
        }
        if (e <= s) return null;
        return line.substring(s, e);
    }

    private static long parseHex(String s) {
        return Long.parseUnsignedLong(s.replaceFirst("^0x", ""), 16);
    }

    private static String hexAdd(long v, long delta) {
        long r = v + delta;
        return "0x" + Long.toHexString(r);
    }

    @Override
    protected void onDestroy() {
        try { Stellar.INSTANCE.removeBinderReceivedListener(binderListener); } catch (Throwable ignored) { }
        try { Stellar.INSTANCE.removeBinderDeadListener(deadListener); } catch (Throwable ignored) { }
        try { Stellar.INSTANCE.removeRequestPermissionResultListener(permListener); } catch (Throwable ignored) { }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
