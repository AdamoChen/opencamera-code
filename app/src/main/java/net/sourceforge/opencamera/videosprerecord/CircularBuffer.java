package net.sourceforge.opencamera.videosprerecord;

import android.annotation.SuppressLint;
import android.util.Log;

import java.util.LinkedList;
import java.util.List;

/**
 * 循环缓存容器
 */
public class CircularBuffer {

    private final String TAG = "CircularBuffer";

    private int hasAddDataCount;
    private final LinkedList<VideosCacheData> linkedList = new LinkedList<>();
    private long preRecordingDurationUs = 45 * 1_000_000;

    public CircularBuffer(long preRecordingDurationUs) {
        if (preRecordingDurationUs > 0) {
            this.preRecordingDurationUs = preRecordingDurationUs;
        }
    }

    /**
     * 增加并延迟过期数据
     *
     * @param item
     */
    public void addAndRemoveExpireData(VideosCacheData item) {
        // 减少预录开始第一帧使用了 1x 画面的问题 暂时这样处理
        if (hasAddDataCount < 32) {
            hasAddDataCount++;
            return;
        }

        linkedList.add(item);
        try {
            while (!linkedList.isEmpty()) {
                if (item.getPresentationTimeUs() - linkedList.getFirst().getPresentationTimeUs() > preRecordingDurationUs) {
                    linkedList.removeFirst();
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "addAndRemoveExpireData error", e);
        }
    }

    /**
     * @param durationSecs 秒数
     */
    @SuppressLint("LongLogTag")
    public void setPreRecordingDurationUs(int durationSecs) {
        if (durationSecs <= 0) {
            Log.e(TAG, "durationSecs must be bigger than 0");
            return;
        }
        this.preRecordingDurationUs = durationSecs * 1_000_000L;
    }

    public List<VideosCacheData> getAll() {
        return linkedList;
    }

    public boolean clear() {
        hasAddDataCount = 0;
        linkedList.clear();
        return true;
    }

}
