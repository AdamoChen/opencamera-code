package net.sourceforge.opencamera.videosprerecord;

import android.annotation.SuppressLint;
import android.util.Log;

import java.util.LinkedList;
import java.util.List;

/**
 * 循环缓存容器
 */
public class CircularBuffer {

    private final LinkedList<VideosCacheData> linkedList = new LinkedList<>();
    private long preRecordingDurationUs = 45 * 1_000_000;

    public CircularBuffer(long preRecordingDurationUs) {
        if (preRecordingDurationUs > 0) {
            this.preRecordingDurationUs = preRecordingDurationUs;
        }
    }

    /**
     * 增加并延迟过期数据
     * @param item
     */
    public void addAndRemoveExpireData(VideosCacheData item) {
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
            Log.e("add&RemoveExpireData er", String.valueOf(e));
        }
    }

    /**
     *
     * @param durationSecs 秒数
     */
    @SuppressLint("LongLogTag")
    public void setPreRecordingDurationUs(int durationSecs) {
        if (durationSecs <= 0) {
            Log.e("durationSecs must be bigger than 0", String.valueOf(durationSecs));
            return;
        }
        this.preRecordingDurationUs = durationSecs * 1_000_000L;
    }

    public List<VideosCacheData> getAll() {
        return linkedList;
    }

    public boolean clear() {
        linkedList.clear();
        return true;
    }

}
