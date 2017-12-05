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

import static android.Manifest.permission.RECORD_AUDIO;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.os.Build.VERSION_CODES.M;
import static net.yrom.screenrecorder.ScreenRecorder.AUDIO_AAC;
import static net.yrom.screenrecorder.ScreenRecorder.VIDEO_AVC;

public class MainActivity extends Activity {
    private static final int REQUEST_MEDIA_PROJECTION = 1;
    private static final int REQUEST_PERMISSIONS = 2;
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


        Utils.findEncodersByTypeAsync(VIDEO_AVC, infos -> {
            logCodecInfos(infos, VIDEO_AVC);
            mCodecInfos = infos;
            SpinnerAdapter codecsAdapter = createCodecsAdapter(mCodecInfos);
            mCodec.setAdapter(codecsAdapter);

        });
        Utils.findEncodersByTypeAsync(AUDIO_AAC, infos -> {
            logCodecInfos(infos, AUDIO_AAC);
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
            boolean isLandscape = isLandscape();
            int width = selectedWithHeight[isLandscape ? 0 : 1];
            int height = selectedWithHeight[isLandscape ? 1 : 0];
            int framerate = getSelectedFramerate();
            int iframe = getSelectedIFrameInterval();
            int bitrate = getSelectedBitrate();
            MediaCodecInfo.CodecProfileLevel profileLevel = getSelectedProfileLevel();

            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
            final File file = new File(dir, "Screen-" + format.format(new Date())
                    + "-" + width + "x" + height + ".mp4");
            mRecorder = new ScreenRecorder(new VideoEncodeConfig(width, height, bitrate,
                    framerate, iframe, codec, VIDEO_AVC, profileLevel),
                    //TODO: audio encode config
                    new AudioEncodeConfig(null, AUDIO_AAC, 300_000, 44100, 2),
                    1, mediaProjection, file.getAbsolutePath());
            mRecorder.setCallback(new ScreenRecorder.Callback() {
                long startTime = 0;

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
                    if (startTime <= 0) {
                        startTime = presentationTimeUs;
                    }
                    long time = (presentationTimeUs - startTime) / 1000;
                    mNotifications.recording(time);
                }
            });
            if (hasPermissions()) {
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
        if (requestCode == REQUEST_PERMISSIONS) {
            // we request 2 permissions
            if (grantResults.length == 2
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
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
        } else if (hasPermissions()) {
            startCaptureIntent();
        } else if (Build.VERSION.SDK_INT >= M) {
            requestPermissions();
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
    private void requestPermissions() {
        if (!shouldShowRequestPermissionRationale(WRITE_EXTERNAL_STORAGE)
                && !shouldShowRequestPermissionRationale(RECORD_AUDIO)) {
            requestPermissions(new String[]{WRITE_EXTERNAL_STORAGE, RECORD_AUDIO}, REQUEST_PERMISSIONS);
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("Using your mic to record audio and your sd card to save video file")
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        requestPermissions(new String[]{WRITE_EXTERNAL_STORAGE, RECORD_AUDIO}, REQUEST_PERMISSIONS))
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .show();
    }

    private boolean hasPermissions() {
        PackageManager pm = getPackageManager();
        String packageName = getPackageName();
        int granted = pm.checkPermission(RECORD_AUDIO, packageName)
                | pm.checkPermission(WRITE_EXTERNAL_STORAGE, packageName);
        return granted == PackageManager.PERMISSION_GRANTED;
    }

    private void onResolutionChanged(int selectedPosition, String resolution) {
        String codecName = getSelectedCodec();
        MediaCodecInfo codec = getCodecInfo(codecName);
        if (codec == null) return;
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(VIDEO_AVC);
        MediaCodecInfo.VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();
        String[] xes = resolution.split("x");
        if (xes.length != 2) throw new IllegalArgumentException();
        boolean isLandscape = isLandscape();
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
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(VIDEO_AVC);
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
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(VIDEO_AVC);
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
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(VIDEO_AVC);
        MediaCodecInfo.VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();
        int[] selectedWithHeight = getSelectedWithHeight();
        int selectedFramerate = Integer.parseInt(rate);
        boolean isLandscape = isLandscape();
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
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(VIDEO_AVC);

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
            profileLevels[i + 1] = Utils.avcProfileLevelToString(levels[i]);
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
            mCodecInfos = Utils.findEncodersByType(VIDEO_AVC);
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
        return mOrientation != null && mOrientation.getSelectedItemPosition() == 1;
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
     * Print information of all MediaCodec on this device.
     */
    private static void logCodecInfos(MediaCodecInfo[] codecInfos, String mimeType) {
        for (MediaCodecInfo info : codecInfos) {
            StringBuilder builder = new StringBuilder(512);
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mimeType);
            builder.append("Encoder '").append(info.getName()).append('\'')
                    .append("\n  supported : ")
                    .append(Arrays.toString(info.getSupportedTypes()));
            MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
            if (videoCaps != null) {
                builder.append("\n  Video capabilities:")
                        .append("\n  Widths: ").append(videoCaps.getSupportedWidths())
                        .append("\n  Heights: ").append(videoCaps.getSupportedHeights())
                        .append("\n  Frame Rates: ").append(videoCaps.getSupportedFrameRates())
                        .append("\n  Bitrate: ").append(videoCaps.getBitrateRange());
                if (VIDEO_AVC.equals(mimeType)) {
                    MediaCodecInfo.CodecProfileLevel[] levels = caps.profileLevels;

                    builder.append("\n  Profile-levels: ");
                    for (MediaCodecInfo.CodecProfileLevel level : levels) {
                        builder.append("\n  ").append(Utils.avcProfileLevelToString(level));
                    }
                }
                builder.append("\n  Color-formats: ");
                for (int c : caps.colorFormats) {
                    builder.append("\n  ").append(Utils.toHumanReadable(c));
                }
            }
            MediaCodecInfo.AudioCapabilities audioCaps = caps.getAudioCapabilities();
            if (audioCaps != null) {
                builder.append("\n Audio capabilities:")
                        .append("\n Sample Rates: ").append(Arrays.toString(audioCaps.getSupportedSampleRates()))
                        .append("\n Bit Rates: ").append(audioCaps.getBitrateRange())
                        .append("\n Max channels: ").append(audioCaps.getMaxInputChannelCount());
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
            view.setDataAndType(Uri.fromFile(file), VIDEO_AVC);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(view);
            } catch (ActivityNotFoundException e) {
                // no activity can open this video
            }
        }
    };

}
