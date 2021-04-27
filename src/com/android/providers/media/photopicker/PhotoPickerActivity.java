/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.photopicker;

import static com.android.providers.media.photopicker.viewmodel.PickerViewModel.TAG;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import com.android.providers.media.R;

import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.ui.PhotosTabFragment;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import java.util.List;


/**
 * Photo Picker allows users to choose one or more photos and/or videos to share with an app. The
 * app does not get access to all photos/videos.
 */
public class PhotoPickerActivity extends AppCompatActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_picker);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        // TODO (b/185801192): remove this and add tabs Photos and Albums
        getSupportActionBar().setTitle("Photos & Videos");

        PickerViewModel model = new ViewModelProvider(this).get(PickerViewModel.class);

        // TODO (b/185801129): Remove this when the preview is ready, return the
        // uri when the user clicks the add button.
        model.getSelectedItems().observe(this, this::onSelectedItemChanged);

        // only add the fragment when the activity is created at first time
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.fragment_container, PhotosTabFragment.class, null)
                    .commitNow();
        }
    }

    private void onSelectedItemChanged(List<Item> selectedItemList) {
        Log.d(TAG, "Selected Item Size = " + selectedItemList.size());
        // TODO (b/169737798): Support Multi-select
        if (selectedItemList.size() > 0) {
            final Intent intent = new Intent();
            intent.setData(selectedItemList.get(0).getContentUri());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            setResult(Activity.RESULT_OK, intent);
            finish();
        }
    }
}