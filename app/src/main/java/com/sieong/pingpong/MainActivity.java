package com.sieong.pingpong;

import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private PingPongGame game;
    TextView hostScore;
    TextView guestScore;
    Button scoreHostButton;
    Button scoreGuestButton;
    TextView whoShouldServing;
    Button restartButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        restartGame();
        toastNetworkResult();
    }

    private void toastNetworkResult() {
        AsyncTask task = new AsyncTask() {
            @Override
            protected Object doInBackground(Object[] objects) {
                OkHttpClient client = new OkHttpClient();
                Request.Builder builder = new Request.Builder();
                builder.url("https://reqres.in/api/users/2");
                Request request = builder.build();
                try {
                    Response response = client.newCall(request).execute();
                    Log.d("Myles", response.body().string());
                }catch (IOException e){
                    e.printStackTrace();
                }
                return null;
            }
        };
        task.execute();
    }

    private void initViews() {
        hostScore = findViewById(R.id.hostScore);
        guestScore = findViewById(R.id.guestScore);
        scoreHostButton = findViewById(R.id.scoreHost);
        scoreGuestButton = findViewById(R.id.scoreGuest);
        whoShouldServing = findViewById(R.id.whoShouldServe);
        restartButton = findViewById(R.id.restart);

        scoreHostButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                game.hostScores();
                updateUI();
            }
        });

        scoreGuestButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                game.guestScores();
                updateUI();
            }
        });

        restartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartGame();
                updateUI();
            }
        });
    }

    private void updateUI() {
        hostScore.setText(String.valueOf(game.getScoreHost()));
        guestScore.setText(String.valueOf(game.getScoreGuest()));
        if (game.shouldHostService()) {
            whoShouldServing.setText("Host should serve");
        } else {
            whoShouldServing.setText("Guest should serve");
        }
    }

    private void restartGame() {
        game = new PingPongGame();
    }


    private class PingPongGame {
        int scoreHost;
        int scoreGuest;

        PingPongGame() {
            scoreHost = 0;
            scoreGuest = 0;
        }

        public int getScoreHost() {
            return scoreHost;
        }

        public int getScoreGuest() {
            return scoreGuest;
        }

        /* A is assumed to serve at the begining*/
        public boolean shouldHostService() {
            return isEvenNumber((scoreHost + scoreGuest) / 2);
        }

        private boolean isEvenNumber(int number) {
            return (number % 2) == 0;
        }

        public void hostScores() {
            scoreHost++;
        }

        public void guestScores() {
            scoreGuest++;
        }
    }
}
