package com.sieong.pingpong;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.jetbrains.annotations.NotNull;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public class MainActivity extends AppCompatActivity implements Referee.Listener {


    private static final int REQUEST_RECORD_AUDIO = 13;
    private static final String TAG = MainActivity.class.getSimpleName();

    private TextView hostScore;
    private TextView guestScore;
    private TextView whoShouldServing;
    private Button scoreHostButton;
    private Button scoreGuestButton;
    private Button restartButton;
    private TextView recognizerStatus;

    private Referee referee;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        setupReferee();
    }

    private void initViews() {
        hostScore = findViewById(R.id.host_score);
        guestScore = findViewById(R.id.guest_score);
        scoreHostButton = findViewById(R.id.score_host);
        scoreGuestButton = findViewById(R.id.score_guest);
        whoShouldServing = findViewById(R.id.who_should_serve);
        restartButton = findViewById(R.id.restart);
        recognizerStatus = findViewById(R.id.recognizer_status);

        scoreHostButton.setOnClickListener(v -> referee.hostScored());
        scoreGuestButton.setOnClickListener(v -> referee.guestScored());
        restartButton.setOnClickListener(v -> referee.restartGame());
    }

    private void setupReferee() {
        referee = new Referee(this);
        referee.setListener(this);
        referee.setGameSummaryMode(true);
        turnOnRefereeVoiceCommand();
    }

    private void turnOnRefereeVoiceCommand() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int permissionState = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO);
            if (permissionState == PERMISSION_GRANTED) {
                referee.setVoiceCommandMode(true);
            } else {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            }
        } else {
            referee.setVoiceCommandMode(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        referee.setVoiceCommandMode(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        referee.setVoiceCommandMode(false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NotNull String[] permissions, @NotNull int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult");
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.length > 0 && grantResults[0] == PERMISSION_GRANTED) {
            Log.d(TAG, "onRequestPermissionsResult- allowed");
            referee.setVoiceCommandMode(true);
        }
    }

    @Override
    public void onGameUpdated(@NotNull Game game) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                refreshUI(game);
            }
        });
    }

    private void refreshUI(Game game) {
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

    @Override
    public void onVoiceCommandModeStatus(@NotNull VoiceCommandModeStatus status) {
        switch (status){
            case ENABLED:
                recognizerStatus.setText("Recognizer working...");
                break;
            case DISABLED:
                recognizerStatus.setText("Recognizer disabled.");
                break;
            case PAUSED:
                recognizerStatus.setText("Recognizer paused.");
                break;
        }
    }
}
