package com.k2fsa.sherpa.onnx.kws

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.KeyWordsSpottingAction
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getKeywordsFile
import com.k2fsa.sherpa.onnx.getKwsModelConfig
import kotlin.concurrent.thread

private const val TAG = "sherpa-onnx"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

class KeyWordsSpottingController {
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    private lateinit var kws: KeywordSpotter
    private lateinit var stream: OnlineStream
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO

    // Note: We don't use AudioFormat.ENCODING_PCM_FLOAT
    // since the AudioRecord.read(float[]) needs API level >= 23
    // but we are targeting API level >= 21
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var idx: Int = 0

    private var inited = false
    private val START_RECORDING:String = "开始录像"
    private val STOP_RECORDING:String = "结束录像"

    @Volatile
    private var isRecording: Boolean = false
        get() = field

    fun startKeyWordsSpotting(activity: Activity, keyWordsSpottingAction: KeyWordsSpottingAction) {
        if (!inited) {
            initModel(activity)
            inited = true;
        }
        start(activity, keyWordsSpottingAction)
    }

    private fun start(activity: Activity, keyWordsSpottingAction: KeyWordsSpottingAction ) {
        if (!isRecording) {
            var keywords = ""

            Log.i(TAG, keywords)
            keywords = keywords.replace("\n", "/")
            keywords = keywords.trim()
            // If keywords is an empty string, it just resets the decoding stream
            // always returns true in this case.
            // If keywords is not empty, it will create a new decoding stream with
            // the given keywords appended to the default keywords.
            // Return false if errors occurred when adding keywords, true otherwise.
            stream.release()
            stream = kws.createStream(keywords)
            if (stream.ptr == 0L) {
                Log.i(TAG, "Failed to create stream with keywords: $keywords")
                return
            }

            val ret = initMicrophone(activity)
            if (!ret) {
                Log.e(TAG, "Failed to initialize microphone")
                return
            }
            Log.i(TAG, "state: ${audioRecord?.state}")
            audioRecord!!.startRecording()
            isRecording = true
            idx = 0

            recordingThread = thread(true) {
                processSamples(activity, keyWordsSpottingAction)
            }
            Log.i(TAG, "Started recording")
        } else {
            stop()
            Log.i(TAG, "Stopped recording")
        }
    }

    fun stop() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        stream.release()
    }

    private fun processSamples(activity: Activity, keyWordsSpottingAction: KeyWordsSpottingAction) {
//        Log.i(TAG, "processing samples")

        val interval = 0.1 // i.e., 100 ms
        val bufferSize = (interval * sampleRateInHz).toInt() // in samples
        val buffer = ShortArray(bufferSize)

        while (isRecording) {
            val ret = audioRecord?.read(buffer, 0, buffer.size)
            if (ret != null && ret > 0) {
                val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate = sampleRateInHz)
                while (kws.isReady(stream)) {
                    kws.decode(stream)
                }
                val text = kws.getResult(stream).keyword
                if (text.isNotBlank()) {
                    if (START_RECORDING == text.trim()) {
                        keyWordsSpottingAction.startRecording()
                    } else if (STOP_RECORDING == text.trim()) {
                        keyWordsSpottingAction.stopRecording()
                    }
                }
            }
        }
    }

    private fun initMicrophone(activity: Activity): Boolean {
        if (ActivityCompat.checkSelfPermission(
                activity, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity, permissions, REQUEST_RECORD_AUDIO_PERMISSION
            )
            return false
        }

        val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        Log.i(
            TAG, "buffer size in milliseconds: ${numBytes * 1000.0f / sampleRateInHz}"
        )

        audioRecord = AudioRecord(
            audioSource,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            numBytes * 2 // a sample has two bytes as we are using 16-bit PCM
        )
        return true
    }

    private fun initModel(activity: Activity) {
        // Please change getKwsModelConfig() to add new models
        // See https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html
        // for a list of available models
        val type = 2
        Log.i(TAG, "Select model type $type")
        val config = KeywordSpotterConfig(
            featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
            modelConfig = getKwsModelConfig(type = type)!!,
            keywordsFile = getKeywordsFile(type = type),
        )

        kws = KeywordSpotter(
            assetManager = activity.assets,
            config = config,
        )
        stream = kws.createStream()
    }
}