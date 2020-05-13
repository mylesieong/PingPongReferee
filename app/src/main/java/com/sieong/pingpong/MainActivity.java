package com.sieong.pingpong;

import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Locale.UK;

public class MainActivity extends AppCompatActivity {

    private static final int SAMPLE_RATE = 16000;
    private static final int SAMPLE_DURATION_MS = 1000;
    private static final int RECORDING_LENGTH = (int) (SAMPLE_RATE * SAMPLE_DURATION_MS / 1000);
    private static final long AVERAGE_WINDOW_DURATION_MS = 1000;
    private static final float DETECTION_THRESHOLD = 0.50f;
    private static final int SUPPRESSION_MS = 1500;
    private static final int MINIMUM_COUNT = 3;
    private static final long MINIMUM_TIME_BETWEEN_SAMPLES_MS = 30;
    private static final String LABEL_FILENAME = "file:///android_asset/conv_actions_labels.txt";
    private static final String MODEL_FILENAME = "file:///android_asset/conv_actions_frozen.tflite";

    private static final int REQUEST_RECORD_AUDIO = 13;
    private static final String TAG = MainActivity.class.getSimpleName();
    private final ReentrantLock recordingBufferLock = new ReentrantLock();

    private short[] recordingBuffer = new short[RECORDING_LENGTH];
    private int recordingOffset = 0;
    private boolean shouldContinue = true;
    private boolean shouldContinueRecognition = true;
    private Game game;
    private Thread recordingThread;
    private Thread recognitionThread;
    private List<String> labels = new ArrayList<>();
    private List<String> displayedLabels = new ArrayList<>();
    private RecognizeCommands recognizeCommands = null;
    private Interpreter tfLite;
    private TextToSpeech textToSpeech;
    private TextView hostScore;
    private TextView guestScore;
    private TextView whoShouldServing;
    private Button scoreHostButton;
    private Button scoreGuestButton;
    private Button restartButton;
    private TextView recognizerStatus;

    private static MappedByteBuffer loadModelFile(AssetManager assets, String modelFilename) throws IOException {
        AssetFileDescriptor fileDescriptor = assets.openFd(modelFilename);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        resetGame();
        setupTensorFlowLite();
        requestMicrophonePermission();
        initTextToSpeech();
    }

    private void initViews() {
        hostScore = findViewById(R.id.host_score);
        guestScore = findViewById(R.id.guest_score);
        scoreHostButton = findViewById(R.id.score_host);
        scoreGuestButton = findViewById(R.id.score_guest);
        whoShouldServing = findViewById(R.id.who_should_serve);
        restartButton = findViewById(R.id.restart);
        recognizerStatus = findViewById(R.id.recognizer_status);

        scoreHostButton.setOnClickListener(v -> handleHostScores());
        scoreGuestButton.setOnClickListener(v -> handleGuestScores());
        restartButton.setOnClickListener(v -> handleRestart());
    }

    private void handleRestart() {
        resetGame();
        refreshUI();
        speak("Restarting");
    }

    private void handleGuestScores() {
        if (game.isGameOver()) {
            return;
        }

        game.guestScores();
        announceGameStatus(Game.PlayerRole.GUEST);
        refreshUI();
    }

    private void announceGameStatus(Game.PlayerRole scoredPlayer) {
        String message = scoredPlayer == Game.PlayerRole.GUEST ? "Guest scores." : "Host scores.";
        message += game.toString();
        if (game.isGameOver()) {
            message += "Game is over.";
        } else {
            message += game.whoShouldServeNext() == Game.PlayerRole.HOST ? "Host serves." : "Guest serves.";
        }
        speak(message);
    }

    private void handleHostScores() {
        if (game.isGameOver()) {
            return;
        }

        game.hostScores();
        announceGameStatus(Game.PlayerRole.HOST);
        refreshUI();
    }

    private void refreshUI() {
        hostScore.setText(String.valueOf(game.getScoreHost()));
        guestScore.setText(String.valueOf(game.getScoreGuest()));

        if (game.isGameOver()) {
            whoShouldServing.setText("Game over");
            scoreGuestButton.setEnabled(false);
            scoreHostButton.setEnabled(false);

        } else {
            whoShouldServing.setText(game.whoShouldServeNext() == Game.PlayerRole.HOST ? "Host should serve" : "Guest should serve");
            scoreGuestButton.setEnabled(true);
            scoreHostButton.setEnabled(true);
        }
    }

    private void resetGame() {
        game = new Game();
    }

    private void setupTensorFlowLite() {
        // Load the labels for the model, but only display those that don't start
        // with an underscore.
        String actualLabelFilename = LABEL_FILENAME.split("file:///android_asset/", -1)[1];
        Log.i(TAG, "Reading labels from: " + actualLabelFilename);
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(getAssets().open(actualLabelFilename)));
            String line;
            while ((line = br.readLine()) != null) {
                labels.add(line);
                if (line.charAt(0) != '_') {
                    displayedLabels.add(line.substring(0, 1).toUpperCase() + line.substring(1));
                }
            }
            br.close();
        } catch (IOException e) {
            throw new RuntimeException("Problem reading label file!", e);
        }

        // Set up an object to smooth recognition results to increase accuracy.
        recognizeCommands =
                new RecognizeCommands(
                        labels,
                        AVERAGE_WINDOW_DURATION_MS,
                        DETECTION_THRESHOLD,
                        SUPPRESSION_MS,
                        MINIMUM_COUNT,
                        MINIMUM_TIME_BETWEEN_SAMPLES_MS);

        String actualModelFilename = MODEL_FILENAME.split("file:///android_asset/", -1)[1];
        try {
            tfLite = new Interpreter(loadModelFile(getAssets(), actualModelFilename));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        tfLite.resizeInput(0, new int[]{RECORDING_LENGTH, 1});
        tfLite.resizeInput(1, new int[]{1});
    }

    private void requestMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(getApplicationContext(), status -> {
            Log.d(TAG, "onInit: status=" + status);

            if (status != TextToSpeech.ERROR) {
                textToSpeech.setLanguage(UK);
                textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        Log.d(TAG, "UtteranceProgressListener: onStart");
                        recognizerStops();
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        Log.d(TAG, "UtteranceProgressListener: onDone");
                        recognizerStarts();
                    }

                    @Override
                    public void onError(String utteranceId) {
                        Log.d(TAG, "UtteranceProgressListener: onError");
                    }
                });
                speak("Initialization finished.");
            }
        });
    }

    private void speak(String words) {
        String utteranceId = UUID.randomUUID().toString();
        textToSpeech.speak(words, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        recognizerStarts();
    }

    private void recognizerStarts() {
        startRecording();
        startRecognition();
        recognizerStatus.setText("Recognizer is working...");
    }

    public synchronized void startRecording() {
        Log.v(TAG, "startRecording");

        if (recordingThread != null) {
            return;
        }
        shouldContinue = true;
        recordingThread = new Thread(() -> record());
        recordingThread.start();
    }

    private void record() {
        Log.v(TAG, "record 1");
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        // Estimate the buffer size we'll need for this device.
        int bufferSize =
                AudioRecord.getMinBufferSize(
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2;
        }
        short[] audioBuffer = new short[bufferSize / 2];

        AudioRecord record =
                new AudioRecord(
                        MediaRecorder.AudioSource.DEFAULT,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Audio Record can't initialize!");
            return;
        }

        record.startRecording();

        Log.v(TAG, "record 2");

        // Loop, gathering audio data and copying it to a round-robin buffer.
        while (shouldContinue) {
            int numberRead = record.read(audioBuffer, 0, audioBuffer.length);
            int maxLength = recordingBuffer.length;
            int newRecordingOffset = recordingOffset + numberRead;
            int secondCopyLength = Math.max(0, newRecordingOffset - maxLength);
            int firstCopyLength = numberRead - secondCopyLength;
            // We store off all the data for the recognition thread to access. The ML
            // thread will copy out of this buffer into its own, while holding the
            // lock, so this should be thread safe.
            recordingBufferLock.lock();
            try {
                System.arraycopy(audioBuffer, 0, recordingBuffer, recordingOffset, firstCopyLength);
                System.arraycopy(audioBuffer, firstCopyLength, recordingBuffer, 0, secondCopyLength);
                recordingOffset = newRecordingOffset % maxLength;
            } finally {
                recordingBufferLock.unlock();
            }
        }

        record.stop();
        record.release();
    }

    public synchronized void startRecognition() {
        Log.v(TAG, "startRecognition");

        if (recognitionThread != null) {
            return;
        }
        shouldContinueRecognition = true;
        recognitionThread = new Thread(() -> recognize());
        recognitionThread.start();
    }

    private void recognize() {

        Log.v(TAG, "recognize");

        short[] inputBuffer = new short[RECORDING_LENGTH];
        float[][] floatInputBuffer = new float[RECORDING_LENGTH][1];
        float[][] outputScores = new float[1][labels.size()];
        int[] sampleRateList = new int[]{SAMPLE_RATE};

        // Loop, grabbing recorded data and running the recognition model on it.
        while (shouldContinueRecognition) {
            long startTime = new Date().getTime();
            // The recording thread places data in this round-robin buffer, so lock to
            // make sure there's no writing happening and then copy it to our own
            // local version.
            recordingBufferLock.lock();
            try {
                int maxLength = recordingBuffer.length;
                int firstCopyLength = maxLength - recordingOffset;
                int secondCopyLength = recordingOffset;
                System.arraycopy(recordingBuffer, recordingOffset, inputBuffer, 0, firstCopyLength);
                System.arraycopy(recordingBuffer, 0, inputBuffer, firstCopyLength, secondCopyLength);
            } finally {
                recordingBufferLock.unlock();
            }

            // We need to feed in float values between -1.0f and 1.0f, so divide the
            // signed 16-bit inputs.
            for (int i = 0; i < RECORDING_LENGTH; ++i) {
                floatInputBuffer[i][0] = inputBuffer[i] / 32767.0f;
            }

            Object[] inputArray = {floatInputBuffer, sampleRateList};
            Map<Integer, Object> outputMap = new HashMap<>();
            outputMap.put(0, outputScores);

            // Run the model.
            tfLite.runForMultipleInputsOutputs(inputArray, outputMap);

            // Use the smoother to figure out if we've had a real recognition event.
            long currentTime = System.currentTimeMillis();
            final RecognizeCommands.RecognitionResult result =
                    recognizeCommands.processLatestResults(outputScores[0], currentTime);
            runOnUiThread(
                    () -> {

                        // If we do have a new command, highlight the right list entry.
                        if (!result.foundCommand.startsWith("_") && result.isNewCommand) {
                            int labelIndex = -1;
                            for (int i = 0; i < labels.size(); ++i) {
                                if (labels.get(i).equals(result.foundCommand)) {
                                    labelIndex = i;
                                }
                            }

                            Log.v(TAG, "recognize - new command index=" + (labelIndex - 2));
                            switch (labelIndex - 2) {
                                case 0:
                                    Toast.makeText(MainActivity.this, "Yes", Toast.LENGTH_SHORT).show();
                                    handleHostScores();
                                    break;
                                case 1:
                                    Toast.makeText(MainActivity.this, "No", Toast.LENGTH_SHORT).show();
                                    handleGuestScores();
                                    break;
                                case 2:
                                    Toast.makeText(MainActivity.this, "Up", Toast.LENGTH_SHORT).show();
                                    break;
                                case 3:
                                    Toast.makeText(MainActivity.this, "Down", Toast.LENGTH_SHORT).show();
                                    handleCancelLastPoint();
                                    break;
                                case 4:
                                    Toast.makeText(MainActivity.this, "Left", Toast.LENGTH_SHORT).show();
                                    break;
                                case 5:
                                    Toast.makeText(MainActivity.this, "Right", Toast.LENGTH_SHORT).show();
                                    break;
                                case 6:
                                    Toast.makeText(MainActivity.this, "On", Toast.LENGTH_SHORT).show();
                                    break;
                                case 7:
                                    Toast.makeText(MainActivity.this, "Off", Toast.LENGTH_SHORT).show();
                                    break;
                                case 8:
                                    Toast.makeText(MainActivity.this, "Stop", Toast.LENGTH_SHORT).show();
                                    break;
                                case 9:
                                    Toast.makeText(MainActivity.this, "Go", Toast.LENGTH_SHORT).show();
                                    break;
                            }
                        }
                    });
            try {
                // We don't need to run too frequently, so snooze for a bit.
                Thread.sleep(MINIMUM_TIME_BETWEEN_SAMPLES_MS);
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        Log.v(TAG, "End recognition");
    }

    private void handleCancelLastPoint() {
        game.cancelLastPoint();
        speak("Cancel last point");
        speak(game.toString());
        speak(game.whoShouldServeNext() == Game.PlayerRole.HOST ? "Host serves" : "Guest serves");
        refreshUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        recognizerStops();
    }

    private void recognizerStops() {
        stopRecognition();
        stopRecording();
        recognizerStatus.setText("Recognizer stopped.");
    }

    public synchronized void stopRecording() {
        if (recordingThread == null) {
            return;
        }
        shouldContinue = false;
        recordingThread = null;
    }

    public synchronized void stopRecognition() {
        if (recognitionThread == null) {
            return;
        }
        shouldContinueRecognition = false;
        recognitionThread = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult");
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "onRequestPermissionsResult- allowed");
            recognizerStarts();
        }
    }
}
