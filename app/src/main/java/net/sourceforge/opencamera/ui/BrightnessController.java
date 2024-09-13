package net.sourceforge.opencamera.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import net.sourceforge.opencamera.R;
import net.sourceforge.opencamera.preview.ApplicationInterface;

public class BrightnessController {


    public BrightnessController(Activity activity, ApplicationInterface applicationInterface) {
        this.activity = activity;
        this.applicationInterface = applicationInterface;
        this.handlerThread = new HandlerThread("BrightnessController BackgroundThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    private final Activity activity;
    private final ApplicationInterface applicationInterface;
    private final HandlerThread handlerThread;
    private final Handler handler;
    private Runnable dimScreenRunnable;
    private final long longPressTimeout = 1500L;
    private final long delayMillis = 5000L;

    public void setScreenBrightness( float brightness) {
        Window window = activity.getWindow();
        WindowManager.LayoutParams layoutParams = window.getAttributes();
        // 设置亮度
        layoutParams.screenBrightness = brightness;
        window.setAttributes(layoutParams);
    }

    public void delaySetScreenBrightness(float brightness, long delayMillis) {
        if (dimScreenRunnable == null) {
            dimScreenRunnable = () -> {
                this.setScreenBrightness(brightness);
            };
        }
        handler.postDelayed(dimScreenRunnable, delayMillis);
    }

    /**
     *
     */
    public void recoverScreenBrightness() {
        // 替换为你的相机视图ID
        View cameraView = activity.findViewById(R.id.locker);
        cameraView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
//                    if (dimScreenRunnable != null) {
//                        // 移除之前的延迟任务
//                        handler.removeCallbacks(dimScreenRunnable);
//                    }
                    return true;
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (event.getEventTime() - event.getDownTime() > longPressTimeout) {
                        // 设置到系统默认值
                        setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
                        // 只有在预录、录像情况下才需要熄屏
                        handler.postDelayed(dimScreenRunnable, delayMillis);
                    } else {
                        // 小于默认值还在操作 就重新延长
                        handler.removeCallbacks(dimScreenRunnable);
                        handler.postDelayed(dimScreenRunnable, delayMillis);
                    }
                    return true;
                }
                return false;
            }
        });
    }

}
