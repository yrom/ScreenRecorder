package net.yrom.screenrecorder.floatball;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * Created by chao on 18-2-2.
 */

public class FloatHelper {

    private static FloatBall floatBall;

    public static void init(Context context){
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            Intent intent = new Intent(context, RequestOverlayActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } else {
            addToWindow(context);
        }
    }

    public static void addToWindow(Context context){
        floatBall = new FloatBall(context);
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = WindowManager.LayoutParams.TYPE_PHONE;
        params.format = PixelFormat.RGBA_8888;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.END | Gravity.TOP;
        params.x = 0;
        params.y = context.getResources().getDisplayMetrics().heightPixels / 2;
        if (windowManager != null) {
            windowManager.addView(floatBall, params);
        }
    }

    public static void clear(Context context){
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null && floatBall != null) {
            windowManager.removeView(floatBall);
            floatBall = null;
        }
    }
}
