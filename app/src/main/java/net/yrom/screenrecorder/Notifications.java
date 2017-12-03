/*
 * Copyright (c) 2017 Yrom Wang <http://www.yrom.net>
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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.format.DateUtils;

import static net.yrom.screenrecorder.MainActivity.ACTION_STOP;

/**
 * @author yrom
 * @version 2017/12/1
 */
class Notifications {
    private final Context mContext;
    private static final int id = 0x1fff;
    private long mLastFiredTime = 0;
    Notifications(Context context) {
        this.mContext = context;
    }

    public void recording(long timeMs) {
        if (SystemClock.elapsedRealtime() - mLastFiredTime < 1000) {
            return;
        }
        NotificationManager nMan = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nMan == null) return;
        Notification notification = new Notification.Builder(mContext)
                .setOngoing(true)
                .setTicker("Recording...")
                .setContentTitle("Recording...")
                .setContentText("Length: " + DateUtils.formatElapsedTime(timeMs / 1000))
                .addAction(stopAction())
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.drawable.ic_stat_recording)
                .build();
        nMan.notify(id, notification);
        mLastFiredTime = SystemClock.elapsedRealtime();
    }

    private Notification.Action stopAction() {
        Intent intent = new Intent(ACTION_STOP).setPackage(mContext.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 1, intent, PendingIntent.FLAG_ONE_SHOT);
        return new Notification.Action(android.R.drawable.ic_media_pause, "Stop", pendingIntent);
    }

    void clear() {
        NotificationManager nMan = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nMan != null) {
            nMan.cancelAll();
        }
    }
}
