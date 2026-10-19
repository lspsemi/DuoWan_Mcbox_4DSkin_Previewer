package com.example.dwpreviewer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.slider.Slider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public final class MainActivity extends Activity {
    private static final int REQ_OPEN_SKIN = 2001;
    private static final int REQ_PREVIEW_SKIN = 2002;
    private static final int REQ_OPEN_FOLDER = 2003;
    private static final String MIME_DIR = "vnd.android.document/directory";
    private static final String EXTRA_INITIAL_URI = "android.provider.extra.INITIAL_URI";
    /** Shared horizontal inset for page content, cards and list rows. */
    private static final int GUTTER_DP = 16;
    private static final String STATE_FOLDER_TAB = "folder_tab_selected";
    /** Lets the switch thumb finish its slide before the activity is recreated for the new locale. */
    private static final long LANGUAGE_RECREATE_DELAY_MS = 250;

    private LinearLayout rootView;
    private LinearLayout settingsPanel;
    private TextView folderPath;
    private MaterialButton backButton;
    private MaterialButton forwardButton;
    private SkinListAdapter skinListAdapter;
    private Uri treeUri;
    private String currentDocumentId;
    private final ArrayList<String> backStack = new ArrayList<>();
    private final ArrayList<String> forwardStack = new ArrayList<>();
    private boolean previewInFront;
    private LinearLayout folderPage;
    private ScrollView settingsScroll;
    private MaterialButton folderTabButton;
    private MaterialButton actionsTabButton;
    private boolean folderTabSelected = true;
    private TextView emptyText;
    private MaterialButton emptyAction;
    private ExtendedFloatingActionButton openSkinFab;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleUtil.attach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        PreviewUi.applyDynamicColors(this);
        buildUi(savedInstanceState == null || savedInstanceState.getBoolean(STATE_FOLDER_TAB, true));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_FOLDER_TAB, folderTabSelected);
    }

    private void buildUi(boolean showFolderTab) {
        LinearLayout root = new LinearLayout(this);
        rootView = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PreviewUi.surface(this));
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText(R.string.main_title);
        title.setTextSize(24);
        title.setTextColor(PreviewUi.onSurface(this));
        title.setPadding(0, dp(8), 0, dp(8));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        folderPage = buildFolderPage();
        content.addView(folderPage, new FrameLayout.LayoutParams(-1, -1));

        settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(true);
        settingsScroll.setVerticalScrollBarEnabled(false);
        settingsScroll.addView(buildSettingsPage(), new ScrollView.LayoutParams(-1, -2));
        content.addView(settingsScroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bottomBar.setBackground(roundedFill(PreviewUi.surfaceContainer(this), 28));
        folderTabButton = createTabButton(R.string.skin_folder_tab, R.drawable.ic_folder_open);
        actionsTabButton = createTabButton(R.string.settings_tab, R.drawable.ic_settings);
        folderTabButton.setOnClickListener(v -> selectBottomTab(true));
        actionsTabButton.setOnClickListener(v -> selectBottomTab(false));
        bottomBar.addView(folderTabButton, new LinearLayout.LayoutParams(0, dp(48), 1.0f));
        LinearLayout.LayoutParams actionsTabParams = new LinearLayout.LayoutParams(0, dp(48), 1.0f);
        actionsTabParams.leftMargin = dp(4);
        bottomBar.addView(actionsTabButton, actionsTabParams);
        LinearLayout.LayoutParams bottomBarParams = new LinearLayout.LayoutParams(-1, -2);
        bottomBarParams.topMargin = dp(8);
        root.addView(bottomBar, bottomBarParams);

        setContentView(root);
        selectBottomTab(showFolderTab);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int left = insets.getSystemWindowInsetLeft();
            int top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(dp(GUTTER_DP) + left, dp(8) + top, dp(GUTTER_DP) + right, dp(8) + bottom);
            boolean keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            bottomBar.setVisibility(keyboardVisible ? View.GONE : View.VISIBLE);
            openSkinFab.setVisibility(keyboardVisible ? View.GONE : View.VISIBLE);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        restoreFolder();
    }

    private LinearLayout buildFolderPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        // Folder header: history navigation, current folder name, folder picker.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        backButton = iconButton(R.drawable.ic_arrow_back, R.string.nav_back,
                com.google.android.material.R.attr.materialIconButtonStyle);
        backButton.setOnClickListener(v -> navigateBack());
        header.addView(backButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        forwardButton = iconButton(R.drawable.ic_arrow_forward, R.string.nav_forward,
                com.google.android.material.R.attr.materialIconButtonStyle);
        forwardButton.setOnClickListener(v -> navigateForward());
        header.addView(forwardButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        folderPath = new TextView(this);
        folderPath.setTextSize(16);
        folderPath.setSingleLine(true);
        folderPath.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        folderPath.setTextColor(PreviewUi.onSurface(this));
        folderPath.setPadding(dp(8), 0, dp(8), 0);
        header.addView(folderPath, new LinearLayout.LayoutParams(0, -2, 1.0f));
        MaterialButton folderButton = iconButton(R.drawable.ic_folder_open, R.string.select_folder,
                com.google.android.material.R.attr.materialIconButtonFilledTonalStyle);
        folderButton.setOnClickListener(v -> openFolder());
        header.addView(folderButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextInputLayout searchLayout = new TextInputLayout(this);
        searchLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_FILLED);
        searchLayout.setBoxBackgroundColor(PreviewUi.color(this,
                com.google.android.material.R.attr.colorSurfaceContainerHigh, PreviewUi.surfaceContainer(this)));
        searchLayout.setBoxStrokeWidth(0);
        searchLayout.setBoxStrokeWidthFocused(0);
        searchLayout.setBoxCornerRadii(dp(28), dp(28), dp(28), dp(28));
        searchLayout.setHintEnabled(false);
        searchLayout.setStartIconDrawable(R.drawable.ic_search);
        searchLayout.setStartIconTintList(ColorStateList.valueOf(onSurfaceVariant()));
        searchLayout.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
        searchParams.topMargin = dp(8);
        page.addView(searchLayout, searchParams);
        TextInputEditText searchInput = new TextInputEditText(searchLayout.getContext());
        searchInput.setSingleLine(true);
        searchInput.setTextSize(16);
        searchInput.setHint(R.string.search_skins);
        searchInput.setMinHeight(dp(56));
        searchInput.setPadding(0, dp(14), 0, dp(14));
        searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchLayout.addView(searchInput, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout listFrame = new FrameLayout(this);
        LinearLayout.LayoutParams listFrameParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        listFrameParams.topMargin = dp(12);
        page.addView(listFrame, listFrameParams);

        ListView skinList = new ListView(this);
        skinList.setBackgroundColor(Color.TRANSPARENT);
        skinList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        skinList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        skinList.setDividerHeight(dp(6));
        // Leave room so the last row can scroll above the floating action button.
        skinList.setPadding(0, 0, 0, dp(88));
        skinList.setClipToPadding(false);
        skinList.setVerticalScrollBarEnabled(false);
        skinListAdapter = new SkinListAdapter();
        skinList.setAdapter(skinListAdapter);
        skinList.setOnItemClickListener((parent, view, position, id) -> openEntry(skinListAdapter.entry(position)));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                skinListAdapter.filter(s == null ? "" : s.toString());
                updateEmptyState();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        listFrame.addView(skinList, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout emptyView = new LinearLayout(this);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(24), 0, dp(24), dp(88));
        emptyText = new TextView(this);
        emptyText.setTextSize(15);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setTextColor(onSurfaceVariant());
        emptyView.addView(emptyText, new LinearLayout.LayoutParams(-2, -2));
        emptyAction = materialButton();
        emptyAction.setText(R.string.select_folder);
        emptyAction.setIconResource(R.drawable.ic_folder_open);
        emptyAction.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        emptyAction.setOnClickListener(v -> openFolder());
        LinearLayout.LayoutParams emptyActionParams = new LinearLayout.LayoutParams(-2, dp(48));
        emptyActionParams.topMargin = dp(16);
        emptyView.addView(emptyAction, emptyActionParams);
        listFrame.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        skinList.setEmptyView(emptyView);

        openSkinFab = new ExtendedFloatingActionButton(this);
        openSkinFab.setText(R.string.open_single_skin);
        openSkinFab.setIconResource(R.drawable.ic_file_open);
        openSkinFab.setOnClickListener(v -> openSkin());
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        fabParams.bottomMargin = dp(16);
        listFrame.addView(openSkinFab, fabParams);
        return page;
    }

    private LinearLayout buildSettingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        page.addView(sectionHeading(R.string.section_display), new LinearLayout.LayoutParams(-1, -2));
        settingsPanel = settingsCard();
        settingsPanel.addView(createTransparencyControl(), new LinearLayout.LayoutParams(-1, -2));
        settingsPanel.addView(createSwitchControl(R.string.hide_background_controls,
                PreviewSettings.hideMainBehindPreview(this),
                checked -> PreviewSettings.setHideMainBehindPreview(this, checked)), new LinearLayout.LayoutParams(-1, dp(56)));
        settingsPanel.addView(createSwitchControl(R.string.folder_preview_navigation,
                PreviewSettings.folderPreviewNavigation(this),
                checked -> PreviewSettings.setFolderPreviewNavigation(this, checked)), new LinearLayout.LayoutParams(-1, dp(56)));
        settingsPanel.addView(createSwitchControl(R.string.show_file_name,
                PreviewSettings.showFileNameInPreview(this),
                checked -> PreviewSettings.setShowFileNameInPreview(this, checked)), new LinearLayout.LayoutParams(-1, dp(56)));
        page.addView(settingsPanel, new LinearLayout.LayoutParams(-1, -2));

        page.addView(sectionHeading(R.string.section_language), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout languageCard = settingsCard();
        languageCard.addView(createSwitchControl(R.string.language_chinese, LocaleUtil.isChinese(this), checked -> {
            LocaleUtil.setChinese(this, checked);
            languageCard.postDelayed(this::recreate, LANGUAGE_RECREATE_DELAY_MS);
        }), new LinearLayout.LayoutParams(-1, dp(56)));
        page.addView(languageCard, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText(R.string.main_hint);
        hint.setTextSize(13);
        hint.setTextColor(onSurfaceVariant());
        hint.setPadding(dp(GUTTER_DP), dp(16), dp(GUTTER_DP), dp(8));
        page.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    private TextView sectionHeading(int labelRes) {
        TextView heading = new TextView(this);
        heading.setText(labelRes);
        heading.setTextSize(14);
        heading.setTextColor(PreviewUi.primary(this));
        heading.setPadding(dp(GUTTER_DP), dp(16), dp(GUTTER_DP), dp(8));
        return heading;
    }

    private LinearLayout settingsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(GUTTER_DP), dp(4), dp(GUTTER_DP), dp(4));
        card.setBackground(roundedFill(PreviewUi.surfaceContainer(this), 20));
        return card;
    }

    private MaterialButton createTabButton(int labelRes, int iconRes) {
        MaterialButton button = materialButton();
        button.setText(labelRes);
        button.setIconResource(iconRes);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setIconPadding(dp(8));
        button.setIconSize(dp(20));
        button.setTextSize(14);
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        return button;
    }

    private void selectBottomTab(boolean showFolder) {
        folderTabSelected = showFolder;
        folderPage.setVisibility(showFolder ? View.VISIBLE : View.GONE);
        settingsScroll.setVisibility(showFolder ? View.GONE : View.VISIBLE);
        styleTabButton(folderTabButton, showFolder);
        styleTabButton(actionsTabButton, !showFolder);
    }

    private void styleTabButton(MaterialButton button, boolean selected) {
        int background = selected
                ? PreviewUi.color(this, com.google.android.material.R.attr.colorSecondaryContainer, PreviewUi.primary(this))
                : Color.TRANSPARENT;
        int foreground = selected
                ? PreviewUi.color(this, com.google.android.material.R.attr.colorOnSecondaryContainer, PreviewUi.onSurface(this))
                : onSurfaceVariant();
        button.setBackgroundTintList(ColorStateList.valueOf(background));
        button.setTextColor(foreground);
        button.setIconTint(ColorStateList.valueOf(foreground));
        button.setRippleColor(ColorStateList.valueOf((foreground & 0x00ffffff) | 0x1f000000));
    }

    private GradientDrawable roundedFill(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private int onSurfaceVariant() {
        return PreviewUi.color(this, com.google.android.material.R.attr.colorOnSurfaceVariant, PreviewUi.onSurface(this));
    }

    private MaterialButton iconButton(int iconRes, int descriptionRes, int styleAttr) {
        MaterialButton button = new MaterialButton(this, null, styleAttr);
        button.setIconResource(iconRes);
        button.setContentDescription(getString(descriptionRes));
        return button;
    }

    private LinearLayout createTransparencyControl() {
        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.VERTICAL);
        control.setPadding(0, dp(12), 0, 0);
        TextView label = new TextView(this);
        label.setTextSize(16);
        label.setTextColor(PreviewUi.onSurface(this));
        int initial = PreviewSettings.backgroundTransparency(this);
        label.setText(getString(R.string.background_transparency, initial));
        control.addView(label, new LinearLayout.LayoutParams(-1, -2));
        Slider seekBar = new Slider(this);
        seekBar.setValueFrom(0f);
        seekBar.setValueTo(100f);
        seekBar.setStepSize(1f);
        seekBar.setValue(initial);
        seekBar.setTrackHeight(dp(6));
        seekBar.setThumbRadius(dp(10));
        seekBar.setHaloRadius(dp(18));
        // 100 discrete steps would otherwise draw a dense row of tick dots.
        seekBar.setTickVisible(false);
        seekBar.addOnChangeListener((slider, value, fromUser) -> {
                int progress = Math.round(value);
                PreviewSettings.setBackgroundTransparency(MainActivity.this, progress);
                label.setText(getString(R.string.background_transparency, progress));
        });
        // The slider reserves side padding for its halo; pull it out so the track lines up with the label.
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(-1, dp(48));
        int sidePadding = Math.max(0, seekBar.getTrackSidePadding() - dp(10));
        sliderParams.leftMargin = -sidePadding;
        sliderParams.rightMargin = -sidePadding;
        control.addView(seekBar, sliderParams);
        return control;
    }

    private LinearLayout createSwitchControl(int labelRes, boolean checked, BoolConsumer consumer) {
        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(16);
        label.setTextColor(PreviewUi.onSurface(this));
        label.setGravity(Gravity.CENTER_VERTICAL);
        control.addView(label, new LinearLayout.LayoutParams(0, -1, 1.0f));
        MaterialSwitch sw = new MaterialSwitch(this);
        sw.setShowText(false);
        sw.setText("");
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> consumer.accept(isChecked));
        control.addView(sw, new LinearLayout.LayoutParams(-2, -1));
        // Tapping anywhere on the row toggles the switch, as in standard settings lists.
        control.setOnClickListener(v -> sw.toggle());
        return control;
    }

    private void setSettingsExpanded(boolean expanded) {
        if (settingsPanel != null) {
            settingsPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        PreviewSettings.setMainSettingsExpanded(this, expanded);
    }

    private void openSkin() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        addInitialUri(intent);
        startActivityForResult(intent, REQ_OPEN_SKIN);
    }

    private void openFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        addInitialUri(intent);
        startActivityForResult(intent, REQ_OPEN_FOLDER);
    }

    private void addInitialUri(Intent intent) {
        String saved = PreviewSettings.lastTreeUri(this);
        if (saved != null) {
            intent.putExtra(EXTRA_INITIAL_URI, Uri.parse(saved));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PREVIEW_SKIN) {
            previewInFront = false;
            setMainContentVisible(true);
            return;
        }
        if (requestCode == REQ_OPEN_FOLDER) {
            handleFolderResult(resultCode, data);
            return;
        }
        if (requestCode != REQ_OPEN_SKIN || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launchPreview(previewIntent(uri));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!previewInFront) {
            setMainContentVisible(true);
        }
    }

    private void setMainContentVisible(boolean visible) {
        if (rootView != null) {
            rootView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void handleFolderResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        getContentResolver().takePersistableUriPermission(uri,
                data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        treeUri = uri;
        currentDocumentId = DocumentsContract.getTreeDocumentId(uri);
        backStack.clear();
        forwardStack.clear();
        PreviewSettings.setLastFolder(this, treeUri.toString(), currentDocumentId);
        loadCurrentFolder();
    }

    private void restoreFolder() {
        String savedTree = PreviewSettings.lastTreeUri(this);
        String savedDoc = PreviewSettings.lastDocumentId(this);
        if (savedTree == null) {
            updateFolderUi(getString(R.string.no_folder_selected), new ArrayList<>());
            return;
        }
        treeUri = Uri.parse(savedTree);
        currentDocumentId = savedDoc == null ? DocumentsContract.getTreeDocumentId(treeUri) : savedDoc;
        loadCurrentFolder();
    }

    private void openEntry(BrowserEntry entry) {
        if (entry == null) {
            return;
        }
        if (entry.directory) {
            backStack.add(currentDocumentId);
            forwardStack.clear();
            currentDocumentId = entry.documentId;
            PreviewSettings.setLastFolder(this, treeUri.toString(), currentDocumentId);
            loadCurrentFolder();
            return;
        }
        Intent intent = previewIntent(entry.uri);
        intent.putExtra(FourDSkinPreviewActivity.EXTRA_FILE_NAME, entry.name);
        if (PreviewSettings.folderPreviewNavigation(this)) {
            ArrayList<String> skinUris = skinListAdapter.skinUris();
            ArrayList<String> skinNames = skinListAdapter.skinNames();
            if (skinUris.size() > 1) {
                intent.putStringArrayListExtra(FourDSkinPreviewActivity.EXTRA_FOLDER_SKIN_URIS, skinUris);
                intent.putStringArrayListExtra(FourDSkinPreviewActivity.EXTRA_FOLDER_SKIN_NAMES, skinNames);
                intent.putExtra(FourDSkinPreviewActivity.EXTRA_FOLDER_SKIN_INDEX, skinUris.indexOf(entry.uri.toString()));
            }
        }
        launchPreview(intent);
    }

    private void navigateBack() {
        if (backStack.isEmpty()) return;
        forwardStack.add(currentDocumentId);
        currentDocumentId = backStack.remove(backStack.size() - 1);
        PreviewSettings.setLastFolder(this, treeUri.toString(), currentDocumentId);
        loadCurrentFolder();
    }

    private void navigateForward() {
        if (forwardStack.isEmpty()) return;
        backStack.add(currentDocumentId);
        currentDocumentId = forwardStack.remove(forwardStack.size() - 1);
        PreviewSettings.setLastFolder(this, treeUri.toString(), currentDocumentId);
        loadCurrentFolder();
    }

    private void loadCurrentFolder() {
        if (treeUri == null || currentDocumentId == null) {
            updateFolderUi(getString(R.string.no_folder_selected), new ArrayList<>());
            return;
        }
        ArrayList<BrowserEntry> entries = new ArrayList<>();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocumentId);
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                while (cursor.moveToNext()) {
                    String documentId = cursor.getString(idCol);
                    String name = cursor.getString(nameCol);
                    String mime = cursor.getString(mimeCol);
                    boolean directory = MIME_DIR.equals(mime);
                    if (!directory && !isSupportedSkinFile(name)) continue;
                    entries.add(new BrowserEntry(name == null ? "" : name, documentId,
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId), directory));
                }
            }
            Collections.sort(entries, (left, right) -> {
                if (left.directory != right.directory) return left.directory ? -1 : 1;
                return left.name.compareToIgnoreCase(right.name);
            });
            updateFolderUi(displayNameForCurrentFolder(), entries);
        } catch (Throwable t) {
            updateFolderUi(getString(R.string.folder_load_failed, t.getMessage()), new ArrayList<>());
        }
    }

    private boolean isSupportedSkinFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".png") || lower.endsWith(".zip") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    private String displayNameForCurrentFolder() {
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocumentId);
        try (Cursor cursor = getContentResolver().query(documentUri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable ignored) {
        }
        String treeDoc = DocumentsContract.getTreeDocumentId(treeUri);
        if (treeDoc.equals(currentDocumentId)) return getString(R.string.current_folder_root);
        int index = Math.max(currentDocumentId.lastIndexOf('/'), currentDocumentId.lastIndexOf(':'));
        return index >= 0 && index + 1 < currentDocumentId.length() ? currentDocumentId.substring(index + 1) : currentDocumentId;
    }

    private void updateFolderUi(String path, List<BrowserEntry> entries) {
        if (folderPath != null) folderPath.setText(path);
        if (backButton != null) backButton.setEnabled(!backStack.isEmpty());
        if (forwardButton != null) forwardButton.setEnabled(!forwardStack.isEmpty());
        if (skinListAdapter != null) skinListAdapter.setEntries(entries);
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (emptyText == null || skinListAdapter == null) return;
        if (treeUri == null) {
            emptyText.setText(R.string.empty_no_folder);
            emptyAction.setVisibility(View.VISIBLE);
        } else if (skinListAdapter.hasAnyEntries()) {
            emptyText.setText(R.string.no_matching_entries);
            emptyAction.setVisibility(View.GONE);
        } else {
            emptyText.setText(R.string.empty_folder);
            emptyAction.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (!folderTabSelected) {
            selectBottomTab(true);
            return;
        }
        if (!backStack.isEmpty()) {
            navigateBack();
            return;
        }
        super.onBackPressed();
    }

    private Intent previewIntent(Uri uri) {
        Intent intent = new Intent(this, FourDSkinPreviewActivity.class);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private void launchPreview(Intent intent) {
        previewInFront = true;
        setMainContentVisible(!PreviewSettings.hideMainBehindPreview(this));
        startActivityForResult(intent, REQ_PREVIEW_SKIN);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private MaterialButton materialButton() {
        MaterialButton button = new MaterialButton(this);
        button.setCornerRadius(dp(24));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private interface BoolConsumer {
        void accept(boolean value);
    }

    private static final class BrowserEntry {
        final String name;
        final String documentId;
        final Uri uri;
        final boolean directory;

        BrowserEntry(String name, String documentId, Uri uri, boolean directory) {
            this.name = name;
            this.documentId = documentId;
            this.uri = uri;
            this.directory = directory;
        }
    }

    private final class SkinListAdapter extends BaseAdapter {
        private final ArrayList<BrowserEntry> entries = new ArrayList<>();
        private final ArrayList<BrowserEntry> allEntries = new ArrayList<>();
        private String query = "";

        void setEntries(List<BrowserEntry> newEntries) {
            allEntries.clear();
            allEntries.addAll(newEntries);
            applyFilter();
        }

        void filter(String value) {
            query = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            applyFilter();
        }

        private void applyFilter() {
            entries.clear();
            for (BrowserEntry entry : allEntries) {
                if (query.isEmpty() || entry.name.toLowerCase(Locale.ROOT).contains(query)) entries.add(entry);
            }
            notifyDataSetChanged();
        }

        BrowserEntry entry(int position) {
            return position >= 0 && position < entries.size() ? entries.get(position) : null;
        }

        ArrayList<String> skinUris() {
            ArrayList<String> uris = new ArrayList<>();
            for (BrowserEntry entry : entries) if (!entry.directory) uris.add(entry.uri.toString());
            return uris;
        }

        ArrayList<String> skinNames() {
            ArrayList<String> names = new ArrayList<>();
            for (BrowserEntry entry : entries) if (!entry.directory) names.add(entry.name);
            return names;
        }

        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        boolean hasAnyEntries() {
            return !allEntries.isEmpty();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowViews views = convertView != null ? (RowViews) convertView.getTag() : createRow();
            BrowserEntry entry = entries.get(position);
            views.name.setText(entry.name);
            views.icon.setImageResource(entry.directory ? R.drawable.ic_folder : R.drawable.ic_skin);
            views.icon.setImageTintList(ColorStateList.valueOf(entry.directory
                    ? PreviewUi.primary(MainActivity.this) : onSurfaceVariant()));
            views.chevron.setVisibility(entry.directory ? View.VISIBLE : View.GONE);
            return views.row;
        }

        private RowViews createRow() {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(GUTTER_DP), 0, dp(12), 0);
            GradientDrawable shape = roundedFill(PreviewUi.surfaceContainer(MainActivity.this), 16);
            GradientDrawable mask = roundedFill(Color.WHITE, 16);
            int ripple = (PreviewUi.onSurface(MainActivity.this) & 0x00ffffff) | 0x1f000000;
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape, mask));
            row.setLayoutParams(new ListView.LayoutParams(-1, dp(56)));

            ImageView icon = new ImageView(MainActivity.this);
            row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView name = new TextView(MainActivity.this);
            name.setTextSize(16);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setTextColor(PreviewUi.onSurface(MainActivity.this));
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
            nameParams.leftMargin = dp(16);
            nameParams.rightMargin = dp(8);
            row.addView(name, nameParams);
            ImageView chevron = new ImageView(MainActivity.this);
            chevron.setImageResource(R.drawable.ic_chevron_right);
            chevron.setImageTintList(ColorStateList.valueOf(onSurfaceVariant()));
            row.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(24)));

            RowViews views = new RowViews(row, icon, name, chevron);
            row.setTag(views);
            return views;
        }
    }

    private static final class RowViews {
        final View row;
        final ImageView icon;
        final TextView name;
        final ImageView chevron;

        RowViews(View row, ImageView icon, TextView name, ImageView chevron) {
            this.row = row;
            this.icon = icon;
            this.name = name;
            this.chevron = chevron;
        }
    }
}
