package com.sieong.pingpong

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.sieong.pingpong.Game.PlayerRole
import com.sieong.pingpong.RecognizeCommands.RecognitionResult
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max

class Referee(context: Context) {

    companion object {
        private val TAG = Referee::class.java.canonicalName
        private const val SAMPLE_RATE = 16000
        private const val SAMPLE_DURATION_MS = 1000
        private const val RECORDING_LENGTH = (SAMPLE_RATE * SAMPLE_DURATION_MS / 1000)
        private const val AVERAGE_WINDOW_DURATION_MS: Long = 1000
        private const val DETECTION_THRESHOLD = 0.40f
        private const val SUPPRESSION_MS = 1500
        private const val MINIMUM_COUNT = 3
        private const val MINIMUM_TIME_BETWEEN_SAMPLES_MS: Long = 30
        private const val LABEL_FILENAME = "file:///android_asset/conv_actions_labels.txt"
        private const val MODEL_FILENAME = "file:///android_asset/conv_actions_frozen.tflite"
    }

    private val recordingBuffer = ShortArray(RECORDING_LENGTH)
    private var recordingOffset = 0
    private var shouldContinue = true
    private var shouldContinueRecognition = true
    private var recordingThread: Thread? = null
    private var recognitionThread: Thread? = null
    private val labels = mutableListOf<String>()
    private val displayedLabels = mutableListOf<String>()
    private lateinit var recognizeCommands: RecognizeCommands
    private var tfLite: Interpreter? = null
    private val recordingBufferLock = ReentrantLock()

    private var game = Game()
    private var listener: Listener? = null
    private var voiceCommandMode = false
    private var gameSummaryMode = false
    private var textToSpeech: TextToSpeech? = null
    private var lastScoreTimestamp: Long = -1

    init {
        setupTensorFlowLite(context)
        initTextToSpeech(context)
    }

    private fun initTextToSpeech(context: Context) {
        textToSpeech = TextToSpeech(context, OnInitListener { status: Int ->
            Log.d(TAG, "onInit: status=$status")
            if (status != TextToSpeech.ERROR) {
                textToSpeech?.language = Locale.UK
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        Log.d(TAG, "UtteranceProgressListener: onStart")
                        if (voiceCommandMode) {
                            toggleVoiceCommandMode(false)
                        }
                    }

                    override fun onDone(utteranceId: String) {
                        Log.d(TAG, "UtteranceProgressListener: onDone")
                        if (voiceCommandMode) {
                            toggleVoiceCommandMode(true)
                        }
                    }

                    override fun onError(utteranceId: String) {
                        Log.d(TAG, "UtteranceProgressListener: onError")
                    }
                })
                speak("Initialization finished.")
            }
        })
    }

    private fun setupTensorFlowLite(context: Context) {
        // Load the labels for the model, but only display those that don't start 
        // with an underscore.
        val actualLabelFilename = LABEL_FILENAME.split("file:///android_asset/").dropLastWhile { it.isEmpty() }.toTypedArray()[1]
        Log.i(TAG, "Reading labels from: $actualLabelFilename")
        val br: BufferedReader?
        try {
            br = BufferedReader(InputStreamReader(context.assets.open(actualLabelFilename)))
            var line: String = ""
            while (br.readLine()?.also { line = it } != null) {
                labels.add(line)
                if (line[0] != '_') {
                    displayedLabels.add(line.substring(0, 1).toUpperCase() + line.substring(1))
                }
            }
            br.close()
        } catch (e: IOException) {
            throw RuntimeException("Problem reading label file!", e)
        }
        // Set up an object to smooth recognition results to increase accuracy.
        recognizeCommands = RecognizeCommands(
                labels,
                AVERAGE_WINDOW_DURATION_MS,
                DETECTION_THRESHOLD,
                SUPPRESSION_MS,
                MINIMUM_COUNT,
                MINIMUM_TIME_BETWEEN_SAMPLES_MS)
        val actualModelFilename = MODEL_FILENAME.split("file:///android_asset/").dropLastWhile { it.isEmpty() }.toTypedArray()[1]
        try {
            tfLite = Interpreter(loadModelFile(context.assets, actualModelFilename))
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
        tfLite?.resizeInput(0, intArrayOf(RECORDING_LENGTH, 1))
        tfLite?.resizeInput(1, intArrayOf(1))
    }

    @Throws(IOException::class)
    private fun loadModelFile(assets: AssetManager, modelFilename: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(modelFilename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun setVoiceCommandMode(mode: Boolean) {
        voiceCommandMode = mode
        toggleVoiceCommandMode(mode)
    }

    fun setGameSummaryMode(mode: Boolean) {
        gameSummaryMode = mode
    }

    fun setListener(l: Listener) {
        listener = l
    }

    fun hostScored() {
        game.hostScores()
        listener?.onGameUpdated(game)
        if (gameSummaryMode) {
            announceGameStatus()
        }
    }

    private fun announceGameStatus() {
        var message = game.toString()
        message += if (game.isGameOver()) {
            "Game is over."
        } else {
            if (game.whoShouldServeNext() === PlayerRole.HOST) "Host serves." else "Guest serves."
        }
        speak(message)
    }

    private fun speak(words: String) {
        val utteranceId = UUID.randomUUID().toString()
        textToSpeech?.speak(words, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun guestScored() {
        game.guestScores()
        listener?.onGameUpdated(game)
        if (gameSummaryMode) {
            announceGameStatus()
        }
    }

    fun cancelLastPoint() {
        game.cancelLastPoint()
        listener?.onGameUpdated(game)
        if (gameSummaryMode) {
            speak("Revert last point.")
            announceGameStatus()
        }
    }

    fun restartGame() {
        game.reset()
        listener?.onGameUpdated(game)
        if (gameSummaryMode) {
            speak("Game restarted.")
        }
    }

    private fun toggleVoiceCommandMode(toggle: Boolean) {
        if (!voiceCommandMode) {
            Log.e(TAG, " toggleVoiceCommandMode: voiceCommandMode is disabled so no need to toggle")
            return
        }

        if (toggle) {
            startRecording()
            startRecognition()

        } else {
            stopRecognition()
            stopRecording()
        }

        if (voiceCommandMode && toggle) {
            listener?.onVoiceCommandModeStatus(Listener.VoiceCommandModeStatus.ENABLED)

        } else if (voiceCommandMode && !toggle) {
            listener?.onVoiceCommandModeStatus(Listener.VoiceCommandModeStatus.PAUSED)

        } else if (!voiceCommandMode && !toggle) {
            listener?.onVoiceCommandModeStatus(Listener.VoiceCommandModeStatus.DISABLED)
        }
    }

    @Synchronized
    fun startRecording() {
        Log.v(TAG, "startRecording")
        if (recordingThread != null) {
            return
        }
        shouldContinue = true
        recordingThread = Thread(Runnable { record() })
        recordingThread?.start()
    }

    private fun record() {
        Log.v(TAG, "record 1")
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        // Estimate the buffer size we'll need for this device.
        var bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2
        }
        val audioBuffer = ShortArray(bufferSize / 2)
        val record = AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Audio Record can't initialize!")
            return
        }
        record.startRecording()
        Log.v(TAG, "record 2")
        // Loop, gathering audio data and copying it to a round-robin buffer.
        while (shouldContinue) {
            val numberRead = record.read(audioBuffer, 0, audioBuffer.size)
            val maxLength: Int = recordingBuffer.size
            val newRecordingOffset: Int = recordingOffset + numberRead
            val secondCopyLength = max(0, newRecordingOffset - maxLength)
            val firstCopyLength = numberRead - secondCopyLength
            // We store off all the data for the recognition thread to access. The ML
            // thread will copy out of this buffer into its own, while holding the
            // lock, so this should be thread safe.
            recordingBufferLock.lock()
            try {
                System.arraycopy(audioBuffer, 0, recordingBuffer, recordingOffset, firstCopyLength)
                System.arraycopy(audioBuffer, firstCopyLength, recordingBuffer, 0, secondCopyLength)
                recordingOffset = newRecordingOffset % maxLength
            } finally {
                recordingBufferLock.unlock()
            }
        }
        record.stop()
        record.release()
    }

    @Synchronized
    fun startRecognition() {
        Log.v(TAG, "startRecognition")
        if (recognitionThread != null) {
            return
        }
        shouldContinueRecognition = true
        recognitionThread = Thread(Runnable { recognize() })
        recognitionThread?.start()
    }

    private fun recognize() {
        Log.v(TAG, "recognize")
        val inputBuffer = ShortArray(RECORDING_LENGTH)
        val floatInputBuffer = Array(RECORDING_LENGTH) { FloatArray(1) }
        val outputScores = Array(1) { FloatArray(labels.size) }
        val sampleRateList = intArrayOf(SAMPLE_RATE)
        // Loop, grabbing recorded data and running the recognition model on it.
        while (shouldContinueRecognition) {
            // The recording thread places data in this round-robin buffer, so lock to
            // make sure there's no writing happening and then copy it to our own
            // local version.
            recordingBufferLock.lock()
            try {
                val maxLength: Int = recordingBuffer.size
                val firstCopyLength: Int = maxLength - recordingOffset
                val secondCopyLength: Int = recordingOffset
                System.arraycopy(recordingBuffer, recordingOffset, inputBuffer, 0, firstCopyLength)
                System.arraycopy(recordingBuffer, 0, inputBuffer, firstCopyLength, secondCopyLength)
            } finally {
                recordingBufferLock.unlock()
            }
            // We need to feed in float values between -1.0f and 1.0f, so divide the
            // signed 16-bit inputs.
            for (i in 0 until RECORDING_LENGTH) {
                floatInputBuffer[i][0] = inputBuffer[i] / 32767.0f
            }
            val inputArray = arrayOf(floatInputBuffer, sampleRateList)
            val outputMap: MutableMap<Int, Any> = HashMap()
            outputMap[0] = outputScores
            // Run the model.
            tfLite?.runForMultipleInputsOutputs(inputArray, outputMap)
            // Use the smoother to figure out if we've had a real recognition event.
            val currentTime = System.currentTimeMillis()
            val result: RecognitionResult = recognizeCommands.processLatestResults(outputScores[0], currentTime)

            // If we do have a new command, highlight the right list entry.
            if (!result.foundCommand.startsWith("_") && result.isNewCommand) {
                var labelIndex = -1
                for (i in labels.indices) {
                    if (labels[i] == result.foundCommand) {
                        labelIndex = i
                    }
                }
                Log.v(TAG, "recognize - new command index=" + (labelIndex - 2))
                when (labelIndex - 2) {
                    0 -> {
                        Log.d(TAG, "recognize - Yes")
                        if (!isTooSoonForAnotherScore()) {
                            hostScored()
                            lastScoreTimestamp = System.currentTimeMillis()
                        } else {
                            Log.d(TAG, " recognize- Too soon for another score. Ignoring.")
                        }
                    }
                    1 -> Log.d(TAG, "recognize - No")
                    2 -> Log.d(TAG, "recognize - Up")
                    3 -> Log.d(TAG, "recognize - Down")
                    4 -> Log.d(TAG, "recognize - Left")
                    5 -> Log.d(TAG, "recognize - Right")
                    6 -> Log.d(TAG, "recognize - On")
                    7 -> Log.d(TAG, "recognize - Off")
                    8 -> {
                        Log.d(TAG, "recognize - Stop")
                        cancelLastPoint()
                    }
                    9 -> {
                        Log.d(TAG, "recognize - Go")
                        if (!isTooSoonForAnotherScore()) {
                            guestScored()
                            lastScoreTimestamp = System.currentTimeMillis()
                        } else {
                            Log.d(TAG, " recognize- Too soon for another score. Ignoring.")
                        }
                    }
                }
            }

            try { // We don't need to run too frequently, so snooze for a bit.
                Thread.sleep(MINIMUM_TIME_BETWEEN_SAMPLES_MS)
            } catch (e: InterruptedException) { // Ignore
            }
        }
        Log.v(TAG, "End recognition")
    }

    private fun isTooSoonForAnotherScore(): Boolean {
        val now = System.currentTimeMillis()
        val duration = now - lastScoreTimestamp
        Log.d(TAG, " isTooSoonForAnotherScore - duration = $duration")
        return duration < 6000
    }

    @Synchronized
    fun stopRecording() {
        if (recordingThread == null) {
            return
        }
        shouldContinue = false
        recordingThread = null
    }

    @Synchronized
    fun stopRecognition() {
        if (recognitionThread == null) {
            return
        }
        shouldContinueRecognition = false
        recognitionThread = null
    }

    interface Listener {
        fun onGameUpdated(game: Game)
        fun onVoiceCommandModeStatus(status: VoiceCommandModeStatus)
        enum class VoiceCommandModeStatus {
            ENABLED, DISABLED, PAUSED
        }
    }
}