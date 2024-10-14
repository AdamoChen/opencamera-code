package net.sourceforge.opencamera.videosprerecord;

import android.app.Activity;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.k2fsa.sherpa.onnx.KeyWordsSpottingAction;
import com.k2fsa.sherpa.onnx.kws.KeyWordsSpottingController;

import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.R;
import net.sourceforge.opencamera.cameracontroller.CameraController;
import net.sourceforge.opencamera.cameracontroller.CameraController2;
import net.sourceforge.opencamera.preview.ApplicationInterface;
import net.sourceforge.opencamera.preview.VideoProfile;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * 视频预录器
 */
public class VideoPreRecorder {

    private final String TAG = "VideoPreRecorder";

    private final CameraController2 cameraController2;
    private final ApplicationInterface applicationInterface;
    /**
     * 预览
     */
    public Surface previewSurface;
    public CameraDevice mCameraDevice;
    public CaptureRequest.Builder captureRequestBuilder;
    public Size size = new Size(2560, 1440);
    List<Surface> surfaces = new ArrayList<>();
    private MediaCodec videosMediaCodec;
    private MediaCodec audioMediaCodec;
    AudioRecord audioRecord;

    private MediaMuxer mediaMuxer;
    // 缓存视频surface
    private Surface inputSurface;
    // 预录的数据缓存

    // 定义code
    private final int STOP_RECORDING = 0;
    private final int PRE_RECORDING = 1;
    private final int RECORDING = 2;
    private int isRecording = STOP_RECORDING;
    // -1 表示缓存默认时长的数据
    CircularBuffer circularBuffer = new CircularBuffer(-1);
    // 正式录制的数据缓存队列
    private final BlockingDeque<VideosCacheData> fifoQueue = new LinkedBlockingDeque<>();

    private final HandlerThread handlerThread = new HandlerThread("pre-recording handlerThread");
    private Handler handler1;

    private final HandlerThread handlerThread2 = new HandlerThread("recording handlerThread2");
    private Handler handler2;

    private final HandlerThread handlerThread4 = new HandlerThread("CameraCaptureSession handlerThread4");
    private Handler handler4;

    private CountDownLatch preVideosDataSaveDoneLatch;
    // 相机是否可用的标志
    private boolean cameraEnable = true;
    private KeyWordsSpottingController keyWordsSpottingController;

    public void startPreRecord(MainActivity activity, VideoProfile videoProfile, FileDescriptor fd) {

        try {
            // 需要设置，
            this.cameraEnable = true;
            // 设置预录秒数
            this.circularBuffer.setPreRecordingDurationUs(applicationInterface.getVideoPreRecordingSecsPref());

            if (keyWordsSpottingController != null) {
                keyWordsSpottingController.startKeyWordsSpotting(activity, new KeyWordsSpottingAction() {
                    @Override
                    public void startRecording() {
                        activity.runOnUiThread(() -> {
                            if (isRecording == PRE_RECORDING) {
                                View takePhotoButton = activity.findViewById(R.id.take_photo);
                                takePhotoButton.performClick();
                            }
                        });
                    }

                    @Override
                    public void stopRecording() {
                        activity.runOnUiThread(() -> {
                            if (isRecording == RECORDING) {
                                View takePhotoButton = activity.findViewById(R.id.take_photo);
                                takePhotoButton.performClick();
                            }
                        });
                    }
                });
            }

            if (!handlerThread.isAlive()) {
                handlerThread.start();
                // 从相机中获取数据 丢进缓存中
                handler1 = new Handler(handlerThread.getLooper());
            }

            if (!handlerThread2.isAlive()) {
                handlerThread2.start();
                // 开始正式录像后 循环从队列中取数据
                handler2 = new Handler(handlerThread2.getLooper());
            }

            if (!handlerThread4.isAlive()) {
                handlerThread4.start();
                // createCaptureSession 所需要的handle
                handler4 = new Handler(handlerThread4.getLooper());
            }

            if (videoProfile.videoFrameHeight != 0 && videoProfile.videoFrameWidth != 0) {
                size = new Size(videoProfile.videoFrameWidth, videoProfile.videoFrameHeight);
            }

            int frameRate = 60;
            if (videoProfile.videoFrameRate != 0) {
                frameRate = videoProfile.videoFrameRate;
            }

            int sampleRate = 44100;
            int audioBitRate = videoProfile.audioBitRate;

            videosMediaCodec = MediaCodec.createEncoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", size.getWidth(), size.getHeight());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, size.getWidth() * size.getHeight() * 3 / 2);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            videosMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = videosMediaCodec.createInputSurface();
            videosMediaCodec.start();

            // 配置音频编码器
            MediaFormat audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, 1);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate);  // 设置比特率为 64 kbps

            // 创建并配置音频编码器
            audioMediaCodec = MediaCodec.createEncoderByType("audio/mp4a-latm");
            audioMediaCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioMediaCodec.start();

            // 初始化 MediaMuxer
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mediaMuxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }
            // 视频输出方向顺时针转90度
            mediaMuxer.setOrientationHint(90);

            // 设置 SurfaceTextureListener
            List<Surface> surfaces = Arrays.asList(previewSurface, inputSurface);

            captureRequestBuilder.addTarget(previewSurface);
            captureRequestBuilder.addTarget(inputSurface);

            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    try {
                        session.setRepeatingRequest(captureRequestBuilder.build(), null, handler4);
                        cameraController2.setCaptureSession(session);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "startPreRecord", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    // 处理配置失败
                    Log.e(TAG,"onConfigureFailed error");
                }
            }, null);

            int minBufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            // 开始音频录制
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize);
            audioRecord.startRecording();

            handler1.post(() -> {
                try {
                    isRecording = PRE_RECORDING;
                    int trackIndex = 0;
                    int audioTrackIndex = 1;

                    long videosBaseTimeUs = 0;
                    long audiosBaseTimeUs = videosBaseTimeUs;

                    while (isRecording == PRE_RECORDING || isRecording == RECORDING) {
                        // 表明相机异常， 不再拿无用的数据
                        if (!this.cameraEnable) {
                            break;
                        }

                        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                        int outputBufferIndex = videosMediaCodec.dequeueOutputBuffer(bufferInfo, 10000);

                        MediaCodec.BufferInfo bufferInfo2 = new MediaCodec.BufferInfo();
                        // 获取音频数据并编码
                        int audioOutputBufferIndex = audioMediaCodec.dequeueOutputBuffer(bufferInfo2, 10000);

                        if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                            continue;
                        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // 设置混合器
                            MediaFormat trackFormat = videosMediaCodec.getOutputFormat();
                            trackIndex = mediaMuxer.addTrack(trackFormat);
                            audioTrackIndex = mediaMuxer.addTrack(audioMediaCodec.getOutputFormat());

                            mediaMuxer.start();
                        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                            //
                        } else if (outputBufferIndex >= 0) {
                            ByteBuffer encodedByteBuffer = videosMediaCodec.getOutputBuffer(outputBufferIndex);
                            if (encodedByteBuffer != null && bufferInfo.size > 0) {
                                // 限制ByteBuffer到实际的有效数据范围
                                encodedByteBuffer.position(bufferInfo.offset);
                                encodedByteBuffer.limit(bufferInfo.offset + bufferInfo.size);
                                ByteBuffer byteBufferCopy = ByteBuffer.allocate(encodedByteBuffer.remaining());
                                byteBufferCopy.put(encodedByteBuffer);
                                byteBufferCopy.flip();
                                if (videosBaseTimeUs == -1) {
                                    videosBaseTimeUs = bufferInfo.presentationTimeUs;
                                }
                                MediaCodec.BufferInfo bufferInfo1 = new MediaCodec.BufferInfo();
                                bufferInfo1.set(bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs - videosBaseTimeUs, bufferInfo.flags);
                                VideosCacheData videosCacheData = new VideosCacheData(bufferInfo1, byteBufferCopy, trackIndex);
                                if (isRecording == PRE_RECORDING) {
                                    circularBuffer.addAndRemoveExpireData(videosCacheData);
                                } else {
                                    fifoQueue.add(videosCacheData);
                                }
                            }
                            // 释放缓冲区以便重用
                            videosMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                            // 检查 bufferInfo.flags 是否包含 MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
//                                break;  // 如果结束标志被设置，退出循环
                            }
                        }
                        //---------------------------------------
                        // 处理音频相关内容
                        //---------------------------------------
                        if (audioOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                            continue;
                        } else if (audioOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                        } else if (audioOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                            //
                        } else if (audioOutputBufferIndex >= 0) {
                            ByteBuffer encodedAudioData = audioMediaCodec.getOutputBuffer(audioOutputBufferIndex);
                            if (encodedAudioData != null && bufferInfo2.size > 0) {
                                // 限制ByteBuffer到实际的有效数据范围
                                encodedAudioData.position(bufferInfo2.offset);
                                encodedAudioData.limit(bufferInfo2.offset + bufferInfo2.size);
                                ByteBuffer byteBufferCopy = ByteBuffer.allocate(encodedAudioData.remaining());
                                byteBufferCopy.put(encodedAudioData);
                                byteBufferCopy.flip();

                                if (bufferInfo2.presentationTimeUs != 0) {

                                    if (audiosBaseTimeUs == -1) {
                                        audiosBaseTimeUs = bufferInfo2.presentationTimeUs;
                                    }

                                    MediaCodec.BufferInfo bufferInfo1 = new MediaCodec.BufferInfo();
                                    bufferInfo1.set(bufferInfo2.offset, bufferInfo2.size, bufferInfo2.presentationTimeUs - audiosBaseTimeUs, bufferInfo2.flags);
                                    VideosCacheData audiosCacheData = new VideosCacheData(bufferInfo1, byteBufferCopy, audioTrackIndex);
                                    if (isRecording == PRE_RECORDING) {
                                        circularBuffer.addAndRemoveExpireData(audiosCacheData);
                                    } else {
                                        fifoQueue.add(audiosCacheData);
                                    }
                                }
//                            mediaMuxer.writeSampleData(audioTrackIndex, encodedAudioData, bufferInfo2);
                            }

                            audioMediaCodec.releaseOutputBuffer(audioOutputBufferIndex, false);
                        }

                        // 从 AudioRecord 获取音频输入数据并传递给音频编码器
                        int audioInputBufferIndex = audioMediaCodec.dequeueInputBuffer(10000);
                        if (audioInputBufferIndex >= 0) {
                            ByteBuffer inputBuffer = audioMediaCodec.getInputBuffer(audioInputBufferIndex);
                            int readBytes = audioRecord.read(inputBuffer, minBufSize);
                            if (readBytes > 0) {
                                audioMediaCodec.queueInputBuffer(audioInputBufferIndex, 0, readBytes, (System.nanoTime() / 1000), 0);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "startPreRecord PRE_RECORDING error", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "startPreRecord error", e);
        }
    }


    public void startNewRecording(Activity activity) {
        handler2.post(() -> {
            isRecording = RECORDING;
            preVideosDataSaveDoneLatch = new CountDownLatch(1);

            try {
                for (VideosCacheData videosCacheData : circularBuffer.getAll()) {
                    mediaMuxer.writeSampleData(videosCacheData.getTrackIndex(), videosCacheData.getEncodedByteBuffer(), videosCacheData.getBufferInfo());
                }
            } finally {
                // 预录数据未保存完时 就释放了组件。
                preVideosDataSaveDoneLatch.countDown();
            }

            while (isRecording == RECORDING) {
                VideosCacheData videosCacheData;
                try {
                    videosCacheData = fifoQueue.take();
                    mediaMuxer.writeSampleData(videosCacheData.getTrackIndex(), videosCacheData.getEncodedByteBuffer(), videosCacheData.getBufferInfo());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 停止录制
     */
    public void stopNewRecording(MainActivity activity) {
        try {
            // 等待预录数据全部保存，
            preVideosDataSaveDoneLatch.await();
            isRecording = STOP_RECORDING;
            // 不需要关闭
            try {
                audioRecord.release();
                audioMediaCodec.release();
                videosMediaCodec.release();
                // 需要释放否则文件不全
                mediaMuxer.release();
            } catch (Exception e) {
                Log.e(TAG, "stopNewRecording release error", e);
            }
            surfaces.clear();
            circularBuffer.clear();
            fifoQueue.clear();
            if (this.keyWordsSpottingController != null) {
                keyWordsSpottingController.stop();
            }
            // 中止了 结束后 需要再设置为true
            this.cameraEnable = true;
            /*//停止后，将视频从内部目录copy到公共目录
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);
            String fname = "ccg_ext_" + sdf.format(new Date()) + ".mp4";

            //设置保存参数到ContentValues中
            ContentValues contentValues = new ContentValues();
            //设置文件名
            contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, fname);
            //兼容Android Q和以下版本****************************
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                //android Q中不再使用DATA字段，而用RELATIVE_PATH代替
                //RELATIVE_PATH是相对路径不是绝对路径
                //关于系统文件夹可以到系统自带的文件管理器中查看，不可以写没存在的名字
                contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM");
                //contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Music/sample");
            } else {
                contentValues.put(MediaStore.Video.Media.DATA, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getPath());
            }
            //设置文件类型
            contentValues.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            //执行insert操作，向系统文件夹中添加文件
            //EXTERNAL_CONTENT_URI代表外部存储器，该值不变
            Uri uri = previewSurfaceView.getContext().getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues);
            try (OutputStream outputStream = previewSurfaceView.getContext().getContentResolver().openOutputStream(uri)) {
                File type = previewSurfaceView.getContext().getExternalFilesDir("video");
                File file = new File(type, videoName);
                Path path = Paths.get(file.getPath());
                Files.copy(path, outputStream);
                file.delete();
            } catch (Exception e) {
                e.printStackTrace();
            }*/
        } catch (Exception e) {
            // 中止了 结束后 需要再设置为true
            this.cameraEnable = true;
            Log.e(TAG, "stopNewRecording error", e);
        }
    }

    public VideoPreRecorder(CameraController cameraController2, ApplicationInterface applicationInterface) {
        this.cameraController2 = (CameraController2) cameraController2;
        this.applicationInterface = applicationInterface;
        cameraController2.initVideoPreRecorder(this);
    }

    public void initSpeechControl() {
        if (this.keyWordsSpottingController == null) {
            this.keyWordsSpottingController = new KeyWordsSpottingController();
        }
    }

    /**
     * 底层相机出现异常时要中断数据缓存。
     */
    public void onCameraError() {
        this.cameraEnable = false;
    }
}
