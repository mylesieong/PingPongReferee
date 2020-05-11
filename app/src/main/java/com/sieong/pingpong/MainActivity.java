package com.sieong.pingpong;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    TextView hostScore;
    TextView guestScore;
    Button scoreHostButton;
    Button scoreGuestButton;
    TextView whoShouldServing;
    Button restartButton;
    private Game game;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        resetGame();
    }

    private void initViews() {
        hostScore = findViewById(R.id.hostScore);
        guestScore = findViewById(R.id.guestScore);
        scoreHostButton = findViewById(R.id.scoreHost);
        scoreGuestButton = findViewById(R.id.scoreGuest);
        whoShouldServing = findViewById(R.id.whoShouldServe);
        restartButton = findViewById(R.id.restart);

        scoreHostButton.setOnClickListener(v -> handleHostScores());
        scoreGuestButton.setOnClickListener(v -> handleGuestScores());
        restartButton.setOnClickListener(v -> handleRestart());
    }

    private void handleRestart() {
        resetGame();
        refreshUI();
    }

    private void handleGuestScores() {
        game.guestScores();
        refreshUI();
    }

    private void handleHostScores() {
        game.hostScores();
        refreshUI();
    }

    private void refreshUI() {
        hostScore.setText(String.valueOf(game.getScoreHost()));
        guestScore.setText(String.valueOf(game.getScoreGuest()));
        if (game.shouldHostService()) {
            whoShouldServing.setText("Host should serve");
        } else {
            whoShouldServing.setText("Guest should serve");
        }
    }

    private void resetGame() {
        game = new Game();
    }
}
