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
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.cameracontroller.CameraController;
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
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class VideoPreRecorder {

    private CameraController camera_controller;

    /**
     * 预览
     */
//    public SurfaceView previewSurfaceView;
//    public TextureView previewSurfaceView;

    public Surface previewSurface;

    public CameraDevice mCameraDevice;
    public Size size = new Size(2560, 1440);

    List<Surface> surfaces = new ArrayList<>();
    private CaptureRequest.Builder mPreviewBuilder;
    private CameraCaptureSession mCameraCaptureSession;

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
    private int count = 0;
    private int vdCount = 0;

    // 目前分辨率 码率下  200 约等于5秒
    CircularBuffer<VideosCacheData> circularBuffer = new CircularBuffer<>(200 * 15);
    // 正式录制的数据缓存队列
    private final BlockingDeque<VideosCacheData> fifoQueue = new LinkedBlockingDeque<>();
    private final Object queueLock = new Object();

    private HandlerThread handlerThread = new HandlerThread("ccg handlerThread");
    private Handler handler1;

    private HandlerThread handlerThread2 = new HandlerThread("ccg handlerThread2");
    private Handler handler2;

//    private HandlerThread handlerThread3 = new HandlerThread("ccg handlerThread3");
//    private Handler handler3;

    private HandlerThread handlerThread4 = new HandlerThread("ccg handlerThread4");
    private Handler handler4;


    private String videoName;
//    private boolean preVideosDataSaveDone;
    private CountDownLatch preVideosDataSaveDoneLatch;

    private boolean cameraEnable = true;

    public void startPreRecord(MainActivity activity, VideoProfile videoProfile, FileDescriptor fd) {

        try {
            // 需要设置，
            this.cameraEnable = true;

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

//            if (!handlerThread3.isAlive()) {
//                handlerThread3.start();
//                handler3 = new Handler(handlerThread3.getLooper());
//            }

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
//            int audioBitRate = 64000;
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
            mediaMuxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            // 视频输出方向顺时针转90度
            mediaMuxer.setOrientationHint(90);

            // session
//            Surface previewSurface = previewSurfaceView.getHolder().getSurface();

            // 设置 SurfaceTextureListener
            List<Surface> surfaces = Arrays.asList(previewSurface, inputSurface);

            CaptureRequest.Builder captureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(previewSurface);
            captureRequestBuilder.addTarget(inputSurface);

            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    mCameraCaptureSession = session;
                    try {
                        mCameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, handler4);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    // 处理配置失败
                    System.out.println("startPreRecordingCameraSession config error");
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

                    Long time = 0L;
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
//                            System.out.println("---ccg INFO_TRY_AGAIN_LATER");
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
//                                System.out.println("------ccg timestamp 1 ----" +  System.currentTimeMillis() +  "---" + bufferInfo.presentationTimeUs);
                                if (isRecording == PRE_RECORDING) {
                                    circularBuffer.add(videosCacheData);
                                } else {
                                    count++;
                                    fifoQueue.add(videosCacheData);
                                }
                            }
                            // 释放缓冲区以便重用
                            videosMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                            // 检查 bufferInfo.flags 是否包含 MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                System.out.println("ccg 如果结束标志被设置，退出循环");
//                                break;  // 如果结束标志被设置，退出循环
                            }
                        } else {
                            System.out.println("---ccg index  others ------" + outputBufferIndex);
                        }

                        //---------------------------------------
                        // 处理音频相关内容
                        //---------------------------------------
                        if (audioOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                            System.out.println("---ccg INFO_TRY_AGAIN_LATER");
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
                                    //                            System.out.println("------ccg timestamp 2 ----" +  System.currentTimeMillis() +  "---" + bufferInfo2.presentationTimeUs);
                                    if (isRecording == PRE_RECORDING) {
                                        circularBuffer.add(audiosCacheData);
                                    } else {
                                        vdCount++;
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
                        } else {
//                            System.out.println(audioInputBufferIndex);
                        }

                    }

                } catch (Exception e) {
                    e.printStackTrace();
//                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public void startNewRecording(Activity activity) {
        handler2.post(() -> {
            isRecording = RECORDING;
            preVideosDataSaveDoneLatch = new CountDownLatch(1);

            try {
                for (VideosCacheData videosCacheData : circularBuffer.getAll()) {
                    MediaCodec.BufferInfo bufferInfo = videosCacheData.getBufferInfo();
                    System.out.println(videosCacheData.getTrackIndex() + "-----ccg circularBuffer ----" + bufferInfo.presentationTimeUs);
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
//                    System.out.println(videosCacheData.getTrackIndex() + "-----ccg fifoQueue ----" + videosCacheData.getBufferInfo().presentationTimeUs);
                    mediaMuxer.writeSampleData(videosCacheData.getTrackIndex(), videosCacheData.getEncodedByteBuffer(), videosCacheData.getBufferInfo());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
    }


    // sotp   isRecording 两个缓存要清除 其他资源要关闭

    /**
     * 停止录制
     */
    public void stopNewRecording(MainActivity activity) {
        // 暂时不需要 handle 异步
//        handler3.post(() -> {

            try {
                // 等待预录数据全部保存，
                preVideosDataSaveDoneLatch.await();

                isRecording = STOP_RECORDING;
                // 不需要关闭
//                handlerThread2.quitSafely();
//                System.out.println("------ccg  预录size:" + circularBuffer.size());
//                System.out.println("-------ccg 录像count " + count);
//                System.out.println("--------ccg 音频vdcount " + vdCount);
                try {
//                    audioRecord.stop();
                    audioRecord.release();
                    audioMediaCodec.release();
                    videosMediaCodec.release();
    //            mediaCodec.stop();
                    // 需要释放否则文件不全
                    mediaMuxer.release();
    //            mediaMuxer.stop();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                surfaces.clear();
                circularBuffer.clear();
                fifoQueue.clear();
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
                e.printStackTrace();
                // 中止了 结束后 需要再设置为true
                this.cameraEnable = true;
            }

//        });

    }

    public VideoPreRecorder(CameraController camera_controller) {
        this.camera_controller = camera_controller;
        camera_controller.initVideoPreRecorder(this);
    }

    /**
     * 底层相机出现异常时要中断数据缓存。
     */
    public void onCameraError() {
        this.cameraEnable = false;
    }
}
