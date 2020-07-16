/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.acmi.sliceviewer;

import android.app.Activity;
import android.content.ContentProviderClient;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.RadioButton;
import androidx.lifecycle.LiveData;
import androidx.slice.Slice;
import androidx.slice.widget.SliceLiveData;
import androidx.slice.widget.SliceView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * This app lets users view the slices that are installed on the system.
 *
 * <p>In a very simple UI, users can specify slice authority (auto-completed based on the available
 * ContentProviders) and slice path, as well as toggle between three slice display mode (large,
 * small and shortcut). Below these input fields, the slice will be inflated and continuously
 * updated.</p>
 */
public class MainActivity extends Activity {
  private static final String TAG = "sliceviewer";

  private static final int SLICE_DEFAULT_MODE = SliceView.MODE_LARGE;
  private static final int AUTHORITIES_REFRESH_PERIOD_S = 5;

  private final ScheduledExecutorService scheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "authorities-refresh"));

  private ArrayAdapterContains<String> authoritiesListAdapter;
  private SliceView sliceView;
  private ScheduledFuture<?> refreshAuthoritiesTask;

  private String authority;
  private String path;

  private final Object liveDataLock = new Object();
  private LiveData<Slice> liveData;

  @Override
  protected void onStart() {
    super.onStart();

    // Reset authority / path.
    authority = "";
    path = "";

    // Make the navigation bar transparent
    getWindow()
        .setFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
    int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
    getWindow().getDecorView().setSystemUiVisibility(uiOptions);

    // Set layout and find inflated objects
    setContentView(R.layout.activity_main);

    AutoCompleteTextView autoCompleteTextView = findViewById(R.id.authorityTextView);
    EditText pathTextView = findViewById(R.id.pathTextView);
    sliceView = findViewById(R.id.sliceView);
    RadioButton radioButtonLarge = findViewById(R.id.sliceModeLarge);
    RadioButton radioButtonSmall = findViewById(R.id.sliceModeSmall);
    RadioButton radioButtonShortcut = findViewById(R.id.sliceModeShortcut);

    // Set listeners on text views. Since we simply want to trigger actions when the text is
    // changed, we only care about the onTextChanged callback.
    autoCompleteTextView.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int before, int count) {
        // Nothing to do.
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        authority = s.toString();
        tryDisplaySlice();
      }

      @Override
      public void afterTextChanged(Editable s) {
        // Nothing to do.
      }
    });

    pathTextView.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int before, int count) {
        // Nothing to do.
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        path = s.toString();
        tryDisplaySlice();
      }

      @Override
      public void afterTextChanged(Editable s) {
        // Nothing to do.
      }
    });

    // Set listeners on radio buttons.
    radioButtonLarge.setOnCheckedChangeListener((buttonView, isChecked) -> {
      if (isChecked) {
        sliceView.setMode(SliceView.MODE_LARGE);
      }
    });

    radioButtonSmall.setOnCheckedChangeListener((buttonView, isChecked) -> {
      if (isChecked) {
        sliceView.setMode(SliceView.MODE_SMALL);
      }
    });

    radioButtonShortcut.setOnCheckedChangeListener((buttonView, isChecked) -> {
      if (isChecked) {
        sliceView.setMode(SliceView.MODE_SHORTCUT);
      }
    });

    // Check initial radio button.
    switch (SLICE_DEFAULT_MODE) {
      case SliceView.MODE_LARGE:
        radioButtonLarge.setChecked(true);
        break;
      case SliceView.MODE_SMALL:
        radioButtonSmall.setChecked(true);
        break;
      case SliceView.MODE_SHORTCUT:
        radioButtonShortcut.setChecked(true);
        break;
    }

    // Set initial mode in SliceView.
    sliceView.setMode(SLICE_DEFAULT_MODE);

    authoritiesListAdapter = new ArrayAdapterContains<>(
        /* context */ this,
        android.R.layout.simple_dropdown_item_1line,
        new ArrayList<>());

    autoCompleteTextView.setAdapter(authoritiesListAdapter);

    // Periodically refresh authorities.
    refreshAuthoritiesTask = scheduledExecutorService.scheduleAtFixedRate(
        this::refreshAuthorities,
        /* initialDelay */ 0,
        /* period */ AUTHORITIES_REFRESH_PERIOD_S,
        TimeUnit.SECONDS);
  }

  @Override
  protected void onStop() {
    super.onStop();

    if (refreshAuthoritiesTask != null) {
      refreshAuthoritiesTask.cancel(false);
      refreshAuthoritiesTask = null;
    }
  }

  private void tryDisplaySlice() {
    // Reset slice.
    sliceView.setSlice(null);

    Uri previewParametersUri = new Uri.Builder()
        .scheme("content")
        .authority(authority)
        .path(path)
        .build();

    // Check permissions.
    if (!canAcquireContentProvider(previewParametersUri)) {
      Log.w(TAG, "Permission denied to access uri " + previewParametersUri);
      return;
    }

    synchronized (liveDataLock) {
      if (liveData != null) {
        liveData.removeObserver(sliceView);
      }

      liveData = SliceLiveData.fromUri(/* context */ this, previewParametersUri);
      liveData.observeForever(sliceView);
    }
  }

  private boolean canAcquireContentProvider(Uri uri) {
    try (ContentProviderClient client
        = getContentResolver().acquireUnstableContentProviderClient(uri)) {
      return true;
    } catch (SecurityException e) {
      return false;
    }
  }

  private void refreshAuthorities() {
    List<PackageInfo> packageInfos = getPackageManager().getInstalledPackages(
        PackageManager.GET_PROVIDERS);

    runOnUiThread(() -> {
      authoritiesListAdapter.clear();

      for (PackageInfo packageInfo : packageInfos) {
        ProviderInfo[] providers = packageInfo.providers;
        if (providers != null) {
          for (ProviderInfo provider : providers) {
            if (provider.authority != null) {
              authoritiesListAdapter.add(provider.authority);
            }
          }
        }
      }
    });
  }
}
