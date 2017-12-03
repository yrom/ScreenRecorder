/*
 * Copyright (c) 2014 Yrom Wang <http://www.yrom.net>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.yrom.screenrecorder;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.MediaCodecInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SpinnerAdapter;
import android.widget.Toast;

import net.yrom.screenrecorder.view.NamedSpinner;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.os.Build.VERSION_CODES.M;

public class MainActivity extends Activity {
    private static final int REQUEST_MEDIA_PROJECTION = 1;
    private static final int REQUEST_SDCARD_PERMISSION = 2;
    // members below will be initialized in onCreate()
    private MediaProjectionManager mMediaProjectionManager;
    private Button mButton;
    private NamedSpinner mResolution;
    private NamedSpinner mFramerate;
    private NamedSpinner mIFrameInterval;
    private NamedSpinner mBitrate;
    private NamedSpinner mCodec;
    private NamedSpinner mProfileLevel;
    private NamedSpinner mOrientation;
    private MediaCodecInfo[] mCodecInfos; // avc codecs
    private Notifications mNotifications;

    /**
     * <b>NOTE:</b>
     * {@code ScreenRecorder} should run in background Service
     * instead of a foreground Activity in this demonstrate.
     */
    private ScreenRecorder mRecorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mMediaProjectionManager = (MediaProjectionManager) getApplicationContext().getSystemService(MEDIA_PROJECTION_SERVICE);
        mNotifications = new Notifications(getApplicationContext());

        mButton = findViewById(R.id.record_button);
        mButton.setOnClickListener(this::onButtonClick);

        mCodec = findViewById(R.id.media_codec);
        mResolution = findViewById(R.id.resolution);
        mFramerate = findViewById(R.id.framerate);
        mIFrameInterval = findViewById(R.id.iframe_interval);
        mBitrate = findViewById(R.id.bitrate);
        mProfileLevel = findViewById(R.id.avc_profile);
        mOrientation = findViewById(R.id.orientation);

        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mOrientation.setSelectedPosition(1);
        }
        restoreSelections();

        mCodec.setOnItemSelectedListener((view, position) -> onCodecSelected(view.getSelectedItem()));
        mResolution.setOnItemSelectedListener((view, position) -> {
            if (position == 0) return;
            onResolutionChanged(position, view.getSelectedItem());
        });
        mFramerate.setOnItemSelectedListener((view, position) -> {
            if (position == 0) return;
            onFramerateChanged(position, view.getSelectedItem());
        });
        mBitrate.setOnItemSelectedListener((view, position) -> {
            if (position == 0) return;
            onBitrateChanged(position, view.getSelectedItem());
        });
        mOrientation.setOnItemSelectedListener((view, position) -> {
            if (position == 0) return;
            onOrientationChanged(position, view.getSelectedItem());
        });


        Utils.findEncodersByTypeAsync(ScreenRecorder.VIDEO_AVC, infos -> {
            logAvcCodecInfos(infos);
            mCodecInfos = infos;
            SpinnerAdapter codecsAdapter = createCodecsAdapter(mCodecInfos);
            mCodec.setAdapter(codecsAdapter);

        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mOrientation.setSelectedPosition(1);
        } else {
            mOrientation.setSelectedPosition(0);
        }
        // reset padding
        int horizontal = (int) getResources().getDimension(R.dimen.activity_horizontal_margin);
        int vertical = (int) getResources().getDimension(R.dimen.activity_vertical_margin);
        findViewById(R.id.container).setPadding(horizontal, vertical, horizontal, vertical);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            // NOTE: Should pass this result data into a Service to run ScreenRecorder.
            // The following codes are merely exemplary.

            MediaProjection mediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection == null) {
                Log.e("@@", "media projection is null");
                return;
            }

            final String codec = getSelectedCodec();
            if (codec == null) {
                toast("There is no media-codec");
                mediaProjection.stop();
                return;
            }
            File dir = getSavingDir();
            if (!dir.exists() && !dir.mkdirs()) {
                cancelRecorder();
                return;
            }

            // video size
            int[] selectedWithHeight = getSelectedWithHeight();
            boolean isLandscape = !isLandscape();
            int width = selectedWithHeight[isLandscape ? 0 : 1];
            int height = selectedWithHeight[isLandscape ? 1 : 0];
            int framerate = getSelectedFramerate();
            int iframe = getSelectedIFrameInterval();
            int bitrate = getSelectedBitrate();
            MediaCodecInfo.CodecProfileLevel profileLevel = getSelectedProfileLevel();

            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-kkmmss", Locale.US);
            final File file = new File(dir, "Screen-" + format.format(new Date())
                    + "-" + width + "x" + height + ".mp4");
            mRecorder = new ScreenRecorder(width, height, bitrate,
                    framerate, iframe, codec, profileLevel,
                    1, mediaProjection, file.getAbsolutePath());
            mRecorder.setCallback(new ScreenRecorder.Callback() {
                long startTimeUs = 0;

                @Override
                public void onStop(Throwable error) {
                    runOnUiThread(() -> stopRecorder());
                    if (error != null) {
                        toast("Recorder error ! See logcat for more details");
                        error.printStackTrace();
                        file.delete();
                    }

                }

                @Override
                public void onStart() {
                    mNotifications.recording(0);
                }

                @Override
                public void onRecording(long presentationTimeUs) {
                    if (startTimeUs <= 0) {
                        startTimeUs = presentationTimeUs;
                    }
                    long time = (presentationTimeUs - startTimeUs) / 1000;
                    mNotifications.recording(time);
                }
            });
            if (hasPermissionToWriteExStorage()) {
                startRecorder();
            } else {
                cancelRecorder();
            }
        }

    }

    private static File getSavingDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "ScreenCaptures");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_SDCARD_PERMISSION) {
            // we request only one permission
            if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCaptureIntent();
            }
        }
    }

    private void startCaptureIntent() {
        Intent captureIntent = mMediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveSelections();
        stopRecorder();
        mNotifications = null;
    }


    private void onButtonClick(View v) {
        if (mRecorder != null) {
            stopRecorder();
        } else if (hasPermissionToWriteExStorage()) {
            startCaptureIntent();
        } else if (Build.VERSION.SDK_INT >= M) {
            requestPermissionToWriteExStorage();
        } else {
            toast("No permission to write sd card");
        }
    }

    private void startRecorder() {
        if (mRecorder == null) return;
        mRecorder.start();
        mButton.setText("Stop Recorder");
        registerReceiver(mStopActionReceiver, new IntentFilter(ACTION_STOP));
        moveTaskToBack(true);
    }

    private void stopRecorder() {
        mNotifications.clear();
        if (mRecorder != null) {
            mRecorder.quit();
        }
        mRecorder = null;
        mButton.setText("Restart recorder");
        try {
            unregisterReceiver(mStopActionReceiver);
        } catch (Exception e) {
            //ignored
        }
    }

    private void cancelRecorder() {
        if (mRecorder == null) return;
        Toast.makeText(this, "Permission denied! Screen recorder is cancel", Toast.LENGTH_SHORT).show();
        stopRecorder();
    }

    @TargetApi(M)
    private void requestPermissionToWriteExStorage() {
        if (!shouldShowRequestPermissionRationale(WRITE_EXTERNAL_STORAGE)) {
            requestPermissions(new String[]{WRITE_EXTERNAL_STORAGE}, REQUEST_SDCARD_PERMISSION);
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("Saving captured result to your SD card needs permission")
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        requestPermissions(new String[]{WRITE_EXTERNAL_STORAGE}, REQUEST_SDCARD_PERMISSION))
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .show();
    }

    private boolean hasPermissionToWriteExStorage() {
        int granted = getPackageManager().checkPermission(WRITE_EXTERNAL_STORAGE, getPackageName());
        return granted == PackageManager.PERMISSION_GRANTED;
    }


    private void onResolutionChanged(int selectedPosition, String resolution) {
        String codecName = getSelectedCodec();
        MediaCodecInfo codec = getCodecInfo(codecName);
        if (codec == null) return;
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(ScreenRecorder.VIDEO_AVC);
        MediaCodecInfo.VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();
        String[] xes = resolution.split("x");
        if (xes.length != 2) throw new IllegalArgumentException();
        boolean isLandscape = !isLandscape();
        int width = Integer.parseInt(xes[isLandscape ? 0 : 1]);
        int height = Integer.parseInt(xes[isLandscape ? 1 : 0]);

        double selectedFramerate = getSelectedFramerate();
        int resetPos = Math.max(selectedPosition - 1, 0);
        if (!videoCapabilities.isSizeSupported(width, height)) {
            mResolution.setSelectedPosition(resetPos);
            toast("codec '%s' unsupported size %dx%d (%s)",
                    codecName, width, height, mOrientation.getSelectedItem());
            Log.w("@@", codecName +
                    " height range: " + videoCapabilities.getSupportedHeights() +
                    "\n width range: " + videoCapabilities.getSupportedHeights());
        } else if (!videoCapabilities.areSizeAndRateSupported(width, height, selectedFramerate)) {
            mResolution.setSelectedPosition(resetPos);
            toast("codec '%s' unsupported size %dx%d(%s)\nwith framerate %d",
                    codecName, width, height, mOrientation.getSelectedItem(), (int) selectedFramerate);
        }
    }

    private void onBitrateChanged(int selectedPosition, String bitrate) {
        String codecName = getSelectedCodec();
        MediaCodecInfo codec = getCodecInfo(codecName);
        if (codec == null) return;
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(ScreenRecorder.VIDEO_AVC);
        MediaCodecInfo.VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();
        int selectedBitrate = Integer.parseInt(bitrate) * 1000;

        int resetPos = Math.max(selectedPosition - 1, 0);
        if (!videoCapabilities.getBitrateRange().contains(selectedBitrate)) {
            mBitrate.setSelectedPosition(resetPos);
            toast("codec '%s' unsupported bitrate %d", codecName, selectedBitrate);
            Log.w("@@", codecName +
                    " bitrate range: " + videoCapabilities.getBitrateRange());
        }
    }

    private void onOrientationChanged(int selectedPosition, String orientation) {
        String codecName = getSelectedCodec();
        MediaCodecInfo codec = getCodecInfo(codecName);
        if (codec == null) return;
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(ScreenRecorder.VIDEO_AVC);
        MediaCodecInfo.VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();
        int[] selectedWithHeight = getSelectedWithHeight();
        boolean isLandscape = selectedPosition == 1;
        int width = selectedWithHeight[isLandscape ? 0 : 1];
        int height = selectedWithHeight[isLandscape ? 1 : 0];
        int resetPos = Math.max(mResolution.getSelectedItemPosition() - 1, 0);
        if (!videoCapabilities.isSizeSupported(width, height)) {
            mResolution.setSelectedPosition(resetPos);
            toast("codec '%s' unsupported size %dx%d (%s)",
                    codecName, width, height, orientation);
            return;
        }

        int current = getResources().getConfiguration().orientation;
        if (isLandscape && current == Configuration.ORIENTATION_PORTRAIT) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else if (!isLandscape && current == Configuration.ORIENTATION_PORTRAIT) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    private void onFramerateChanged(int selectedPosition, String rate) {
        String codecName = getSelectedCodec();
        MediaCodecInfo codec = getCodecInfo(codecName);
        if (codec == null) return;
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(ScreenRecorder.VIDEO_AVC);
        MediaCodecInfo.VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();
        int[] selectedWithHeight = getSelectedWithHeight();
        int selectedFramerate = Integer.parseInt(rate);
        boolean isLandscape = !isLandscape();
        int width = selectedWithHeight[isLandscape ? 0 : 1];
        int height = selectedWithHeight[isLandscape ? 1 : 0];

        int resetPos = Math.max(selectedPosition - 1, 0);
        if (!videoCapabilities.getSupportedFrameRates().contains(selectedFramerate)) {
            mFramerate.setSelectedPosition(resetPos);
            toast("codec '%s' unsupported framerate %d", codecName, selectedFramerate);
        } else if (!videoCapabilities.areSizeAndRateSupported(width, height, selectedFramerate)) {
            mFramerate.setSelectedPosition(resetPos);
            toast("codec '%s' unsupported size %dx%d\nwith framerate %d",
                    codecName, width, height, selectedFramerate);
        }
    }

    private void onCodecSelected(String codecName) {
        MediaCodecInfo codec = getCodecInfo(codecName);
        if (codec == null) {
            mProfileLevel.setAdapter(null);
            return;
        }
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(ScreenRecorder.VIDEO_AVC);

        resetProfileLevelAdapter(capabilities);
    }

    private void resetProfileLevelAdapter(MediaCodecInfo.CodecCapabilities capabilities) {
        MediaCodecInfo.CodecProfileLevel[] levels = capabilities.profileLevels;
        if (levels == null || levels.length == 0) {
            mProfileLevel.setEnabled(false);
            return;
        }
        mProfileLevel.setEnabled(true);
        String[] profileLevels = new String[levels.length + 1];
        profileLevels[0] = "Default";
        for (int i = 0; i < levels.length; i++) {
            profileLevels[i + 1] = Utils.toHumanReadable(levels[i]);
        }

        SpinnerAdapter old = mProfileLevel.getAdapter();
        if (old == null || !(old instanceof ArrayAdapter)) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            adapter.addAll(profileLevels);
            mProfileLevel.setAdapter(adapter);
        } else {
            ArrayAdapter<String> adapter = (ArrayAdapter<String>) old;
            adapter.setNotifyOnChange(false);
            adapter.clear();
            adapter.addAll(profileLevels);
            adapter.notifyDataSetChanged();
        }
    }


    private MediaCodecInfo getCodecInfo(String codecName) {
        if (codecName == null) return null;
        if (mCodecInfos == null) {
            mCodecInfos = Utils.findEncodersByType(ScreenRecorder.VIDEO_AVC);
        }
        MediaCodecInfo codec = null;
        for (int i = 0; i < mCodecInfos.length; i++) {
            MediaCodecInfo info = mCodecInfos[i];
            if (info.getName().equals(codecName)) {
                codec = info;
                break;
            }
        }
        if (codec == null) return null;
        return codec;
    }

    private String getSelectedCodec() {
        return mCodec == null ? null : mCodec.getSelectedItem();
    }

    private SpinnerAdapter createCodecsAdapter(MediaCodecInfo[] codecInfos) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, codecInfoNames(codecInfos));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private boolean isLandscape() {
        return mOrientation != null && mOrientation.getSelectedItemPosition() == 0;
    }

    private int getSelectedFramerate() {
        if (mFramerate == null) throw new IllegalStateException();
        return Integer.parseInt(mFramerate.getSelectedItem());
    }

    private int getSelectedBitrate() {
        if (mBitrate == null) throw new IllegalStateException();
        String selectedItem = mBitrate.getSelectedItem(); //kbps
        return Integer.parseInt(selectedItem) * 1000;
    }

    private int getSelectedIFrameInterval() {
        return (mIFrameInterval != null) ? Integer.parseInt(mIFrameInterval.getSelectedItem()) : 5;
    }

    private MediaCodecInfo.CodecProfileLevel getSelectedProfileLevel() {
        return mProfileLevel != null ? Utils.toProfileLevel(mProfileLevel.getSelectedItem()) : null;
    }

    private int[] getSelectedWithHeight() {
        if (mResolution == null) throw new IllegalStateException();
        String selected = mResolution.getSelectedItem();
        String[] xes = selected.split("x");
        if (xes.length != 2) throw new IllegalArgumentException();
        return new int[]{Integer.parseInt(xes[0]), Integer.parseInt(xes[1])};

    }

    private void toast(String message, Object... args) {
        Toast toast = Toast.makeText(this,
                (args.length == 0) ? message : String.format(Locale.US, message, args),
                Toast.LENGTH_SHORT);
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(toast::show);
        } else {
            toast.show();
        }
    }

    private static String[] codecInfoNames(MediaCodecInfo[] codecInfos) {
        String[] names = new String[codecInfos.length];
        for (int i = 0; i < codecInfos.length; i++) {
            names[i] = codecInfos[i].getName();
        }
        return names;
    }

    /**
     * Print information of all AVC MediaCodec on this device.
     */
    private static void logAvcCodecInfos(MediaCodecInfo[] codecInfos) {
        for (MediaCodecInfo info : codecInfos) {
            StringBuilder builder = new StringBuilder(512);
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(ScreenRecorder.VIDEO_AVC);
            builder.append("Encoder '").append(info.getName()).append('\'')
                    .append("\n  supported : ")
                    .append(Arrays.toString(info.getSupportedTypes()));
            MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();

            builder.append("\n  Video capabilities:")
                    .append("\n  Widths: ").append(videoCaps.getSupportedWidths())
                    .append("\n  Heights: ").append(videoCaps.getSupportedHeights())
                    .append("\n  Frame Rates: ").append(videoCaps.getSupportedFrameRates())
                    .append("\n  Bitrate: ").append(videoCaps.getBitrateRange());
            MediaCodecInfo.CodecProfileLevel[] levels = caps.profileLevels;

            builder.append("\n  Profile-levels: ");
            for (MediaCodecInfo.CodecProfileLevel level : levels) {
                builder.append("\n  ").append(Utils.toHumanReadable(level));
            }

            builder.append("\n  Color-formats: ");
            for (int c : caps.colorFormats) {
                builder.append("\n  ").append(Utils.toHumanReadable(c));
            }

            Log.i("@@@", builder.toString());
        }
    }

    private void restoreSelections() {
        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        restoreSelectionFromPreferences(preferences, this.mResolution);
        restoreSelectionFromPreferences(preferences, this.mFramerate);
        restoreSelectionFromPreferences(preferences, this.mIFrameInterval);
        restoreSelectionFromPreferences(preferences, this.mBitrate);
    }

    private void saveSelections() {
        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor edit = preferences.edit();
        saveSelectionToPreferences(edit, this.mResolution);
        saveSelectionToPreferences(edit, this.mFramerate);
        saveSelectionToPreferences(edit, this.mIFrameInterval);
        saveSelectionToPreferences(edit, this.mBitrate);
        edit.apply();
    }

    private void saveSelectionToPreferences(SharedPreferences.Editor preferences, NamedSpinner spinner) {
        int resId = spinner.getId();
        String key = getResources().getResourceEntryName(resId);
        int selectedItemPosition = spinner.getSelectedItemPosition();
        preferences.putInt(key, selectedItemPosition);
    }

    private void restoreSelectionFromPreferences(SharedPreferences preferences, NamedSpinner spinner) {
        int resId = spinner.getId();
        String key = getResources().getResourceEntryName(resId);
        int resolution = preferences.getInt(key, -1);
        if (resolution != -1) {
            spinner.setSelectedPosition(resolution);
        }
    }

    static final String ACTION_STOP = "net.yrom.screenrecorder.action.STOP";

    private BroadcastReceiver mStopActionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            File file = new File(mRecorder.getSavedPath());
            if (ACTION_STOP.equals(intent.getAction())) {
                stopRecorder();
            }
            Toast.makeText(context, "Recorder stopped!", Toast.LENGTH_SHORT).show();
            StrictMode.VmPolicy vmPolicy = StrictMode.getVmPolicy();
            try {
                // disable detecting FileUriExposure on public file
                StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
                viewResult(file);
            } finally {
                StrictMode.setVmPolicy(vmPolicy);
            }
        }

        private void viewResult(File file) {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.addCategory(Intent.CATEGORY_DEFAULT);
            view.setDataAndType(Uri.fromFile(file), ScreenRecorder.VIDEO_AVC);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(view);
            } catch (ActivityNotFoundException e) {
                // no activity can open this video
            }
        }
    };

}
