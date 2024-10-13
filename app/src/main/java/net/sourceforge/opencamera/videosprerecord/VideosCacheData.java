package net.sourceforge.opencamera.videosprerecord;

import android.media.MediaCodec;

import java.nio.ByteBuffer;

/**
 * 音视频缓存数据实体
 */
public class VideosCacheData {

    private final MediaCodec.BufferInfo bufferInfo;
    private final ByteBuffer encodedByteBuffer;
    private final int trackIndex;

    public VideosCacheData(MediaCodec.BufferInfo bufferInfo, ByteBuffer encodedByteBuffer, int trackIndex) {
        this.bufferInfo = bufferInfo;
        this.encodedByteBuffer = encodedByteBuffer;
        this.trackIndex = trackIndex;
    }

    public long getPresentationTimeUs() {
        if (bufferInfo == null) {
            return -1;
        }
        return bufferInfo.presentationTimeUs;
    }

    public MediaCodec.BufferInfo getBufferInfo() {
        return bufferInfo;
    }

    public ByteBuffer getEncodedByteBuffer() {
        return encodedByteBuffer;
    }

    public int getTrackIndex() {
        return trackIndex;
    }
}
