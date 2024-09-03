package net.sourceforge.opencamera.videosprerecord;

import android.media.MediaCodec;

import java.nio.ByteBuffer;

/**
 * 音视频缓存数据实体
 */
public class VideosCacheData {

    private MediaCodec.BufferInfo bufferInfo;
    private ByteBuffer encodedByteBuffer;
    private int trackIndex;

    public VideosCacheData(MediaCodec.BufferInfo bufferInfo, ByteBuffer encodedByteBuffer, int trackIndex) {
        this.bufferInfo = bufferInfo;
        this.encodedByteBuffer = encodedByteBuffer;
        this.trackIndex = trackIndex;
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
