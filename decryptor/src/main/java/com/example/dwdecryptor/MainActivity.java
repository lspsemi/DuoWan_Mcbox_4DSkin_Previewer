package com.example.dwdecryptor;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_OPEN_TREE = 1001;
    private static final int REQ_CREATE_LOG = 1002;
    private static final int LOG_FLUSH_DELAY_MS = 16;
    private static final int STATUS_TICK_MS = 250;
    private static final int MAX_VISIBLE_LOG_CHARS = 24_000;
    private static final String PREFS = "dw_decryptor";
    private static final String PREF_CHINESE = "chinese";
    private static final String PREF_TREE_URI = "tree_uri";
    private static final String PREF_REPAIR_MAP_DB = "repair_map_db";
    private static final String PREF_REPAIR_DECRYPT = "repair_mode_decrypt";
    private static final String PREF_KEEP_ARTIFACTS = "keep_repair_artifacts";
    private static final String PREF_ANALYZE_WAL = "analyze_map_wal";
    private static final String PREF_ANALYZE_USER_KEY = "analyze_user_key";

    private enum RunState { IDLE, RUNNING, PAUSED, STOPPING, DONE, STOPPED, FAILED }

    private TextView titleView;
    private MaterialButton languageButton;
    private TextView folderLabelView;
    private TextView folderNameView;
    private TextView folderPathView;
    private TextView statusView;
    private TextView currentItemView;
    private LinearProgressIndicator progressIndicator;
    private LinearLayout idleActions;
    private LinearLayout runActions;
    private MaterialButton pickButton;
    private MaterialButton startButton;
    private MaterialButton pauseButton;
    private MaterialButton stopButton;
    private LinearLayout optionsHeader;
    private TextView optionsTitleView;
    private TextView optionsSummaryView;
    private ImageView optionsChevron;
    private LinearLayout optionsBody;
    private SwitchRow repairRow;
    private TextView repairModeLabel;
    private MaterialButtonToggleGroup repairModeGroup;
    private MaterialButton modeDeleteButton;
    private MaterialButton modeDecryptButton;
    private SwitchRow keepArtifactsRow;
    private TextView analysisLabel;
    private SwitchRow analyzeWalRow;
    private SwitchRow analyzeUserKeyRow;
    private TextView logTitleView;
    private MaterialButton saveLogButton;
    private MaterialButton clearLogButton;
    private TextView logView;
    private ScrollView logScroll;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();
    private final StringBuilder pendingLog = new StringBuilder();
    private final Object pauseLock = new Object();
    private boolean logFlushScheduled;
    private boolean autoFollowLog = true;
    private boolean programmaticLogScroll;
    private boolean chinese;
    private boolean optionsExpanded;
    private Uri treeUri;
    private RunState state = RunState.IDLE;
    private volatile boolean paused;
    private volatile boolean stopRequested;
    private volatile String currentItem;
    private volatile int processedCount;
    private long runStartedAt;
    private long runElapsedMs;
    private int resultSucceeded;
    private int resultFailed;

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            if (!isBusy()) {
                return;
            }
            updateStatus();
            mainHandler.postDelayed(this, STATUS_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DecryptorUi.applyDynamicColors(this);
        buildUi();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildUi() {
        chinese = loadInitialLanguage();
        treeUri = restoreTreeUri();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DecryptorUi.surface(this));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(16) + insets.getSystemWindowInsetLeft(),
                    dp(8) + insets.getSystemWindowInsetTop(),
                    dp(16) + insets.getSystemWindowInsetRight(),
                    dp(16) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        root.addView(buildHeader(), new LinearLayout.LayoutParams(-1, dp(64)));

        // Folder/task card and options scroll together, capped so the log always keeps room.
        ScrollView topScroll = new ScrollView(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int cap = Math.round(getResources().getDisplayMetrics().heightPixels * 0.58f);
                int available = MeasureSpec.getSize(heightMeasureSpec);
                int max = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                        ? cap : Math.min(cap, available);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        topScroll.setVerticalScrollBarEnabled(false);
        topScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout topContent = new LinearLayout(this);
        topContent.setOrientation(LinearLayout.VERTICAL);
        topContent.addView(buildTaskCard(), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(-1, -2);
        optionsParams.topMargin = dp(12);
        topContent.addView(buildOptionsCard(), optionsParams);
        topScroll.addView(topContent);
        root.addView(topScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        logParams.topMargin = dp(12);
        root.addView(buildLogCard(), logParams);

        setContentView(root);
        updateTexts();
        refreshState();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(this);
        titleView.setTextSize(22);
        titleView.setTextColor(DecryptorUi.onSurface(this));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));

        languageButton = pillButton(R.attr.tonalButtonStyle, R.drawable.ic_translate, 40);
        languageButton.setOnClickListener(v -> {
            chinese = !chinese;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_CHINESE, chinese).apply();
            updateTexts();
            refreshState();
        });
        header.addView(languageButton, new LinearLayout.LayoutParams(-2, dp(40)));
        return header;
    }

    private View buildTaskCard() {
        LinearLayout card = card();

        folderLabelView = labelText();
        card.addView(folderLabelView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout folderRow = new LinearLayout(this);
        folderRow.setOrientation(LinearLayout.HORIZONTAL);
        folderRow.setGravity(Gravity.CENTER_VERTICAL);
        folderRow.setPadding(0, dp(8), 0, 0);
        ImageView folderIcon = new ImageView(this);
        folderIcon.setImageResource(R.drawable.ic_folder);
        folderIcon.setImageTintList(ColorStateList.valueOf(DecryptorUi.primary(this)));
        folderRow.addView(folderIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout folderText = new LinearLayout(this);
        folderText.setOrientation(LinearLayout.VERTICAL);
        folderText.setPadding(dp(12), 0, 0, 0);
        folderNameView = new TextView(this);
        folderNameView.setTextSize(16);
        folderNameView.setTextColor(DecryptorUi.onSurface(this));
        folderNameView.setSingleLine(true);
        folderNameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        folderText.addView(folderNameView, new LinearLayout.LayoutParams(-1, -2));
        folderPathView = new TextView(this);
        folderPathView.setTextSize(13);
        folderPathView.setTextColor(DecryptorUi.onSurfaceVariant(this));
        folderPathView.setMaxLines(2);
        folderPathView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        folderText.addView(folderPathView, new LinearLayout.LayoutParams(-1, -2));
        folderRow.addView(folderText, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(folderRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout statusBlock = new LinearLayout(this);
        statusBlock.setOrientation(LinearLayout.VERTICAL);
        statusBlock.setPadding(0, dp(16), 0, 0);
        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusBlock.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
        currentItemView = new TextView(this);
        currentItemView.setTextSize(13);
        currentItemView.setTextColor(DecryptorUi.onSurfaceVariant(this));
        currentItemView.setSingleLine(true);
        currentItemView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        currentItemView.setPadding(0, dp(2), 0, 0);
        statusBlock.addView(currentItemView, new LinearLayout.LayoutParams(-1, -2));
        progressIndicator = new LinearProgressIndicator(this);
        progressIndicator.setIndeterminate(true);
        progressIndicator.setTrackThickness(dp(4));
        progressIndicator.setTrackCornerRadius(dp(2));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, -2);
        progressParams.topMargin = dp(10);
        statusBlock.addView(progressIndicator, progressParams);
        card.addView(statusBlock, new LinearLayout.LayoutParams(-1, -2));

        idleActions = actionRow();
        pickButton = pillButton(R.attr.tonalButtonStyle, R.drawable.ic_folder_open, 48);
        pickButton.setOnClickListener(v -> openTree());
        idleActions.addView(pickButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        startButton = pillButton(0, R.drawable.ic_play, 48);
        startButton.setOnClickListener(v -> startDecrypt());
        idleActions.addView(startButton, spacedWeight());
        card.addView(idleActions, actionRowParams());

        runActions = actionRow();
        pauseButton = pillButton(R.attr.tonalButtonStyle, R.drawable.ic_pause, 48);
        pauseButton.setOnClickListener(v -> togglePause());
        runActions.addView(pauseButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        stopButton = pillButton(com.google.android.material.R.attr.materialButtonOutlinedStyle,
                R.drawable.ic_stop, 48);
        stopButton.setOnClickListener(v -> requestStop());
        runActions.addView(stopButton, spacedWeight());
        card.addView(runActions, actionRowParams());
        return card;
    }

    private View buildOptionsCard() {
        LinearLayout card = card();
        card.setPadding(0, dp(4), 0, dp(4));

        optionsHeader = new LinearLayout(this);
        optionsHeader.setOrientation(LinearLayout.HORIZONTAL);
        optionsHeader.setGravity(Gravity.CENTER_VERTICAL);
        optionsHeader.setMinimumHeight(dp(64));
        optionsHeader.setPadding(dp(16), dp(8), dp(12), dp(8));
        optionsHeader.setBackground(rippleBackground());
        optionsHeader.setOnClickListener(v -> setOptionsExpanded(!optionsExpanded));
        ImageView tuneIcon = new ImageView(this);
        tuneIcon.setImageResource(R.drawable.ic_tune);
        tuneIcon.setImageTintList(ColorStateList.valueOf(DecryptorUi.onSurfaceVariant(this)));
        optionsHeader.addView(tuneIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout headerText = new LinearLayout(this);
        headerText.setOrientation(LinearLayout.VERTICAL);
        headerText.setPadding(dp(16), 0, dp(8), 0);
        optionsTitleView = new TextView(this);
        optionsTitleView.setTextSize(16);
        optionsTitleView.setTextColor(DecryptorUi.onSurface(this));
        headerText.addView(optionsTitleView, new LinearLayout.LayoutParams(-1, -2));
        optionsSummaryView = new TextView(this);
        optionsSummaryView.setTextSize(13);
        optionsSummaryView.setTextColor(DecryptorUi.onSurfaceVariant(this));
        optionsSummaryView.setSingleLine(true);
        optionsSummaryView.setEllipsize(TextUtils.TruncateAt.END);
        headerText.addView(optionsSummaryView, new LinearLayout.LayoutParams(-1, -2));
        optionsHeader.addView(headerText, new LinearLayout.LayoutParams(0, -2, 1f));
        optionsChevron = new ImageView(this);
        optionsChevron.setImageResource(R.drawable.ic_expand_more);
        optionsChevron.setImageTintList(ColorStateList.valueOf(DecryptorUi.onSurfaceVariant(this)));
        optionsHeader.addView(optionsChevron, new LinearLayout.LayoutParams(dp(24), dp(24)));
        card.addView(optionsHeader, new LinearLayout.LayoutParams(-1, -2));

        optionsBody = new LinearLayout(this);
        optionsBody.setOrientation(LinearLayout.VERTICAL);
        optionsBody.setPadding(0, 0, 0, dp(8));

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        repairRow = switchRow(prefs.getBoolean(PREF_REPAIR_MAP_DB, true));
        repairRow.toggle.setOnCheckedChangeListener((b, checked) -> {
            savePref(PREF_REPAIR_MAP_DB, checked);
            refreshOptions();
        });
        optionsBody.addView(repairRow.row, new LinearLayout.LayoutParams(-1, -2));

        repairModeLabel = labelText();
        repairModeLabel.setPadding(dp(16), dp(4), dp(16), dp(8));
        optionsBody.addView(repairModeLabel, new LinearLayout.LayoutParams(-1, -2));
        repairModeGroup = new MaterialButtonToggleGroup(this);
        repairModeGroup.setSingleSelection(true);
        repairModeGroup.setSelectionRequired(true);
        modeDeleteButton = segmentButton();
        modeDecryptButton = segmentButton();
        repairModeGroup.addView(modeDeleteButton, new LinearLayout.LayoutParams(0, dp(40), 1f));
        repairModeGroup.addView(modeDecryptButton, new LinearLayout.LayoutParams(0, dp(40), 1f));
        repairModeGroup.check(prefs.getBoolean(PREF_REPAIR_DECRYPT, false)
                ? modeDecryptButton.getId() : modeDeleteButton.getId());
        repairModeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                savePref(PREF_REPAIR_DECRYPT, checkedId == modeDecryptButton.getId());
                refreshOptions();
            }
        });
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(-1, -2);
        groupParams.leftMargin = dp(16);
        groupParams.rightMargin = dp(16);
        groupParams.bottomMargin = dp(4);
        optionsBody.addView(repairModeGroup, groupParams);

        keepArtifactsRow = switchRow(prefs.getBoolean(PREF_KEEP_ARTIFACTS, false));
        keepArtifactsRow.toggle.setOnCheckedChangeListener((b, checked) -> {
            savePref(PREF_KEEP_ARTIFACTS, checked);
            refreshOptions();
        });
        optionsBody.addView(keepArtifactsRow.row, new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(this);
        divider.setBackgroundColor(DecryptorUi.outlineVariant(this));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        dividerParams.leftMargin = dp(16);
        dividerParams.rightMargin = dp(16);
        dividerParams.topMargin = dp(4);
        optionsBody.addView(divider, dividerParams);

        analysisLabel = labelText();
        analysisLabel.setPadding(dp(16), dp(12), dp(16), 0);
        optionsBody.addView(analysisLabel, new LinearLayout.LayoutParams(-1, -2));
        analyzeWalRow = switchRow(prefs.getBoolean(PREF_ANALYZE_WAL, false));
        analyzeWalRow.toggle.setOnCheckedChangeListener((b, checked) -> {
            savePref(PREF_ANALYZE_WAL, checked);
            refreshOptions();
        });
        optionsBody.addView(analyzeWalRow.row, new LinearLayout.LayoutParams(-1, -2));
        analyzeUserKeyRow = switchRow(prefs.getBoolean(PREF_ANALYZE_USER_KEY, false));
        analyzeUserKeyRow.toggle.setOnCheckedChangeListener((b, checked) -> {
            savePref(PREF_ANALYZE_USER_KEY, checked);
            refreshOptions();
        });
        optionsBody.addView(analyzeUserKeyRow.row, new LinearLayout.LayoutParams(-1, -2));

        card.addView(optionsBody, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private View buildLogCard() {
        LinearLayout card = card();
        card.setPadding(dp(16), dp(4), dp(8), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(52));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_terminal);
        icon.setImageTintList(ColorStateList.valueOf(DecryptorUi.onSurfaceVariant(this)));
        header.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        logTitleView = new TextView(this);
        logTitleView.setTextSize(16);
        logTitleView.setTextColor(DecryptorUi.onSurface(this));
        logTitleView.setPadding(dp(12), 0, 0, 0);
        header.addView(logTitleView, new LinearLayout.LayoutParams(0, -2, 1f));
        clearLogButton = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialIconButtonStyle);
        clearLogButton.setIconResource(R.drawable.ic_clear_all);
        clearLogButton.setIconTint(ColorStateList.valueOf(DecryptorUi.onSurfaceVariant(this)));
        clearLogButton.setOnClickListener(v -> clearLog());
        header.addView(clearLogButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        saveLogButton = pillButton(R.attr.tonalButtonStyle, R.drawable.ic_save, 36);
        saveLogButton.setTextSize(13);
        saveLogButton.setOnClickListener(v -> createLogFile());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-2, dp(36));
        saveParams.leftMargin = dp(4);
        saveParams.rightMargin = dp(4);
        header.addView(saveLogButton, saveParams);
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));

        logScroll = new ScrollView(this) {
            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                if (!programmaticLogScroll && isLogAtBottom()) {
                    autoFollowLog = true;
                }
            }
        };
        logScroll.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE) {
                autoFollowLog = false;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (isLogAtBottom()) {
                    autoFollowLog = true;
                }
            }
            return false;
        });
        logScroll.setBackground(rounded(DecryptorUi.surface(this), 16));
        logScroll.setClipToOutline(true);
        logView = new TextView(this);
        logView.setTextIsSelectable(false);
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(dp(2), 1f);
        logView.setTextColor(DecryptorUi.onSurface(this));
        logView.setHintTextColor(DecryptorUi.onSurfaceVariant(this));
        logView.setPadding(dp(12), dp(10), dp(12), dp(10));
        logScroll.addView(logView);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollParams.rightMargin = dp(8);
        card.addView(logScroll, scrollParams);
        return card;
    }

    // ---- Run flow -------------------------------------------------------------------------

    private boolean isBusy() {
        return state == RunState.RUNNING || state == RunState.PAUSED || state == RunState.STOPPING;
    }

    private void openTree() {
        if (isBusy()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (treeUri != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri);
        }
        startActivityForResult(intent, REQ_OPEN_TREE);
    }

    private void createLogFile() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "dw_decrypt_log_" + stamp + ".txt");
        startActivityForResult(intent, REQ_CREATE_LOG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        if (requestCode == REQ_CREATE_LOG) {
            saveLog(uri);
            return;
        }
        if (requestCode != REQ_OPEN_TREE || isBusy()) {
            return;
        }
        getContentResolver().takePersistableUriPermission(uri,
                data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        if (treeUri != null && !treeUri.equals(uri)) {
            try {
                getContentResolver().releasePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // The old grant may already be gone.
            }
        }
        treeUri = uri;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_TREE_URI, uri.toString()).apply();
        state = RunState.IDLE;
        append(t("Selected") + ": " + folderDisplayName(uri));
        refreshState();
    }

    private void startDecrypt() {
        if (treeUri == null || isBusy()) {
            return;
        }
        Uri uri = treeUri;
        boolean repairMapDb = repairRow.toggle.isChecked();
        DwDecryptor.MapDbRepairMode repairMode = repairModeGroup.getCheckedButtonId() == modeDecryptButton.getId()
                ? DwDecryptor.MapDbRepairMode.DECRYPT_MVILLAGES
                : DwDecryptor.MapDbRepairMode.DELETE_MVILLAGES;
        boolean keepRepairArtifacts = keepArtifactsRow.toggle.isChecked();
        boolean analyzeMapWal = analyzeWalRow.toggle.isChecked();
        boolean analyzeUserKey = analyzeUserKeyRow.toggle.isChecked();

        paused = false;
        stopRequested = false;
        currentItem = null;
        processedCount = 0;
        runStartedAt = SystemClock.elapsedRealtime();
        state = RunState.RUNNING;
        setOptionsExpanded(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        append(t("Start") + ": " + folderDisplayName(uri));
        refreshState();
        mainHandler.postDelayed(statusTicker, STATUS_TICK_MS);
        new Thread(() -> runDecrypt(uri, repairMapDb, repairMode, keepRepairArtifacts,
                analyzeMapWal, analyzeUserKey), "dw-decrypt").start();
    }

    private void runDecrypt(Uri uri, boolean repairMapDb, DwDecryptor.MapDbRepairMode repairMode,
                            boolean keepRepairArtifacts, boolean analyzeMapWal,
                            boolean analyzeUserKey) {
        RunState result;
        int succeeded = 0;
        int failed = 0;
        try {
            DwDecryptor decryptor = new DwDecryptor(this, this::append, repairMapDb, repairMode,
                    keepRepairArtifacts, analyzeMapWal, analyzeUserKey, chinese);
            decryptor.setRunControl(this::awaitContinue);
            decryptor.decryptTree(uri);
            succeeded = decryptor.successfulCount();
            failed = decryptor.failedCount();
            result = decryptor.wasStopped() ? RunState.STOPPED : RunState.DONE;
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            append(t("ERROR") + "\n" + sw);
            result = RunState.FAILED;
        }
        RunState finalResult = result;
        int finalSucceeded = succeeded;
        int finalFailed = failed;
        runOnUiThread(() -> finishRun(finalResult, finalSucceeded, finalFailed));
    }

    /** Runs on the decrypt thread between files. Blocks while paused; false stops the run. */
    private boolean awaitContinue(String name, int processed) {
        currentItem = name;
        processedCount = processed;
        synchronized (pauseLock) {
            while (paused && !stopRequested) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return !stopRequested;
    }

    private void togglePause() {
        if (state != RunState.RUNNING && state != RunState.PAUSED) {
            return;
        }
        synchronized (pauseLock) {
            paused = state == RunState.RUNNING;
            pauseLock.notifyAll();
        }
        state = paused ? RunState.PAUSED : RunState.RUNNING;
        append(t(paused ? "Paused" : "Resumed"));
        refreshState();
    }

    private void requestStop() {
        if (state != RunState.RUNNING && state != RunState.PAUSED) {
            return;
        }
        synchronized (pauseLock) {
            stopRequested = true;
            paused = false;
            pauseLock.notifyAll();
        }
        state = RunState.STOPPING;
        refreshState();
    }

    private void finishRun(RunState result, int succeeded, int failed) {
        mainHandler.removeCallbacks(statusTicker);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runElapsedMs = SystemClock.elapsedRealtime() - runStartedAt;
        resultSucceeded = succeeded;
        resultFailed = failed;
        state = result;
        refreshState();
    }

    private void saveLog(Uri uri) {
        try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
            if (stream == null) {
                append(t("Failed to save log") + ": " + t("cannot open output"));
                return;
            }
            stream.write(currentLogText().getBytes(StandardCharsets.UTF_8));
            append(t("Log saved") + ": " + uri);
        } catch (Exception e) {
            append(t("Failed to save log") + ": " + e.getMessage());
        }
    }

    // ---- State rendering ------------------------------------------------------------------

    private void refreshState() {
        boolean busy = isBusy();
        idleActions.setVisibility(busy ? View.GONE : View.VISIBLE);
        runActions.setVisibility(busy ? View.VISIBLE : View.GONE);
        startButton.setEnabled(treeUri != null);
        pickButton.setText(t(treeUri == null ? "Select Folder" : "Change Folder"));
        boolean finished = state == RunState.DONE || state == RunState.STOPPED || state == RunState.FAILED;
        startButton.setText(t(finished ? "Run Again" : "Start"));

        boolean pausedNow = state == RunState.PAUSED;
        pauseButton.setText(t(pausedNow ? "Resume" : "Pause"));
        pauseButton.setIconResource(pausedNow ? R.drawable.ic_play : R.drawable.ic_pause);
        pauseButton.setEnabled(state != RunState.STOPPING);
        stopButton.setEnabled(state != RunState.STOPPING);
        progressIndicator.setVisibility(state == RunState.RUNNING || state == RunState.STOPPING
                ? View.VISIBLE : View.GONE);

        if (treeUri == null) {
            folderNameView.setText(t("No folder selected"));
            folderPathView.setText(t("Select a folder that contains your purchased Duowan resources. Output is written into a new _dw_decrypted_* folder."));
        } else {
            folderNameView.setText(folderDisplayName(treeUri));
            folderPathView.setText(folderPath(treeUri) + "\n" + t("Output goes to a new _dw_decrypted_* folder inside it"));
        }
        refreshOptions();
        updateStatus();
    }

    private void updateStatus() {
        int color = DecryptorUi.onSurface(this);
        String status;
        String detail = null;
        switch (state) {
            case RUNNING:
                status = String.format(Locale.US, t("Decrypting · %d items processed"), processedCount);
                detail = currentItem;
                color = DecryptorUi.primary(this);
                break;
            case PAUSED:
                status = String.format(Locale.US, t("Paused · %d items processed"), processedCount);
                detail = currentItem;
                break;
            case STOPPING:
                status = t("Stopping after the current file…");
                detail = currentItem;
                break;
            case DONE:
                status = String.format(Locale.US, t("Done · %d succeeded, %d failed · %s"),
                        resultSucceeded, resultFailed, formatElapsed(runElapsedMs));
                color = resultFailed > 0 ? DecryptorUi.error(this) : DecryptorUi.primary(this);
                break;
            case STOPPED:
                status = String.format(Locale.US, t("Stopped · %d succeeded, %d failed · %s"),
                        resultSucceeded, resultFailed, formatElapsed(runElapsedMs));
                break;
            case FAILED:
                status = t("Failed, see the log for details");
                color = DecryptorUi.error(this);
                break;
            default:
                status = t(treeUri == null ? "Pick a folder to begin" : "Ready");
                color = DecryptorUi.onSurfaceVariant(this);
                break;
        }
        statusView.setText(status);
        statusView.setTextColor(color);
        statusView.setVisibility(state == RunState.IDLE && treeUri == null ? View.GONE : View.VISIBLE);
        if (detail != null && isBusy()) {
            currentItemView.setText(t("Current") + ": " + detail);
            currentItemView.setVisibility(View.VISIBLE);
        } else {
            currentItemView.setVisibility(View.GONE);
        }
    }

    private void refreshOptions() {
        boolean editable = !isBusy();
        boolean repair = repairRow.toggle.isChecked();
        repairRow.setEnabled(editable);
        keepArtifactsRow.setEnabled(editable && repair);
        analyzeWalRow.setEnabled(editable);
        analyzeUserKeyRow.setEnabled(editable);
        repairModeGroup.setEnabled(editable && repair);
        modeDeleteButton.setEnabled(editable && repair);
        modeDecryptButton.setEnabled(editable && repair);
        repairModeLabel.setAlpha(editable && repair ? 1f : 0.38f);
        optionsHeader.setEnabled(editable);
        optionsHeader.setAlpha(editable ? 1f : 0.6f);

        StringBuilder summary = new StringBuilder();
        if (repair) {
            summary.append(t("Repair Map DB")).append(" · ")
                    .append(t(repairModeGroup.getCheckedButtonId() == modeDecryptButton.getId()
                            ? "Decrypt with .level.key" : "Delete mVillages"));
        } else {
            summary.append(t("No map DB repair"));
        }
        int extras = (repair && keepArtifactsRow.toggle.isChecked() ? 1 : 0)
                + (analyzeWalRow.toggle.isChecked() ? 1 : 0)
                + (analyzeUserKeyRow.toggle.isChecked() ? 1 : 0);
        if (extras > 0) {
            summary.append(" · ").append(String.format(Locale.US, t("+%d more"), extras));
        }
        optionsSummaryView.setText(summary);
        applyOptionsExpanded();
    }

    private void setOptionsExpanded(boolean expanded) {
        if (expanded && isBusy()) {
            return;
        }
        optionsExpanded = expanded;
        applyOptionsExpanded();
    }

    private void applyOptionsExpanded() {
        optionsBody.setVisibility(optionsExpanded ? View.VISIBLE : View.GONE);
        optionsChevron.setRotation(optionsExpanded ? 180f : 0f);
    }

    private String formatElapsed(long ms) {
        if (ms < 60_000) {
            return String.format(Locale.US, t("%.1f s"), ms / 1000f);
        }
        long seconds = ms / 1000;
        return String.format(Locale.US, t("%d min %d s"), seconds / 60, seconds % 60);
    }

    private void clearLog() {
        synchronized (pendingLog) {
            pendingLog.setLength(0);
        }
        logBuffer.setLength(0);
        logView.setText("");
        autoFollowLog = true;
    }

    // ---- Folder helpers -------------------------------------------------------------------

    private Uri restoreTreeUri() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, null);
        if (saved == null) {
            return null;
        }
        Uri uri = Uri.parse(saved);
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(uri) && permission.isReadPermission() && permission.isWritePermission()) {
                return uri;
            }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_TREE_URI).apply();
        return null;
    }

    private String folderDisplayName(Uri uri) {
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(this, uri);
            String name = dir == null ? null : dir.getName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (Exception ignored) {
            // Fall back to the tree path below.
        }
        String path = folderPath(uri);
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }

    private String folderPath(Uri uri) {
        try {
            String id = DocumentsContract.getTreeDocumentId(uri);
            int colon = id.indexOf(':');
            String volume = colon >= 0 ? id.substring(0, colon) : "";
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            String prefix = "primary".equals(volume) ? t("Internal storage") : volume;
            return path.isEmpty() ? prefix : prefix + "/" + path;
        } catch (Exception e) {
            return uri.toString();
        }
    }

    // ---- Log ------------------------------------------------------------------------------

    private void append(String text) {
        synchronized (pendingLog) {
            pendingLog.append(text).append('\n');
            if (logFlushScheduled) {
                return;
            }
            logFlushScheduled = true;
        }
        mainHandler.postDelayed(this::flushLog, LOG_FLUSH_DELAY_MS);
    }

    private void flushLog() {
        String chunk;
        synchronized (pendingLog) {
            chunk = pendingLog.toString();
            pendingLog.setLength(0);
            logFlushScheduled = false;
        }
        if (chunk.length() == 0) {
            return;
        }

        boolean followBottom = autoFollowLog;
        logBuffer.append(chunk);
        CharSequence visible = logView.getText();
        int excess = visible.length() + chunk.length() - MAX_VISIBLE_LOG_CHARS;
        if (excess > 0) {
            StringBuilder suffix = new StringBuilder(Math.min(MAX_VISIBLE_LOG_CHARS, visible.length() + chunk.length()));
            if (chunk.length() < MAX_VISIBLE_LOG_CHARS) {
                int trimAt = Math.min(visible.length(), excess);
                while (trimAt < visible.length() && visible.charAt(trimAt) != '\n') trimAt++;
                if (trimAt < visible.length()) trimAt++;
                suffix.append(visible.subSequence(trimAt, visible.length()));
                suffix.append(chunk);
            } else {
                int start = chunk.length() - MAX_VISIBLE_LOG_CHARS;
                while (start < chunk.length() && chunk.charAt(start) != '\n') start++;
                if (start < chunk.length()) start++;
                suffix.append(chunk.substring(start));
            }
            logView.setText(suffix);
        } else {
            logView.append(chunk);
        }
        if (followBottom) {
            scrollLogToBottom();
        }
    }

    private void scrollLogToBottom() {
        if (logScroll == null) {
            return;
        }
        programmaticLogScroll = true;
        logScroll.post(() -> {
            int scrollY = Math.max(0, logView.getMeasuredHeight() - logScroll.getHeight());
            logScroll.scrollTo(0, scrollY);
            programmaticLogScroll = false;
            autoFollowLog = isLogAtBottom();
        });
    }

    private boolean isLogAtBottom() {
        if (logScroll == null || logScroll.getChildCount() == 0) {
            return true;
        }
        View child = logScroll.getChildAt(0);
        int distance = child.getBottom() - (logScroll.getScrollY() + logScroll.getHeight());
        return distance <= 64;
    }

    private String currentLogText() {
        synchronized (pendingLog) {
            return logBuffer.toString() + pendingLog;
        }
    }

    // ---- View factories -------------------------------------------------------------------

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Fully rounded (pill) button; styleAttr 0 = filled. */
    private MaterialButton pillButton(int styleAttr, int iconRes, int heightDp) {
        MaterialButton button = styleAttr == 0
                ? new MaterialButton(this)
                : new MaterialButton(this, null, styleAttr);
        button.setCornerRadius(dp(heightDp / 2f));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(dp(heightDp));
        button.setMinimumHeight(dp(heightDp));
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setIconResource(iconRes);
        button.setIconSize(dp(18));
        button.setIconPadding(dp(8));
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        int horizontal = dp(heightDp >= 48 ? 24 : 16);
        button.setPadding(horizontal, 0, horizontal, 0);
        return button;
    }

    private MaterialButton segmentButton() {
        MaterialButton button = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setId(View.generateViewId());
        button.setCornerRadius(dp(20));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setTextSize(13);
        button.setMaxLines(2);
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(DecryptorUi.surfaceContainer(this), 24));
        card.setClipToOutline(true);
        return card;
    }

    private TextView labelText() {
        TextView label = new TextView(this);
        label.setTextSize(12);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(DecryptorUi.primary(this));
        return label;
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams actionRowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(16);
        return params;
    }

    private LinearLayout.LayoutParams spacedWeight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.leftMargin = dp(12);
        return params;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private android.graphics.drawable.Drawable rippleBackground() {
        int ripple = (DecryptorUi.onSurface(this) & 0x00ffffff) | 0x1f000000;
        return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(ripple), null,
                new android.graphics.drawable.ColorDrawable(0xffffffff));
    }

    private SwitchRow switchRow(boolean checked) {
        SwitchRow item = new SwitchRow();
        item.row = new LinearLayout(this);
        item.row.setOrientation(LinearLayout.HORIZONTAL);
        item.row.setGravity(Gravity.CENTER_VERTICAL);
        item.row.setMinimumHeight(dp(56));
        item.row.setPadding(dp(16), dp(8), dp(16), dp(8));
        item.row.setBackground(rippleBackground());
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        item.title = new TextView(this);
        item.title.setTextSize(16);
        item.title.setTextColor(DecryptorUi.onSurface(this));
        text.addView(item.title, new LinearLayout.LayoutParams(-1, -2));
        item.summary = new TextView(this);
        item.summary.setTextSize(13);
        item.summary.setTextColor(DecryptorUi.onSurfaceVariant(this));
        text.addView(item.summary, new LinearLayout.LayoutParams(-1, -2));
        item.row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        item.toggle = new MaterialSwitch(this);
        item.toggle.setChecked(checked);
        item.toggle.setClickable(false);
        item.toggle.setFocusable(false);
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(-2, -2);
        switchParams.leftMargin = dp(16);
        item.row.addView(item.toggle, switchParams);
        item.row.setOnClickListener(v -> item.toggle.toggle());
        return item;
    }

    private void savePref(String key, boolean value) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }

    private static final class SwitchRow {
        LinearLayout row;
        TextView title;
        TextView summary;
        MaterialSwitch toggle;

        void setEnabled(boolean enabled) {
            row.setEnabled(enabled);
            toggle.setEnabled(enabled);
            row.setAlpha(enabled ? 1f : 0.38f);
        }

        void setTexts(String titleText, String summaryText) {
            title.setText(titleText);
            summary.setText(summaryText);
        }
    }

    // ---- Text -----------------------------------------------------------------------------

    private boolean loadInitialLanguage() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.contains(PREF_CHINESE)) {
            return prefs.getBoolean(PREF_CHINESE, true);
        }
        return Locale.getDefault().getLanguage().toLowerCase(Locale.US).startsWith("zh");
    }

    private void updateTexts() {
        titleView.setText(t("DW Resource Decryptor"));
        languageButton.setText(chinese ? "中文" : "EN");
        languageButton.setContentDescription(t("Switch language"));
        folderLabelView.setText(t("Source folder"));
        stopButton.setText(t("Stop"));
        optionsTitleView.setText(t("Decrypt options"));
        repairRow.setTexts(t("Repair Map DB"), t("Fix map databases that contain encrypted villages"));
        repairModeLabel.setText(t("Repair mode"));
        modeDeleteButton.setText(t("Delete mVillages"));
        modeDecryptButton.setText(t("Decrypt with .level.key"));
        keepArtifactsRow.setTexts(t("Keep Repair Artifacts"), t("Also write .bak and slice files for debugging"));
        analysisLabel.setText(t("Analysis tools"));
        analyzeWalRow.setTexts(t("Analyze Map Log Decryption"), t("Write analysis reports for suspicious .log files"));
        analyzeUserKeyRow.setTexts(t("Analyze User Key"), t("Write an analysis report for .user.key"));
        logTitleView.setText(t("Log"));
        saveLogButton.setText(t("Save"));
        clearLogButton.setContentDescription(t("Clear log"));
        logView.setHint(t("Decrypt progress and results appear here"));
    }

    private String t(String english) {
        if (!chinese) {
            return english;
        }
        switch (english) {
            case "DW Resource Decryptor":
                return "多玩资源解密器";
            case "Select a folder that contains your purchased Duowan resources. Output is written into a new _dw_decrypted_* folder.":
                return "选择包含你已购买多玩资源的文件夹。输出会写入新的 _dw_decrypted_* 文件夹。";
            case "Switch language":
                return "切换语言";
            case "Source folder":
                return "源文件夹";
            case "No folder selected":
                return "尚未选择文件夹";
            case "Output goes to a new _dw_decrypted_* folder inside it":
                return "输出将写入其中新建的 _dw_decrypted_* 文件夹";
            case "Internal storage":
                return "内部存储";
            case "Select Folder":
                return "选择文件夹";
            case "Change Folder":
                return "更换文件夹";
            case "Start":
                return "开始解密";
            case "Run Again":
                return "再次解密";
            case "Pause":
                return "暂停";
            case "Resume":
                return "继续";
            case "Stop":
                return "停止";
            case "Paused":
                return "已暂停";
            case "Resumed":
                return "已继续";
            case "Pick a folder to begin":
                return "选择一个文件夹以开始";
            case "Decrypting · %d items processed":
                return "正在解密 · 已处理 %d 项";
            case "Paused · %d items processed":
                return "已暂停 · 已处理 %d 项";
            case "Stopping after the current file…":
                return "正在停止，当前文件处理完后结束…";
            case "Done · %d succeeded, %d failed · %s":
                return "已完成 · 成功 %d，失败 %d · 用时 %s";
            case "Stopped · %d succeeded, %d failed · %s":
                return "已停止 · 成功 %d，失败 %d · 用时 %s";
            case "Failed, see the log for details":
                return "出错，详情见日志";
            case "Current":
                return "当前";
            case "%.1f s":
                return "%.1f 秒";
            case "%d min %d s":
                return "%d 分 %d 秒";
            case "Decrypt options":
                return "解密选项";
            case "Repair Map DB":
                return "修复地图数据库";
            case "Fix map databases that contain encrypted villages":
                return "处理含加密 mVillages 的地图数据库";
            case "No map DB repair":
                return "不修复地图数据库";
            case "Repair mode":
                return "修复模式";
            case "Delete mVillages":
                return "删除 mVillages";
            case "Decrypt with .level.key":
                return "用 .level.key 解密";
            case "+%d more":
                return "另 %d 项";
            case "Keep Repair Artifacts":
                return "保留修复调试文件";
            case "Also write .bak and slice files for debugging":
                return "额外输出 .bak 与切片文件，便于排查";
            case "Analysis tools":
                return "分析工具";
            case "Analyze Map Log Decryption":
                return "分析地图 Log 解密";
            case "Write analysis reports for suspicious .log files":
                return "为可疑的 .log 文件输出分析报告";
            case "Analyze User Key":
                return "分析 User Key";
            case "Write an analysis report for .user.key":
                return "为 .user.key 输出分析报告";
            case "Log":
                return "日志";
            case "Save":
                return "保存";
            case "Decrypt progress and results appear here":
                return "解密进度与结果会显示在这里";
            case "Clear log":
                return "清空日志";
            case "Ready":
                return "准备就绪";
            case "Selected":
                return "已选择";
            case "Log saved":
                return "日志已保存";
            case "Failed to save log":
                return "保存日志失败";
            case "cannot open output":
                return "无法打开输出文件";
            case "ERROR":
                return "错误";
            default:
                return english;
        }
    }
}
