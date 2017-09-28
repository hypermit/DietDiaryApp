package com.canyapan.dietdiaryapp.fragments;

import android.Manifest;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v7.preference.ListPreference;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceFragmentCompat;
import android.support.v7.preference.PreferenceManager;
import android.support.v7.preference.SwitchPreferenceCompat;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import com.canyapan.dietdiaryapp.R;
import com.canyapan.dietdiaryapp.db.DatabaseHelper;
import com.canyapan.dietdiaryapp.helpers.DateTimeHelper;
import com.canyapan.dietdiaryapp.helpers.DriveBackupServiceHelper;
import com.canyapan.dietdiaryapp.preference.TimePreferenceCompat;
import com.canyapan.dietdiaryapp.preference.TimePreferenceDialogFragmentCompat;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveApi;
import com.google.android.gms.drive.query.Filters;
import com.google.android.gms.drive.query.Query;
import com.google.android.gms.drive.query.SearchableField;
import com.google.android.gms.drive.query.SortOrder;
import com.google.android.gms.drive.query.SortableField;

import org.joda.time.LocalTime;

import java.lang.ref.WeakReference;

import static android.app.Activity.RESULT_OK;

public class SettingsSupportFragment extends PreferenceFragmentCompat
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, Preference.OnPreferenceChangeListener, ResultCallback<DriveApi.MetadataBufferResult> {

    private static final String TAG = "Settings";
    private static final String DIALOG_FRAGMENT_TAG =
            "android.support.v7.preference.PreferenceFragment.DIALOG";

    private static final int REQUEST_ACCOUNTS = 1000;
    private static final int REQUEST_RESOLVE_ERROR = 1001;

    public static final String KEY_GENERAL_CLOCK_MODE = "general_clock_mode";
    public static final String KEY_NOTIFICATIONS_ACTIVE = "notifications_active";
    public static final String KEY_NOTIFICATIONS_DAILY_REMAINDER = "notifications_daily_remainder";
    public static final String KEY_NOTIFICATIONS_DAILY_REMAINDER_TIME = "notifications_daily_remainder_time";
    public static final String KEY_BACKUP_ACTIVE = "backup_active";
    public static final String KEY_BACKUP_FREQUENCY = "backup_frequency";
    public static final String KEY_BACKUP_WIFI_ONLY = "backup_wifi_only";
    public static final String KEY_BACKUP_NOW = "backup_now";

    private WeakReference<GoogleApiClient> mGoogleApiClientRef = null;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        if (null == mGoogleApiClientRef || null == mGoogleApiClientRef.get()) {
            mGoogleApiClientRef = new WeakReference<>(getGoogleApiClient());
        }

        addPreferencesFromResource(R.xml.settings);

        bindPreferenceSummaryToValue(findPreference(KEY_GENERAL_CLOCK_MODE));
        bindPreferenceSummaryToValue(findPreference(KEY_NOTIFICATIONS_ACTIVE));
        bindPreferenceSummaryToValue(findPreference(KEY_NOTIFICATIONS_DAILY_REMAINDER));
        bindPreferenceSummaryToValue(findPreference(KEY_NOTIFICATIONS_DAILY_REMAINDER_TIME));
        bindPreferenceSummaryToValue(findPreference(KEY_BACKUP_FREQUENCY));

        SwitchPreferenceCompat backupActivePref = (SwitchPreferenceCompat) findPreference(KEY_BACKUP_ACTIVE);
        backupActivePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                if ((Boolean) newValue) { // changing to active
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (getActivity().checkSelfPermission(Manifest.permission.GET_ACCOUNTS)
                                != PackageManager.PERMISSION_GRANTED) {
                            getActivity().requestPermissions( // request permission
                                    new String[]{
                                            Manifest.permission.GET_ACCOUNTS
                                    }, REQUEST_ACCOUNTS);

                            return false;
                        }
                    }

                    // have the permission or version is lower
                    connectGoogleApiClient();

                    return false; // will be handled if drive connection successful.
                }

                // changing to passive
                if (null != mGoogleApiClientRef && null != mGoogleApiClientRef.get()) {
                    mGoogleApiClientRef.get().disconnect();
                }

                return true; // let it
            }
        });

        Preference backupNowPref = findPreference(KEY_BACKUP_NOW);
        bindPreferenceSummaryToValue(backupNowPref);
        backupNowPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                DriveBackupServiceHelper.setupImmediate(getContext());
                //todo setup a listener to watch changes on last backup pref

                return true;
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_RESOLVE_ERROR) {
            if (resultCode == RESULT_OK) {
                connectGoogleApiClient();
            }
        }
    }

    @Override
    public void onDisplayPreferenceDialog(Preference preference) {
        // check if dialog is already showing
        if (getFragmentManager().findFragmentByTag(DIALOG_FRAGMENT_TAG) != null) {
            return;
        }

        if (preference instanceof TimePreferenceCompat) {
            final DialogFragment dialogFragment = TimePreferenceDialogFragmentCompat.newInstance(preference.getKey());
            if (dialogFragment != null) {
                dialogFragment.setTargetFragment(this, 0);
                dialogFragment.show(this.getFragmentManager(), DIALOG_FRAGMENT_TAG);
            }
        } else {
            super.onDisplayPreferenceDialog(preference);
        }
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.d(TAG, "Drive API connected.");

        // Get backup files from drive.
        SortOrder sortOrder = new SortOrder.Builder()
                .addSortDescending(SortableField.CREATED_DATE)
                .build();

        Query query = new Query.Builder()
                .addFilter(Filters.and(
                        Filters.eq(SearchableField.MIME_TYPE, "application/zip"),
                        Filters.contains(SearchableField.TITLE, "backup.zip")))
                .setSortOrder(sortOrder)
                .build();

        Drive.DriveApi.getAppFolder(mGoogleApiClientRef.get())
                .queryChildren(mGoogleApiClientRef.get(), query)
                .setResultCallback(this);

        // Activate the drive backup.
        final SwitchPreferenceCompat backupActivePref = (SwitchPreferenceCompat) findPreference(KEY_BACKUP_ACTIVE);
        if (!backupActivePref.isChecked()) {
            backupActivePref.setChecked(true);
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(TAG, "Drive API connection suspended.");
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        if (!connectionResult.hasResolution()) {
            // show the localized error dialog.
            GoogleApiAvailability.getInstance().getErrorDialog(this.getActivity(),
                    connectionResult.getErrorCode(), 0).show();
            return;
        }

        try {
            connectionResult.startResolutionForResult(this.getActivity(), REQUEST_RESOLVE_ERROR);
        } catch (IntentSender.SendIntentException e) {
            Log.e(TAG, "Exception while starting resolution activity", e);
        }
        Log.e(TAG, "Drive API connection failed. " + connectionResult.toString());
    }

    @Override
    public void onStop() {
        disconnectGoogleApiClient();

        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_ACCOUNTS
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            connectGoogleApiClient(); // Permission granted, lets try to connect now.
        } else {
            Toast.makeText(getContext(), R.string.pref_backup_no_permission, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * A preference value change listener that updates the preference's summary
     * to reflect its new value.
     */
    @Override
    public boolean onPreferenceChange(Preference preference, Object value) {
        if (preference.getKey().equals(KEY_BACKUP_NOW)) {
            if (null == value) {
                preference.setSummary(preference.getContext().getString(R.string.pref_title_backup_now_summary, preference.getContext().getString(R.string.pref_title_backup_now_summary_never)));
            } else {
                long time;
                if (value instanceof String) {
                    time = Long.valueOf((String) value);
                } else if (value instanceof Long) {
                    time = (long) value;
                } else {
                    return false;
                }

                preference.setSummary(preference.getContext().getString(R.string.pref_title_backup_now_summary, DateUtils.getRelativeTimeSpanString(time)));
            }
        } else if (preference instanceof ListPreference) {
            // For list preferences, look up the correct display value in
            // the preference's 'entries' list.
            ListPreference listPreference = (ListPreference) preference;
            if (null != value) {
                int index = listPreference.findIndexOfValue(value.toString());

                CharSequence summary;
                if (index >= 0) {
                    summary = listPreference.getEntries()[index];
                } else if (null != listPreference.getValue()) {
                    summary = listPreference.getValue();
                } else {
                    summary = null;
                }

                // Set the summary to reflect the new value.
                preference.setSummary(summary);
            }
        } else if (preference instanceof TimePreferenceCompat) {
            if (null != value && value instanceof String) {
                LocalTime time = LocalTime.parse((String) value, DatabaseHelper.DB_TIME_FORMATTER);

                TimePreferenceCompat timePreference = (TimePreferenceCompat) preference;
                preference.setSummary(DateTimeHelper.convertLocalTimeToString(preference.getContext(),
                        time.getHourOfDay(), time.getMinuteOfHour()));
            }
        } else {
            // For all other preferences, set the summary to the value's
            // simple string representation.
            if (value instanceof String) {
                preference.setSummary(value.toString());
            }
        }

        return true;
    }

    /**
     * Binds a preference's summary to its value. More specifically, when the
     * preference's value is changed, its summary (line of text below the
     * preference tvTitle) is updated to reflect the value. The summary is also
     * immediately updated upon calling this method. The exact display format is
     * dependent on the type of preference.
     */
    private void bindPreferenceSummaryToValue(Preference preference) {
        // Set the listener to watch for value changes.
        preference.setOnPreferenceChangeListener(this);

        // Trigger the listener immediately with the preference's
        // current value.
        this.onPreferenceChange(preference,
                PreferenceManager
                        .getDefaultSharedPreferences(preference.getContext())
                        .getAll().get(preference.getKey()));
    }

    private GoogleApiClient getGoogleApiClient() {
        return new GoogleApiClient.Builder(this.getActivity())
                .addApi(Drive.API)
                .addScope(Drive.SCOPE_APPFOLDER)
                .addScope(Drive.SCOPE_FILE)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
    }

    private void connectGoogleApiClient() {
        if (null != mGoogleApiClientRef && null != mGoogleApiClientRef.get()) {
            if (!mGoogleApiClientRef.get().isConnecting() && !mGoogleApiClientRef.get().isConnected()) {
                mGoogleApiClientRef.get().connect();
            }
        }
    }

    private void disconnectGoogleApiClient() {
        if (null != mGoogleApiClientRef && null != mGoogleApiClientRef.get()) {
            if (mGoogleApiClientRef.get().isConnected() || mGoogleApiClientRef.get().isConnecting()) {
                mGoogleApiClientRef.get().disconnect();
            }
        }
    }

    @Override
    public void onResult(@NonNull DriveApi.MetadataBufferResult metadataBufferResult) {
        if (!metadataBufferResult.getStatus().isSuccess()) {
            Toast.makeText(getContext(), R.string.pref_backup_unable_to_list_drive_backups, Toast.LENGTH_LONG).show();
            return;
        }

        long size = 0; // total backup size
        for (int i = 0; i < metadataBufferResult.getMetadataBuffer().getCount(); i++) {
            size += metadataBufferResult.getMetadataBuffer().get(i).getFileSize();
        }


        //TODO print total backup size.
        //TODO perform actions for the found backup.
        Toast.makeText(getContext(), "Total backup size " + (size / 1024 / 1024) + "MB.", Toast.LENGTH_LONG).show();
    }
}
