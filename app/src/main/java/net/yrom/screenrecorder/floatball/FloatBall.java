package net.yrom.screenrecorder.floatball;

import android.content.Context;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import net.yrom.screenrecorder.MainActivity;
import net.yrom.screenrecorder.R;
import net.yrom.screenrecorder.Utils;

/**
 * Created by chao on 18-2-2.
 */

public class FloatBall extends FrameLayout{

    private int mStatusHeight;

    private boolean mIsBig = true;

    public FloatBall(Context context) {
        super(context);
        mStatusHeight = Utils.getStatusBarHeight(context);
        inflate(context, R.layout.layout_float_ball, this);
        final View ivBig = findViewById(R.id.iv_big);
        final View ivSmall = findViewById(R.id.iv_small);
        setOnClickListener(view -> {
            mIsBig = !mIsBig;
            ivBig.setVisibility(mIsBig ? VISIBLE : GONE);
            ivSmall.setVisibility(!mIsBig ? VISIBLE : GONE);
            if(mIsBig){
                context.sendBroadcast(new Intent(MainActivity.ACTION_STOP));
            }else{
                context.sendBroadcast(new Intent(MainActivity.ACTION_START));
            }
        });
    }

    float mInnerY, mRawY;

    long time;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mInnerY = event.getY();
                mRawY = event.getRawY();
                time = System.currentTimeMillis();
                break;
            case MotionEvent.ACTION_MOVE:
                float y = event.getRawY();
                moveTo(y - mInnerY - mStatusHeight);
                break;
            case MotionEvent.ACTION_UP:
                long delay = System.currentTimeMillis() - time;
                int move = (int) Math.abs(event.getRawY() - mRawY);
                if(move < 10) {
                    if (delay < 300){
                        performClick();
                    }
                }
                break;
        }
        return true;
    }

    private void moveTo(float y){
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) getLayoutParams();
        params.y = (int) y;
        ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(this, params);
    }

}
