package net.sourceforge.opencamera.ui;

import static net.sourceforge.opencamera.MyApplicationInterface.PRE_REC;
import static net.sourceforge.opencamera.MyApplicationInterface.REC;
import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import net.sourceforge.opencamera.R;
import net.sourceforge.opencamera.preview.ApplicationInterface;

/**
 * 屏幕亮度控制类
 */
public class BrightnessController {

    public BrightnessController(Activity activity, ApplicationInterface applicationInterface) {
        this.activity = activity;
        this.applicationInterface = applicationInterface;
        HandlerThread handlerThread = new HandlerThread("BrightnessController BackgroundThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    private final Activity activity;
    private final ApplicationInterface applicationInterface;
    private final Handler handler;
    // todo ccg 可以配置化
    /**
     * 多少秒降低亮度
     */
    private static final long DELAY_MILLIS = 30000L;

    private final Runnable screenMinBrightnessRunnable = () -> {
        // 默认最低亮度
        float minBrightness = 0;
        this.setScreenBrightness(minBrightness);
    };

    private void setScreenBrightness(float brightness) {
        activity.runOnUiThread(() -> {
            Window window = activity.getWindow();
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            // 设置亮度
            layoutParams.screenBrightness = brightness;
            window.setAttributes(layoutParams);
        });
    }

    public void resetScreenBrightness() {
        handler.removeCallbacksAndMessages(null);
        setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
    }

    /**
     * 延迟调低亮度
     */
    public void delaySetScreenMinBrightness() {
        handler.postDelayed(screenMinBrightnessRunnable, DELAY_MILLIS);
    }

    /**
     * 点击屏幕 恢复亮度 无操作后再降低亮度
     */
    public void recoverScreenBrightness() {
        // 替换为你的相机视图ID
        View cameraView = activity.findViewById(R.id.brightness);
        cameraView.setOnTouchListener(null);
        cameraView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    if (applicationInterface.getPreRecordingStatus() != REC && applicationInterface.getPreRecordingStatus() != PRE_REC) {
                        // 不是处理预录中 不调整亮度
                        Window window = activity.getWindow();
                        WindowManager.LayoutParams layoutParams = window.getAttributes();
                        // 不是默认值 则需要重新重设亮度值
                        if (layoutParams.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                            setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
                        }
                        return false;
                    }

                    setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
                    handler.removeCallbacksAndMessages(null);
                    // 只有在预录、录像情况下才需要熄屏
                    delaySetScreenMinBrightness();
                }
                return false;
            }
        });
    }

}
