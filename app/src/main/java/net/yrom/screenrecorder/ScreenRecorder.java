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

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.media.MediaFormat.MIMETYPE_VIDEO_AVC;
import static android.os.Build.VERSION_CODES.M;

/**
 * @author Yrom
 */
public class ScreenRecorder {
    private static final String TAG = "ScreenRecorder";
    static final String VIDEO_AVC = MIMETYPE_VIDEO_AVC; // H.264 Advanced Video Coding

    private int mWidth;
    private int mHeight;
    private int mDpi;
    private String mDstPath;
    private MediaProjection mMediaProjection;
    private MediaFormat mFormat;
    private String mCodecName;
    private MediaCodec mEncoder;
    private Surface mSurface;
    private MediaMuxer mMuxer;
    private boolean mMuxerStarted = false;
    private int mVideoTrackIndex = -1;
    private AtomicBoolean mForceQuit = new AtomicBoolean(false);
    private AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private VirtualDisplay mVirtualDisplay;
    private MediaProjection.Callback mProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            quit();
        }
    };

    private HandlerThread mWorker;
    private CallbackHandler mHandler;

    private Callback mCallback;

    /**
     * @param dpi for {@link VirtualDisplay}
     */
    public ScreenRecorder(int width, int height, int bitrate,
                          int framerate, int iframeInterval,
                          String codecName,
                          MediaCodecInfo.CodecProfileLevel codecProfileLevel,
                          int dpi, MediaProjection mp,
                          String dstPath) {
        mWidth = width;
        mHeight = height;
        mDpi = dpi;
        mMediaProjection = mp;
        mDstPath = dstPath;
        mCodecName = codecName;
        mFormat = MediaFormat.createVideoFormat(VIDEO_AVC, width, height);
        mFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        mFormat.setInteger(MediaFormat.KEY_FRAME_RATE, framerate);
        mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeInterval);
        if (codecProfileLevel != null && codecProfileLevel.profile != 0 && codecProfileLevel.level != 0) {
            mFormat.setInteger(MediaFormat.KEY_PROFILE, codecProfileLevel.profile);
            mFormat.setInteger("level", codecProfileLevel.level);
        }
        // maybe useful
        // mFormat.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 10_000_000);

        Log.d(TAG, "Created video format: " + mFormat);
    }

    /**
     * stop task
     */
    public final void quit() {
        mForceQuit.set(true);
        if (!mIsRunning.get()) {
            release();
        }
    }

    public void start() {
        if (mWorker != null) throw new IllegalStateException();
        mWorker = new HandlerThread(TAG);
        mWorker.start();
        mHandler = new CallbackHandler(mWorker.getLooper());
        mHandler.sendEmptyMessage(MSG_START);
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public String getSavedPath() {
        return mDstPath;
    }

    interface Callback {
        void onStop(Throwable error);

        void onStart();

        void onRecording(long presentationTimeUs);
    }

    static final int MSG_START = 0;
    static final int MSG_RELEASE = 1;
    static final int MSG_ERROR = 2;

    class CallbackHandler extends Handler {
        CallbackHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START:
                    try {
                        record();
                        if (mCallback != null) {
                            mCallback.onStart();
                        }
                        break;
                    } catch (Exception e) {
                        msg.obj = e;
                    }
                case MSG_ERROR:
                    if (mCallback != null) {
                        mCallback.onStop((Throwable) msg.obj);
                    }
                    stop();
                case MSG_RELEASE:
                    release();
                    break;
            }
        }
    }

    private void record() {
        if (mIsRunning.get() || mForceQuit.get()) {
            throw new IllegalStateException();
        }
        if (mMediaProjection == null) {
            throw new IllegalStateException("maybe release");
        }
        mIsRunning.set(true);

        mMediaProjection.registerCallback(mProjectionCallback, mHandler);
        try {
            // create muxer
            mMuxer = new MediaMuxer(mDstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            // create encoder and input surface
            createVideoEncoderAndSurface();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG + "-display",
                mWidth, mHeight, mDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                mSurface, null, null);
        Log.d(TAG, "created virtual display: " + mVirtualDisplay.getDisplay());
    }

    private void muxVideo(int index, MediaCodec.BufferInfo buffer) {
        if (!mMuxerStarted) {
            throw new IllegalStateException("MediaMuxer dose not call addTrack(format) ");
        }
        ByteBuffer encodedData = mEncoder.getOutputBuffer(index);
        if (mForceQuit.get()) {
            // add EOS flag
            Log.d(TAG, "Recorder attempt to quit, signal muxer EOS");
            buffer.flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
        }
        encodeToVideoTrack(buffer, encodedData);
        mEncoder.releaseOutputBuffer(index, false);
        if ((buffer.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            stop();
            Log.d(TAG, "Stop encoder and muxer, since the buffer has been marked with EOS");
            // send release msg
            mHandler.sendEmptyMessage(MSG_RELEASE);
        }
    }

    private void encodeToVideoTrack(MediaCodec.BufferInfo buffer, ByteBuffer encodedData) {
        if ((buffer.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // The codec config data was pulled out and fed to the muxer when we got
            // the INFO_OUTPUT_FORMAT_CHANGED status.
            // Ignore it.
            Log.d(TAG, "Ignoring BUFFER_FLAG_CODEC_CONFIG");
            buffer.size = 0;
        }
        boolean eos = (buffer.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        if (buffer.size == 0 && !eos) {
            Log.d(TAG, "info.size == 0, drop it.");
            encodedData = null;
        } else {
            Log.d(TAG, "Got buffer, info: size=" + buffer.size
                    + ", presentationTimeUs=" + buffer.presentationTimeUs
                    + ", offset=" + buffer.offset);
            if (!eos && mCallback != null) {
                mCallback.onRecording(buffer.presentationTimeUs);
            }
        }
        if (encodedData != null) {
            encodedData.position(buffer.offset);
            encodedData.limit(buffer.offset + buffer.size);
            mMuxer.writeSampleData(mVideoTrackIndex, encodedData, buffer);
            Log.i(TAG, "Sent " + buffer.size + " bytes to MediaMuxer...");
        }
    }

    private void resetOutputFormatAndStartMuxer(MediaFormat newFormat) {
        // should happen before receiving buffers, and should only happen once
        if (mVideoTrackIndex >= 0 || mMuxerStarted) {
            throw new IllegalStateException("output format already changed!");
        }
        Log.i(TAG, "Output format changed.\n New format: " + newFormat.toString());
        mVideoTrackIndex = mMuxer.addTrack(newFormat);
        mMuxer.start();
        mMuxerStarted = true;
        Log.i(TAG, "Started media muxer, videoIndex=" + mVideoTrackIndex);
    }

    // @WorkerThread
    private void createVideoEncoderAndSurface() throws IOException {
        Surface surface;
        final MediaCodec encoder = MediaCodec.createByCodecName(mCodecName);
        MediaCodec.Callback callback = new MediaCodec.Callback() {
            boolean ranIntoError = false;

            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                Log.i(TAG, "Encoder output buffer available: index=" + index);
                try {
                    muxVideo(index, info);
                } catch (Exception e) {
                    Log.e(TAG, "Muxer encountered an error! ", e);
                    Message.obtain(mHandler, MSG_ERROR, e).sendToTarget();
                }
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                ranIntoError = true;
                Log.e(TAG, "Encoder ran into an error! ", e);
                Message.obtain(mHandler, MSG_ERROR, e).sendToTarget();
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                resetOutputFormatAndStartMuxer(format);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= M) {
                encoder.setCallback(callback, mHandler);
            } else {
                encoder.setCallback(callback);
            }

            encoder.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            // Should call before start()
            surface = encoder.createInputSurface();
            Log.d(TAG, "Created input surface: " + surface);
            encoder.start();
        } catch (MediaCodec.CodecException e) {
            Log.e(TAG, "Configure encoder failure! ", e);
            throw e;
        }
        mSurface = surface;
        mEncoder = encoder;
    }

    private void stop() {
        // maybe called on an error has been occurred
        try {
            if (mEncoder != null) mEncoder.stop();
        } catch (IllegalStateException e) {
            // ignored

        }
        try {
            if (mMuxer != null) mMuxer.stop();
        } catch (IllegalStateException e) {
            // ignored
        }
        mIsRunning.set(false);
    }

    private void release() {
        if (mWorker != null) {
            mWorker.quitSafely();
            mWorker = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        if (mEncoder != null) {
            mEncoder.release();
            mEncoder = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.unregisterCallback(mProjectionCallback);
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        if (mMuxer != null) {
            try {
                mMuxer.release();
            } catch (Exception e) {
                // ignored
            }
            mMuxer = null;
        }
        mHandler = null;
    }

    @Override
    protected void finalize() throws Throwable {
        if (mMediaProjection != null) {
            Log.e(TAG, "release() not called!");
            release();
        }
    }

}
