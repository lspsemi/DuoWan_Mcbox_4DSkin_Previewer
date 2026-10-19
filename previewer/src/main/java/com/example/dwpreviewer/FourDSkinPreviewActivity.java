package com.example.dwpreviewer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Build;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import java.io.OutputStream;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

public final class FourDSkinPreviewActivity extends Activity {
    private static final int REQ_SAVE_RENDER = 7402;
    private static final int EXPORT_SIZE = 2048;
    private static final int REQ_SAVE_FACES_FOLDER = 7403;
    private static final int EXPORT_ISOMETRIC = 0;
    private static final int EXPORT_FACES_SEPARATE = 1;
    private static final int EXPORT_FACES_COMBINED = 2;
    private int pendingExportMode = EXPORT_ISOMETRIC;
    private static final String[] FACE_NAMES = {"front", "back", "left", "right", "top", "bottom"};
    public static final String EXTRA_FOLDER_SKIN_URIS = "com.example.dwpreviewer.FOLDER_SKIN_URIS";
    public static final String EXTRA_FOLDER_SKIN_NAMES = "com.example.dwpreviewer.FOLDER_SKIN_NAMES";
    public static final String EXTRA_FOLDER_SKIN_INDEX = "com.example.dwpreviewer.FOLDER_SKIN_INDEX";
    public static final String EXTRA_FILE_NAME = "com.example.dwpreviewer.FILE_NAME";

    private PreviewSurface surface;
    private FourDSkinRenderer renderer;
    private ArrayList<String> folderSkinUris;
    private ArrayList<String> folderSkinNames;
    private int folderSkinIndex = -1;
    private int loadGeneration;
    private String currentFileName;
    private float lastPinchDistance;
    private float lastPinchCenterX;
    private float lastPinchCenterY;
    private SkinPreviewData currentData;
    private boolean currentSlimArms;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleUtil.attach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreviewUi.applyDynamicColors(this);
        Window window = getWindow();
        window.getDecorView().setSystemUiVisibility(window.getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        folderSkinUris = getIntent().getStringArrayListExtra(EXTRA_FOLDER_SKIN_URIS);
        folderSkinNames = getIntent().getStringArrayListExtra(EXTRA_FOLDER_SKIN_NAMES);
        folderSkinIndex = getIntent().getIntExtra(EXTRA_FOLDER_SKIN_INDEX, -1);
        Uri uri = getIntent().getData();
        if (uri == null) {
            showError(getString(R.string.error_no_skin_file));
            return;
        }
        currentFileName = initialFileName(uri);
        loadAsync(uri);
    }

    private void loadAsync(Uri uri) {
        int generation = ++loadGeneration;
        showLoading();
        new Thread(() -> load(uri, generation)).start();
    }

    private void load(Uri uri, int generation) {
        try {
            SkinPreviewData data = SkinPackageLoader.load(this, uri);
            runOnUiThread(() -> {
                if (generation == loadGeneration) showPreview(data);
            });
        } catch (Throwable t) {
            runOnUiThread(() -> {
                if (generation == loadGeneration) showError(t.getMessage() == null ? t.toString() : t.getMessage());
            });
        }
    }

    private void showLoading() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(withAlpha(PreviewUi.surface(this), PreviewSettings.backgroundAlpha(this)));
        root.setClickable(true);
        ProgressBar progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(-2, -2);
        progressParams.gravity = Gravity.CENTER;
        root.addView(progress, progressParams);
        setContentView(root);
    }

    private void showPreview(SkinPreviewData data) {
        currentData = data;
        currentSlimArms = data.slimArms;
        renderer = new FourDSkinRenderer(data, PreviewSettings.backgroundAlphaFloat(this));
        surface = new PreviewSurface(this);
        surface.setZOrderMediaOverlay(true);
        surface.setEGLContextClientVersion(1);
        surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        surface.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        surface.setRenderer(renderer);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x00000000);
        root.setClickable(true);
        root.startAnimation(AnimationUtils.loadAnimation(this, R.anim.scale_in_activity));
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        TextView status = new TextView(this);
        status.setText(statusTextRes(data));
        status.setTextColor(PreviewUi.onPrimary(this));
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setMaxWidth(dp(260));
        status.setBackground(roundedLabel(withAlpha(PreviewUi.primary(this), 0xdd)));
        status.setPadding(dp(14), dp(7), dp(14), dp(7));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-2, dp(34));
        statusParams.gravity = Gravity.TOP | Gravity.LEFT;
        statusParams.leftMargin = dp(12);
        statusParams.topMargin = dp(78);
        root.addView(status, statusParams);

        if (data.standardSkin) addModelSwitch(root);
        addSaveRenderButton(root);
        if (canSwitchFolderSkins()) addFolderSwitchButtons(root);
        if (PreviewSettings.showFileNameInPreview(this) && !TextUtils.isEmpty(currentFileName)) {
            TextView fileName = createFileNameLabel(currentFileName);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, dp(34));
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.topMargin = dp(120);
            params.leftMargin = dp(18);
            params.rightMargin = dp(18);
            root.addView(fileName, params);
        }

        LinearLayout language = createLanguageControl();
        language.setBackground(roundedControl(withAlpha(PreviewUi.surfaceContainer(this), 0xe8), dp(18)));
        FrameLayout.LayoutParams languageParams = new FrameLayout.LayoutParams(dp(84), dp(38));
        languageParams.gravity = Gravity.TOP | Gravity.RIGHT;
        languageParams.topMargin = dp(78);
        languageParams.rightMargin = dp(54);
        root.addView(language, languageParams);

        MaterialButton close = new MaterialButton(this);
        close.setText("×");
        close.setContentDescription(getString(R.string.close));
        close.setTextColor(PreviewUi.onPrimary(this));
        close.setTextSize(24);
        close.setGravity(Gravity.CENTER);
        close.setCornerRadius(dp(22));
        close.setInsetTop(0);
        close.setInsetBottom(0);
        close.setMinWidth(0);
        close.setMinimumWidth(0);
        close.setPadding(0, 0, 0, 0);
        close.setBackgroundTintList(android.content.res.ColorStateList.valueOf(withAlpha(PreviewUi.primary(this), 0xee)));
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(44), dp(44));
        closeParams.gravity = Gravity.TOP | Gravity.RIGHT;
        closeParams.topMargin = dp(75);
        closeParams.rightMargin = dp(8);
        root.addView(close, closeParams);
        close.bringToFront();
        setContentView(root);
    }

    private void addSaveRenderButton(FrameLayout root) {
        MaterialButton save = new MaterialButton(this);
        save.setText(R.string.save_render);
        save.setTextColor(PreviewUi.onPrimary(this));
        save.setTextSize(14);
        save.setGravity(Gravity.CENTER);
        save.setCornerRadius(dp(22));
        save.setInsetTop(0);
        save.setInsetBottom(0);
        save.setBackgroundTintList(android.content.res.ColorStateList.valueOf(withAlpha(PreviewUi.primary(this), 0xee)));
        save.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.save_render)
                .setItems(new String[]{getString(R.string.export_isometric), getString(R.string.export_six_separate), getString(R.string.export_six_combined)}, (dialog, which) -> {
                    pendingExportMode = which;
                    if (which == EXPORT_FACES_SEPARATE) {
                        Intent folder = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        startActivityForResult(folder, REQ_SAVE_FACES_FOLDER);
                    } else beginSave(which == EXPORT_FACES_COMBINED ? exportBaseName() + "_six_faces.png" : exportFileName());
                }).show());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, dp(44));
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = currentData != null && currentData.standardSkin ? dp(126) : dp(70);
        save.setPadding(dp(18), 0, dp(18), 0);
        root.addView(save, params);
    }

    private void beginSave(String title) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_TITLE, title);
        startActivityForResult(intent, REQ_SAVE_RENDER);
    }

    private String exportFileName() {
        return exportBaseName() + "_isometric.png";
    }

    private String exportBaseName() {
        String base = currentFileName == null ? "skin" : currentFileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode == REQ_SAVE_FACES_FOLDER && resultCode == RESULT_OK && result != null && result.getData() != null) {
            Uri folder = result.getData();
            getContentResolver().takePersistableUriPermission(folder, result.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            exportSeparateFaces(folder);
            return;
        }
        if (requestCode != REQ_SAVE_RENDER || resultCode != RESULT_OK || result == null || result.getData() == null) return;
        Uri destination = result.getData();
        SkinPreviewData data = currentData;
        boolean slim = currentSlimArms;
        if (data == null) return;
        if (data.standardSkin) {
            data = new SkinPreviewData(data.texture, SkinPackageLoader.standardModel(data.textureHeight, slim), data.sourceName,
                    data.encrypted, true, slim, data.sourceType);
        }
        SkinPreviewData exportData = data;
        Toast.makeText(this, R.string.render_saving, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                if (pendingExportMode == EXPORT_FACES_COMBINED) bitmap = renderCombinedFaces(exportData);
                else bitmap = FourDSkinRenderer.renderIsometric(exportData, EXPORT_SIZE, EXPORT_SIZE);
                try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("PNG write failed");
                }
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(this, R.string.render_saved, Toast.LENGTH_SHORT).show());
            } catch (Throwable error) {
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(this, getString(R.string.render_save_failed) + ": " + error.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
        }, "skin-render-export").start();
    }

    private Bitmap renderCombinedFaces(SkinPreviewData data) {
        int tile = EXPORT_SIZE / 2;
        Bitmap sheet = Bitmap.createBitmap(tile * 3, tile * 2, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(sheet);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        for (int face = 0; face < 6; face++) {
            Bitmap tileBitmap = FourDSkinRenderer.renderFace(data, tile, tile, face);
            canvas.drawBitmap(tileBitmap, (face % 3) * tile, (face / 3) * tile, paint);
            tileBitmap.recycle();
        }
        return sheet;
    }

    private void exportSeparateFaces(Uri folder) {
        SkinPreviewData data = currentData;
        if (data == null) return;
        if (data.standardSkin) data = new SkinPreviewData(data.texture, SkinPackageLoader.standardModel(data.textureHeight, currentSlimArms), data.sourceName,
                data.encrypted, true, currentSlimArms, data.sourceType);
        SkinPreviewData exportData = data;
        Toast.makeText(this, R.string.render_saving, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                for (int face = 0; face < FACE_NAMES.length; face++) {
                    Bitmap bitmap = FourDSkinRenderer.renderFace(exportData, EXPORT_SIZE, EXPORT_SIZE, face);
                    try {
                        Uri file = DocumentsContract.createDocument(getContentResolver(), folder, "image/png", exportBaseName() + "_" + FACE_NAMES[face] + ".png");
                        if (file == null) throw new IllegalStateException("Cannot create output file");
                        try (OutputStream output = getContentResolver().openOutputStream(file, "w")) {
                            if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("PNG write failed");
                        }
                    } finally { bitmap.recycle(); }
                }
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(this, R.string.render_saved, Toast.LENGTH_SHORT).show());
            } catch (Throwable error) {
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(this, getString(R.string.render_save_failed) + ": " + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "skin-faces-export").start();
    }

    private void addModelSwitch(FrameLayout root) {
        LinearLayout modelSwitch = new LinearLayout(this);
        modelSwitch.setOrientation(LinearLayout.HORIZONTAL);
        modelSwitch.setGravity(Gravity.CENTER);
        modelSwitch.setBackground(roundedControl(withAlpha(PreviewUi.surfaceContainer(this), 0xe8), dp(6)));
        modelSwitch.setPadding(dp(6), dp(6), dp(6), dp(6));
        MaterialButton steve = createModelButton(R.string.model_steve);
        MaterialButton alex = createModelButton(R.string.model_alex);
        modelSwitch.addView(steve, new LinearLayout.LayoutParams(dp(104), dp(40)));
        LinearLayout.LayoutParams alexParams = new LinearLayout.LayoutParams(dp(104), dp(40));
        alexParams.leftMargin = dp(4);
        modelSwitch.addView(alex, alexParams);
        updateModelButtons(steve, alex, false);
        steve.setOnClickListener(v -> {
            currentSlimArms = false;
            updateModelButtons(steve, alex, false);
            surface.queueEvent(() -> renderer.useStandardModel(false));
        });
        alex.setOnClickListener(v -> {
            currentSlimArms = true;
            updateModelButtons(steve, alex, true);
            surface.queueEvent(() -> renderer.useStandardModel(true));
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(70);
        root.addView(modelSwitch, params);
    }

    private void addFolderSwitchButtons(FrameLayout root) {
        TextView previous = createSkinSwitchButton(R.string.previous_skin);
        previous.setOnClickListener(v -> switchFolderSkin(-1));
        FrameLayout.LayoutParams previousParams = new FrameLayout.LayoutParams(dp(56), dp(56));
        previousParams.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        previousParams.leftMargin = dp(10);
        root.addView(previous, previousParams);
        TextView next = createSkinSwitchButton(R.string.next_skin);
        next.setOnClickListener(v -> switchFolderSkin(1));
        FrameLayout.LayoutParams nextParams = new FrameLayout.LayoutParams(dp(56), dp(56));
        nextParams.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        nextParams.rightMargin = dp(10);
        root.addView(next, nextParams);
    }

    private void showError(String message) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(withAlpha(PreviewUi.surface(this), PreviewSettings.backgroundAlpha(this)));
        root.setClickable(true);
        TextView error = new TextView(this);
        error.setText(getString(R.string.preview_failed) + "\n" + message);
        error.setTextColor(PreviewUi.onSurface(this));
        error.setTextSize(16);
        error.setGravity(Gravity.CENTER);
        error.setPadding(dp(24), dp(24), dp(24), dp(24));
        error.setOnClickListener(v -> finish());
        root.addView(error, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout language = createLanguageControl();
        language.setBackground(roundedControl(withAlpha(PreviewUi.surfaceContainer(this), 0xe8), dp(18)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(84), dp(38));
        params.gravity = Gravity.TOP | Gravity.RIGHT;
        params.topMargin = dp(12);
        params.rightMargin = dp(12);
        root.addView(language, params);
        setContentView(root);
    }

    private int statusTextRes(SkinPreviewData data) {
        if (data.sourceType == SkinPreviewData.SOURCE_INTERNATIONAL_DUOWAN) return R.string.status_international_4d;
        if (data.standardSkin) return data.encrypted ? R.string.status_encrypted_standard : R.string.status_plain_standard;
        return data.encrypted ? R.string.status_encrypted_4d : R.string.status_plain_4d;
    }

    private MaterialButton createModelButton(int textRes) {
        MaterialButton button = new MaterialButton(this);
        button.setText(textRes);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(14);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setCornerRadius(dp(4));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private void updateModelButtons(MaterialButton steve, MaterialButton alex, boolean slimArms) {
        styleModelButton(steve, !slimArms);
        styleModelButton(alex, slimArms);
    }

    private void styleModelButton(MaterialButton button, boolean selected) {
        button.setTextColor(selected ? PreviewUi.onPrimary(this) : PreviewUi.onSurface(this));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected ? withAlpha(PreviewUi.primary(this), 0xee) : PreviewUi.surfaceContainer(this)));
    }

    private TextView createSkinSwitchButton(int textRes) {
        TextView button = new TextView(this);
        button.setText(textRes);
        button.setTextColor(PreviewUi.onSurface(this));
        button.setTextSize(32);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setBackground(roundedControl(withAlpha(PreviewUi.surfaceContainer(this), 0xe8), dp(28)));
        return button;
    }

    private TextView createFileNameLabel(String name) {
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(PreviewUi.onSurface(this));
        label.setTextSize(13);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        label.setMaxWidth(dp(280));
        label.setPadding(dp(14), dp(7), dp(14), dp(7));
        label.setBackground(roundedControl(withAlpha(PreviewUi.surfaceContainer(this), 0xe8), dp(17)));
        label.setClickable(true);
        label.setOnClickListener(v -> copyFileName(name));
        return label;
    }

    private void copyFileName(String name) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("skin file", name));
            Toast.makeText(this, R.string.file_name_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean canSwitchFolderSkins() {
        return folderSkinUris != null && folderSkinUris.size() > 1
                && folderSkinIndex >= 0 && folderSkinIndex < folderSkinUris.size();
    }

    private void switchFolderSkin(int direction) {
        if (!canSwitchFolderSkins()) return;
        folderSkinIndex = (folderSkinIndex + direction + folderSkinUris.size()) % folderSkinUris.size();
        Uri uri = Uri.parse(folderSkinUris.get(folderSkinIndex));
        currentFileName = folderSkinName(folderSkinIndex, uri);
        loadAsync(uri);
    }

    private String initialFileName(Uri uri) {
        String extra = getIntent().getStringExtra(EXTRA_FILE_NAME);
        return TextUtils.isEmpty(extra) ? displayNameForUri(uri) : extra;
    }

    private String folderSkinName(int index, Uri uri) {
        if (folderSkinNames != null && index >= 0 && index < folderSkinNames.size()
                && !TextUtils.isEmpty(folderSkinNames.get(index))) {
            return folderSkinNames.get(index);
        }
        return displayNameForUri(uri);
    }

    private String displayNameForUri(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (!TextUtils.isEmpty(name)) return name;
            }
        } catch (Throwable ignored) {
        }
        String path = uri.getLastPathSegment();
        if (path == null) return "";
        int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'));
        return index >= 0 && index + 1 < path.length() ? path.substring(index + 1) : path;
    }

    private GradientDrawable roundedLabel(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(dp(1), 0x33ffffff);
        return drawable;
    }

    private GradientDrawable roundedControl(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), 0x44ffffff);
        return drawable;
    }

    private LinearLayout createLanguageControl() {
        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setPadding(dp(4), 0, dp(4), 0);
        TextView label = new TextView(this);
        boolean chinese = LocaleUtil.isChinese(this);
        label.setText(chinese ? R.string.language_zh_short : R.string.language_en_short);
        label.setTextColor(PreviewUi.onSurface(this));
        label.setTextSize(14);
        label.setGravity(Gravity.CENTER);
        control.addView(label, new LinearLayout.LayoutParams(dp(26), -1));
        MaterialSwitch language = new MaterialSwitch(this);
        language.setShowText(false);
        language.setText("");
        language.setChecked(chinese);
        language.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LocaleUtil.setChinese(this, isChecked);
            recreate();
        });
        control.addView(language, new LinearLayout.LayoutParams(dp(50), -1));
        return control;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (surface != null) surface.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surface != null) surface.onResume();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, R.anim.scale_out_activity);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | (alpha << 24);
    }

    private final class PreviewSurface extends GLSurfaceView implements GestureDetector.OnDoubleTapListener, GestureDetector.OnGestureListener {
        private final GestureDetector gestures;
        private float lastX;
        private float lastY;

        PreviewSurface(Activity activity) {
            super(activity);
            gestures = new GestureDetector(activity, this);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            gestures.onTouchEvent(event);
            if (renderer == null) return false;
            int action = event.getActionMasked();
            int pointers = event.getPointerCount();
            if (action == MotionEvent.ACTION_DOWN) {
                lastX = event.getX();
                lastY = event.getY();
            } else if (action == MotionEvent.ACTION_POINTER_DOWN && pointers == 2) {
                lastPinchDistance = distance(event);
                lastPinchCenterX = centerX(event);
                lastPinchCenterY = centerY(event);
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (pointers == 1) {
                    float x = event.getX();
                    float y = event.getY();
                    float dx = Math.abs(x - lastX) > 1.0f ? (x > lastX ? 4.0f : -4.0f) : 0.0f;
                    float dy = Math.abs(y - lastY) > 1.0f ? (y > lastY ? 4.0f : -4.0f) : 0.0f;
                    lastX = x;
                    lastY = y;
                    queueEvent(() -> renderer.rotateBy(dx, dy));
                } else if (pointers == 2) {
                    float now = distance(event);
                    float cx = centerX(event);
                    float cy = centerY(event);
                    if (lastPinchDistance > 0.0f) {
                        float factor = Math.max(0.5f, Math.min(1.5f, now / lastPinchDistance));
                        float panDx = cx - lastPinchCenterX;
                        float panDy = cy - lastPinchCenterY;
                        queueEvent(() -> {
                            renderer.zoomBy(factor);
                            renderer.panBy(panDx, panDy);
                        });
                    }
                    lastPinchDistance = now;
                    lastPinchCenterX = cx;
                    lastPinchCenterY = cy;
                }
            }
            return true;
        }

        private float distance(MotionEvent event) {
            float x = event.getX(0) - event.getX(1);
            float y = event.getY(0) - event.getY(1);
            return (float) Math.sqrt((x * x) + (y * y));
        }

        private float centerX(MotionEvent event) {
            return (event.getX(0) + event.getX(1)) * 0.5f;
        }

        private float centerY(MotionEvent event) {
            return (event.getY(0) + event.getY(1)) * 0.5f;
        }

        @Override public boolean onDoubleTap(MotionEvent e) {
            queueEvent(() -> renderer.resetView());
            return true;
        }
        @Override public boolean onDoubleTapEvent(MotionEvent e) { return false; }
        @Override public boolean onSingleTapConfirmed(MotionEvent e) { return false; }
        @Override public boolean onDown(MotionEvent e) { return true; }
        @Override public void onShowPress(MotionEvent e) {}
        @Override public boolean onSingleTapUp(MotionEvent e) { return false; }
        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) { return false; }
        @Override public void onLongPress(MotionEvent e) {}
        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) { return false; }
    }
}
