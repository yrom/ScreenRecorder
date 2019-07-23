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

import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.media.MediaFormat.MIMETYPE_AUDIO_AAC;
import static android.media.MediaFormat.MIMETYPE_VIDEO_AVC;

/**
 * @author Yrom
 */
public class ScreenRecorder {
    private static final String TAG = "ScreenRecorder";
    private static final boolean VERBOSE = false;
    private static final int INVALID_INDEX = -1;
    static final String VIDEO_AVC = MIMETYPE_VIDEO_AVC; // H.264 Advanced Video Coding
    static final String AUDIO_AAC = MIMETYPE_AUDIO_AAC; // H.264 Advanced Audio Coding
    private String mDstPath;
    private VideoEncoder mVideoEncoder;
    private MicRecorder mAudioEncoder;

    private MediaFormat mVideoOutputFormat = null, mAudioOutputFormat = null;
    private int mVideoTrackIndex = INVALID_INDEX, mAudioTrackIndex = INVALID_INDEX;
    private MediaMuxer mMuxer;
    private boolean mMuxerStarted = false;

    private AtomicBoolean mForceQuit = new AtomicBoolean(false);
    private AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private VirtualDisplay mVirtualDisplay;

    private HandlerThread mWorker;
    private CallbackHandler mHandler;

    private Callback mCallback;
    private LinkedList<Integer> mPendingVideoEncoderBufferIndices = new LinkedList<>();
    private LinkedList<Integer> mPendingAudioEncoderBufferIndices = new LinkedList<>();
    private LinkedList<MediaCodec.BufferInfo> mPendingAudioEncoderBufferInfos = new LinkedList<>();
    private LinkedList<MediaCodec.BufferInfo> mPendingVideoEncoderBufferInfos = new LinkedList<>();

    /**
     * @param display for {@link VirtualDisplay#setSurface(Surface)}
     * @param dstPath saving path
     */
    public ScreenRecorder(VideoEncodeConfig video,
                          AudioEncodeConfig audio,
                          VirtualDisplay display,
                          String dstPath) {
        mVirtualDisplay = display;
        mDstPath = dstPath;
        mVideoEncoder = new VideoEncoder(video);
        mAudioEncoder = audio == null ? null : new MicRecorder(audio);
    }

    /**
     * stop task
     */
    public final void quit() {
        mForceQuit.set(true);
        if (!mIsRunning.get()) {
            release();
        } else {
            signalStop(false);
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

    private static final int MSG_START = 0;
    private static final int MSG_STOP = 1;
    private static final int MSG_ERROR = 2;
    private static final int STOP_WITH_EOS = 1;

    private class CallbackHandler extends Handler {
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
                case MSG_STOP:
                case MSG_ERROR:
                    stopEncoders();
                    if (msg.arg1 != STOP_WITH_EOS) signalEndOfStream();
                    if (mCallback != null) {
                        mCallback.onStop((Throwable) msg.obj);
                    }
                    release();
                    break;
            }
        }
    }

    private void signalEndOfStream() {
        MediaCodec.BufferInfo eos = new MediaCodec.BufferInfo();
        ByteBuffer buffer = ByteBuffer.allocate(0);
        eos.set(0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        if (VERBOSE) Log.i(TAG, "Signal EOS to muxer ");
        if (mVideoTrackIndex != INVALID_INDEX) {
            writeSampleData(mVideoTrackIndex, eos, buffer);
        }
        if (mAudioTrackIndex != INVALID_INDEX) {
            writeSampleData(mAudioTrackIndex, eos, buffer);
        }
        mVideoTrackIndex = INVALID_INDEX;
        mAudioTrackIndex = INVALID_INDEX;
    }

    private void record() {
        if (mIsRunning.get() || mForceQuit.get()) {
            throw new IllegalStateException();
        }
        if (mVirtualDisplay == null) {
            throw new IllegalStateException("maybe release");
        }
        mIsRunning.set(true);

        try {
            // create muxer
            mMuxer = new MediaMuxer(mDstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            // create encoder and input surface
            prepareVideoEncoder();
            prepareAudioEncoder();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // "turn on" VirtualDisplay after VideoEncoder prepared
        mVirtualDisplay.setSurface(mVideoEncoder.getInputSurface());
        if (VERBOSE) Log.d(TAG, "set surface to display: " + mVirtualDisplay.getDisplay());
    }

    private void muxVideo(int index, MediaCodec.BufferInfo buffer) {
        if (!mIsRunning.get()) {
            Log.w(TAG, "muxVideo: Already stopped!");
            return;
        }
        if (!mMuxerStarted || mVideoTrackIndex == INVALID_INDEX) {
            mPendingVideoEncoderBufferIndices.add(index);
            mPendingVideoEncoderBufferInfos.add(buffer);
            return;
        }
        ByteBuffer encodedData = mVideoEncoder.getOutputBuffer(index);
        writeSampleData(mVideoTrackIndex, buffer, encodedData);
        mVideoEncoder.releaseOutputBuffer(index);
        if ((buffer.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            if (VERBOSE)
                Log.d(TAG, "Stop encoder and muxer, since the buffer has been marked with EOS");
            // send release msg
            mVideoTrackIndex = INVALID_INDEX;
            signalStop(true);
        }
    }


    private void muxAudio(int index, MediaCodec.BufferInfo buffer) {
        if (!mIsRunning.get()) {
            Log.w(TAG, "muxAudio: Already stopped!");
            return;
        }
        if (!mMuxerStarted || mAudioTrackIndex == INVALID_INDEX) {
            mPendingAudioEncoderBufferIndices.add(index);
            mPendingAudioEncoderBufferInfos.add(buffer);
            return;

        }
        ByteBuffer encodedData = mAudioEncoder.getOutputBuffer(index);
        writeSampleData(mAudioTrackIndex, buffer, encodedData);
        mAudioEncoder.releaseOutputBuffer(index);
        if ((buffer.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            if (VERBOSE)
                Log.d(TAG, "Stop encoder and muxer, since the buffer has been marked with EOS");
            mAudioTrackIndex = INVALID_INDEX;
            signalStop(true);
        }
    }

    private void writeSampleData(int track, MediaCodec.BufferInfo buffer, ByteBuffer encodedData) {
        if ((buffer.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // The codec config data was pulled out and fed to the muxer when we got
            // the INFO_OUTPUT_FORMAT_CHANGED status.
            // Ignore it.
            if (VERBOSE) Log.d(TAG, "Ignoring BUFFER_FLAG_CODEC_CONFIG");
            buffer.size = 0;
        }
        boolean eos = (buffer.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        if (buffer.size == 0 && !eos) {
            if (VERBOSE) Log.d(TAG, "info.size == 0, drop it.");
            encodedData = null;
        } else {
            if (buffer.presentationTimeUs != 0) { // maybe 0 if eos
                if (track == mVideoTrackIndex) {
                    resetVideoPts(buffer);
                } else if (track == mAudioTrackIndex) {
                    resetAudioPts(buffer);
                }
            }
            if (VERBOSE)
                Log.d(TAG, "[" + Thread.currentThread().getId() + "] Got buffer, track=" + track
                        + ", info: size=" + buffer.size
                        + ", presentationTimeUs=" + buffer.presentationTimeUs);
            if (!eos && mCallback != null) {
                mCallback.onRecording(buffer.presentationTimeUs);
            }
        }
        if (encodedData != null) {
            encodedData.position(buffer.offset);
            encodedData.limit(buffer.offset + buffer.size);
            mMuxer.writeSampleData(track, encodedData, buffer);
            if (VERBOSE)
                Log.i(TAG, "Sent " + buffer.size + " bytes to MediaMuxer on track " + track);
        }
    }

    private long mVideoPtsOffset, mAudioPtsOffset;

    private void resetAudioPts(MediaCodec.BufferInfo buffer) {
        if (mAudioPtsOffset == 0) {
            mAudioPtsOffset = buffer.presentationTimeUs;
            buffer.presentationTimeUs = 0;
        } else {
            buffer.presentationTimeUs -= mAudioPtsOffset;
        }
    }

    private void resetVideoPts(MediaCodec.BufferInfo buffer) {
        if (mVideoPtsOffset == 0) {
            mVideoPtsOffset = buffer.presentationTimeUs;
            buffer.presentationTimeUs = 0;
        } else {
            buffer.presentationTimeUs -= mVideoPtsOffset;
        }
    }

    private void resetVideoOutputFormat(MediaFormat newFormat) {
        // should happen before receiving buffers, and should only happen once
        if (mVideoTrackIndex >= 0 || mMuxerStarted) {
            throw new IllegalStateException("output format already changed!");
        }
        if (VERBOSE)
            Log.i(TAG, "Video output format changed.\n New format: " + newFormat.toString());
        mVideoOutputFormat = newFormat;
    }

    private void resetAudioOutputFormat(MediaFormat newFormat) {
        // should happen before receiving buffers, and should only happen once
        if (mAudioTrackIndex >= 0 || mMuxerStarted) {
            throw new IllegalStateException("output format already changed!");
        }
        if (VERBOSE)
            Log.i(TAG, "Audio output format changed.\n New format: " + newFormat.toString());
        mAudioOutputFormat = newFormat;
    }

    private void startMuxerIfReady() {
        if (mMuxerStarted || mVideoOutputFormat == null
                || (mAudioEncoder != null && mAudioOutputFormat == null)) {
            return;
        }

        mVideoTrackIndex = mMuxer.addTrack(mVideoOutputFormat);
        mAudioTrackIndex = mAudioEncoder == null ? INVALID_INDEX : mMuxer.addTrack(mAudioOutputFormat);
        mMuxer.start();
        mMuxerStarted = true;
        if (VERBOSE) Log.i(TAG, "Started media muxer, videoIndex=" + mVideoTrackIndex);
        if (mPendingVideoEncoderBufferIndices.isEmpty() && mPendingAudioEncoderBufferIndices.isEmpty()) {
            return;
        }
        if (VERBOSE) Log.i(TAG, "Mux pending video output buffers...");
        MediaCodec.BufferInfo info;
        while ((info = mPendingVideoEncoderBufferInfos.poll()) != null) {
            int index = mPendingVideoEncoderBufferIndices.poll();
            muxVideo(index, info);
        }
        if (mAudioEncoder != null) {
            while ((info = mPendingAudioEncoderBufferInfos.poll()) != null) {
                int index = mPendingAudioEncoderBufferIndices.poll();
                muxAudio(index, info);
            }
        }
        if (VERBOSE) Log.i(TAG, "Mux pending video output buffers done.");
    }

    // @WorkerThread
    private void prepareVideoEncoder() throws IOException {
        VideoEncoder.Callback callback = new VideoEncoder.Callback() {
            boolean ranIntoError = false;

            @Override
            public void onOutputBufferAvailable(BaseEncoder codec, int index, MediaCodec.BufferInfo info) {
                if (VERBOSE) Log.i(TAG, "VideoEncoder output buffer available: index=" + index);
                try {
                    muxVideo(index, info);
                } catch (Exception e) {
                    Log.e(TAG, "Muxer encountered an error! ", e);
                    Message.obtain(mHandler, MSG_ERROR, e).sendToTarget();
                }
            }

            @Override
            public void onError(Encoder codec, Exception e) {
                ranIntoError = true;
                Log.e(TAG, "VideoEncoder ran into an error! ", e);
                Message.obtain(mHandler, MSG_ERROR, e).sendToTarget();
            }

            @Override
            public void onOutputFormatChanged(BaseEncoder codec, MediaFormat format) {
                resetVideoOutputFormat(format);
                startMuxerIfReady();
            }
        };
        mVideoEncoder.setCallback(callback);
        mVideoEncoder.prepare();
    }

    private void prepareAudioEncoder() throws IOException {
        final MicRecorder micRecorder = mAudioEncoder;
        if (micRecorder == null) return;
        AudioEncoder.Callback callback = new AudioEncoder.Callback() {
            boolean ranIntoError = false;

            @Override
            public void onOutputBufferAvailable(BaseEncoder codec, int index, MediaCodec.BufferInfo info) {
                if (VERBOSE)
                    Log.i(TAG, "[" + Thread.currentThread().getId() + "] AudioEncoder output buffer available: index=" + index);
                try {
                    muxAudio(index, info);
                } catch (Exception e) {
                    Log.e(TAG, "Muxer encountered an error! ", e);
                    Message.obtain(mHandler, MSG_ERROR, e).sendToTarget();
                }
            }

            @Override
            public void onOutputFormatChanged(BaseEncoder codec, MediaFormat format) {
                if (VERBOSE)
                    Log.d(TAG, "[" + Thread.currentThread().getId() + "] AudioEncoder returned new format " + format);
                resetAudioOutputFormat(format);
                startMuxerIfReady();
            }

            @Override
            public void onError(Encoder codec, Exception e) {
                ranIntoError = true;
                Log.e(TAG, "MicRecorder ran into an error! ", e);
                Message.obtain(mHandler, MSG_ERROR, e).sendToTarget();
            }


        };
        micRecorder.setCallback(callback);
        micRecorder.prepare();
    }

    private void signalStop(boolean stopWithEOS) {
        Message msg = Message.obtain(mHandler, MSG_STOP, stopWithEOS ? STOP_WITH_EOS : 0, 0);
        mHandler.sendMessageAtFrontOfQueue(msg);
    }

    private void stopEncoders() {
        mIsRunning.set(false);
        mPendingAudioEncoderBufferInfos.clear();
        mPendingAudioEncoderBufferIndices.clear();
        mPendingVideoEncoderBufferInfos.clear();
        mPendingVideoEncoderBufferIndices.clear();
        // maybe called on an error has been occurred
        try {
            if (mVideoEncoder != null) mVideoEncoder.stop();
        } catch (IllegalStateException e) {
            // ignored
        }
        try {
            if (mAudioEncoder != null) mAudioEncoder.stop();
        } catch (IllegalStateException e) {
            // ignored
        }

    }

    private void release() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.setSurface(null);
            mVirtualDisplay = null;
        }

        mVideoOutputFormat = mAudioOutputFormat = null;
        mVideoTrackIndex = mAudioTrackIndex = INVALID_INDEX;
        mMuxerStarted = false;

        if (mWorker != null) {
            mWorker.quitSafely();
            mWorker = null;
        }
        if (mVideoEncoder != null) {
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
        if (mAudioEncoder != null) {
            mAudioEncoder.release();
            mAudioEncoder = null;
        }

        if (mMuxer != null) {
            try {
                mMuxer.stop();
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
        if (mVirtualDisplay != null) {
            Log.e(TAG, "release() not called!");
            release();
        }
    }

}
