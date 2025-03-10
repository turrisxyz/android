/**
 * ownCloud Android client application
 *
 * @author masensio
 * @author Christian Schabesberger
 * @author David González Verdugo
 * Copyright (C) 2020 ownCloud GmbH.
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.ui.activity;

import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.snackbar.Snackbar;
import com.owncloud.android.R;
import com.owncloud.android.extensions.ActivityExtKt;
import com.owncloud.android.presentation.ui.security.BiometricActivity;
import com.owncloud.android.presentation.ui.security.passcode.PassCodeActivity;
import com.owncloud.android.presentation.ui.security.PatternActivity;
import com.owncloud.android.utils.FileStorageUtils;
import com.owncloud.android.utils.PreferenceUtils;
import timber.log.Timber;

import java.io.File;

import static com.owncloud.android.presentation.ui.security.passcode.PassCodeActivity.PREFERENCE_PASSCODE;

public class ManageSpaceActivity extends AppCompatActivity {

    private static final String LIB_FOLDER = "lib";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_space);

        // Allow or disallow touches with other visible windows
        LinearLayout manageSpaceLayout = findViewById(R.id.manage_space_layout);
        manageSpaceLayout.setFilterTouchesWhenObscured(
                PreferenceUtils.shouldDisallowTouchesWithOtherVisibleWindows(this)
        );

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setTitle(R.string.manage_space_title);

        TextView descriptionTextView = findViewById(R.id.general_description);
        descriptionTextView.setText(getString(R.string.manage_space_description, getString(R.string.app_name)));

        Button clearDataButton = findViewById(R.id.clearDataButton);
        clearDataButton.setOnClickListener(v -> {
            ClearDataAsyncTask clearDataTask = new ClearDataAsyncTask();
            clearDataTask.execute();
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean retval = true;
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                break;
            default:
                Timber.w("Unknown menu item triggered");
                retval = super.onOptionsItemSelected(item);
        }
        return retval;
    }

    /**
     * AsyncTask for Clear Data, saving the passcode
     */
    private class ClearDataAsyncTask extends AsyncTask<Void, Void, Boolean> {

        @Override
        protected Boolean doInBackground(Void... params) {

            boolean result;

            // Save passcode from Share preferences
            SharedPreferences appPrefs = PreferenceManager
                    .getDefaultSharedPreferences(getApplicationContext());

            boolean passCodeEnable = appPrefs.getBoolean(PassCodeActivity.PREFERENCE_SET_PASSCODE, false);
            boolean patternEnabled = appPrefs.getBoolean(PatternActivity.PREFERENCE_SET_PATTERN, false);
            boolean biometricEnabled = appPrefs.getBoolean(BiometricActivity.PREFERENCE_SET_BIOMETRIC, false);

            final String passcodeString = appPrefs.getString(PREFERENCE_PASSCODE, null);
            int numberOfPasscodeDigits = getBaseContext().getResources().getInteger(R.integer.passcode_digits);
            final String[] passCodeDigits = new String[numberOfPasscodeDigits];

            if (passCodeEnable) {
                for (int i = 0; i < numberOfPasscodeDigits && passCodeEnable; i++) {
                    passCodeDigits[i] = Character.toString(passcodeString.charAt(i));
                }
            }

            String patternValue = "";
            if (patternEnabled) {
                patternValue = appPrefs.getString(PatternActivity.PREFERENCE_PATTERN, null);
            }

            // Clear data
            result = clearApplicationData();

            // Clear SharedPreferences
            SharedPreferences.Editor appPrefsEditor = PreferenceManager
                    .getDefaultSharedPreferences(getApplicationContext()).edit();
            appPrefsEditor.clear();
            result = result && appPrefsEditor.commit();

            final StringBuilder newPassCodeString = new StringBuilder();
            for(int i = 0; i < numberOfPasscodeDigits && passCodeEnable; i++) {
                newPassCodeString.append(passCodeDigits[i]);
            }
            appPrefsEditor.putString(PassCodeActivity.PREFERENCE_PASSCODE, newPassCodeString.toString());

            // Recover pattern
            if (patternEnabled) {
                appPrefsEditor.putString(PatternActivity.PREFERENCE_PATTERN, patternValue);
            }

            // Reenable biometric
            appPrefsEditor.putBoolean(BiometricActivity.PREFERENCE_SET_BIOMETRIC, biometricEnabled);

            appPrefsEditor.putBoolean(PassCodeActivity.PREFERENCE_SET_PASSCODE, passCodeEnable);
            appPrefsEditor.putBoolean(PatternActivity.PREFERENCE_SET_PATTERN, patternEnabled);
            result = result && appPrefsEditor.commit();

            return result;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if (!result) {
                ActivityExtKt.showMessageInSnackbar(ManageSpaceActivity.this, android.R.id.content, getString(R.string.manage_space_clear_data),
                        Snackbar.LENGTH_LONG);
            } else {
                finish();
                System.exit(0);
            }

        }

        public boolean clearApplicationData() {
            boolean clearResult = true;
            File appDir = new File(getCacheDir().getParent());
            if (appDir.exists()) {
                String[] children = appDir.list();
                if (children != null) {
                    for (String s : children) {
                        if (!LIB_FOLDER.equals(s)) {
                            File fileToDelete = new File(appDir, s);
                            clearResult = clearResult && FileStorageUtils.deleteDir(fileToDelete);
                            Timber.d("Clear Application Data, File: " + fileToDelete.getName() + " DELETED *****");
                        }
                    }
                } else {
                    clearResult = false;
                }
            }
            return clearResult;
        }
    }
}
