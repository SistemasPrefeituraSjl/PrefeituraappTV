package com.prefeitura.tv;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String SESSION_URL =
            "https://sistemasprefeiturasjl.github.io/TV/session.json";

    private View loginView;
    private View playerView;
    private EditText codeInput;
    private TextView messageText;
    private TextView loadingText;
    private PlayerView exoPlayerView;
    private ExoPlayer player;

    private String serverUrl = "";
    private String lastStreamUrl = "";
    private String enteredCode = "";
    private boolean frozen = false;
    private boolean triedSoftware = false;
    private int reconnectAttempts = 0;
    private boolean isReconnecting = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Runnable pollRunnable;
    private Runnable reconnectRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        loginView = findViewById(R.id.loginView);
        playerView = findViewById(R.id.playerView);
        codeInput = findViewById(R.id.codeInput);
        messageText = findViewById(R.id.messageText);
        loadingText = findViewById(R.id.loadingText);
        exoPlayerView = findViewById(R.id.exoPlayerView);

        findViewById(R.id.connectBtn).setOnClickListener(v -> connect());

        codeInput.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO) {
                connect();
                return true;
            }
            return false;
        });
    }

    private void connect() {
        String code = codeInput.getText().toString().replaceAll("[^0-9]", "");
        if (code.length() != 6) {
            showMessage("Digite os 6 numeros do codigo.", true);
            return;
        }
        enteredCode = code;
        showMessage("Conectando...", false);

        executor.execute(() -> {
            try {
                String json = httpGet(SESSION_URL + "?t=" + System.currentTimeMillis());
                JSONObject session = new JSONObject(json);

                if (!session.optBoolean("active", false)
                        || !enteredCode.equals(session.optString("code"))) {
                    runOnUiThread(() -> showMessage(
                            "Codigo invalido ou transmissao nao iniciada.", true));
                    return;
                }

                serverUrl = session.getString("url");
                String streamPath = session.optString("stream_path", "/hls/stream.m3u8");
                String streamUrl = serverUrl + streamPath;

                runOnUiThread(() -> startPlayer(streamUrl));
            } catch (Exception e) {
                runOnUiThread(() -> showMessage(
                        "Erro ao conectar: " + e.getMessage(), true));
            }
        });
    }

    private void showMessage(String text, boolean isError) {
        messageText.setText(text);
        messageText.setTextColor(isError ? 0xFFE57373 : 0xFF777777);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void startPlayer(String streamUrl) {
        lastStreamUrl = streamUrl;
        isReconnecting = false;
        loginView.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        loadingText.setVisibility(View.VISIBLE);
        loadingText.setText("Carregando...");

        releasePlayer();

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2000, 6000, 1000, 1500)
                .build();

        ExoPlayer.Builder builder = new ExoPlayer.Builder(this)
                .setLoadControl(loadControl);

        if (triedSoftware) {
            DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
            builder.setRenderersFactory(renderersFactory);
        }

        player = builder.build();

        exoPlayerView.setPlayer(player);
        exoPlayerView.setUseController(false);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    loadingText.setVisibility(View.GONE);
                    reconnectAttempts = 0;
                    triedSoftware = false;
                } else if (state == Player.STATE_BUFFERING) {
                    // Only show loading if already reconnecting (expected) — silent otherwise
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (!triedSoftware) {
                    triedSoftware = true;
                    releasePlayer();
                    startPlayer(lastStreamUrl);
                    return;
                }
                scheduleReconnect();
            }
        });

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(Uri.parse(streamUrl))
                .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(2000)
                                .setMaxOffsetMs(5000)
                                .setMinPlaybackSpeed(0.97f)
                                .setMaxPlaybackSpeed(1.03f)
                                .build()
                )
                .build();

        HlsMediaSource hlsSource = new HlsMediaSource.Factory(
                new DefaultHttpDataSource.Factory()
                        .setConnectTimeoutMs(15000)
                        .setReadTimeoutMs(15000)
                        .setAllowCrossProtocolRedirects(true)
        ).createMediaSource(mediaItem);

        player.setMediaSource(hlsSource);
        player.setPlayWhenReady(true);
        player.prepare();

        startPolling();
    }

    private void scheduleReconnect() {
        isReconnecting = true;
        releasePlayer();
        stopPolling();

        long delay = Math.min((long)(2000 * Math.pow(1.5, reconnectAttempts)), 10000);
        reconnectAttempts++;

        // Only show "Reconectando" after a few failures so minor hiccups are silent
        if (reconnectAttempts >= 3) {
            loadingText.setVisibility(View.VISIBLE);
            loadingText.setText("Reconectando...");
        }

        if (reconnectRunnable != null) handler.removeCallbacks(reconnectRunnable);
        reconnectRunnable = () -> {
            if (!isReconnecting) return;
            executor.execute(() -> {
                try {
                    // Re-fetch session to get current URL in case tunnel restarted
                    String json = httpGet(SESSION_URL + "?t=" + System.currentTimeMillis());
                    JSONObject session = new JSONObject(json);

                    if (!session.optBoolean("active", false)) {
                        runOnUiThread(() -> {
                            loadingText.setVisibility(View.VISIBLE);
                            loadingText.setText("Aguardando transmissao...");
                            scheduleReconnect();
                        });
                        return;
                    }

                    if (!enteredCode.isEmpty() && !enteredCode.equals(session.optString("code"))) {
                        runOnUiThread(MainActivity.this::goBack);
                        return;
                    }

                    serverUrl = session.getString("url");
                    String streamPath = session.optString("stream_path", "/hls/stream.m3u8");
                    String newStreamUrl = serverUrl + streamPath;

                    runOnUiThread(() -> startPlayer(newStreamUrl));
                } catch (Exception e) {
                    runOnUiThread(() -> scheduleReconnect());
                }
            });
        };
        handler.postDelayed(reconnectRunnable, delay);
    }

    private void startPolling() {
        stopPolling();
        int[] networkFails = {0};
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (player == null) return;
                executor.execute(() -> {
                    try {
                        String json = httpGet(
                                serverUrl + "/api/session?t=" + System.currentTimeMillis());
                        JSONObject s = new JSONObject(json);
                        boolean isFrozen = s.optBoolean("frozen", false);
                        networkFails[0] = 0;
                        runOnUiThread(() -> {
                            if (player == null) return;
                            if (isFrozen && !frozen) {
                                player.setPlayWhenReady(false);
                                frozen = true;
                            } else if (!isFrozen && frozen) {
                                player.setPlayWhenReady(true);
                                frozen = false;
                            }
                        });
                    } catch (Exception ignored) {
                        networkFails[0]++;
                    }
                });
                handler.postDelayed(this, 1500);
            }
        };
        handler.postDelayed(pollRunnable, 1500);
    }

    private void stopPolling() {
        if (pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
        frozen = false;
    }

    private void releasePlayer() {
        stopPolling();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void goBack() {
        isReconnecting = false;
        reconnectAttempts = 0;
        triedSoftware = false;
        if (reconnectRunnable != null) {
            handler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        releasePlayer();
        playerView.setVisibility(View.GONE);
        loadingText.setVisibility(View.GONE);
        loginView.setVisibility(View.VISIBLE);
        codeInput.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK
                && playerView.getVisibility() == View.VISIBLE) {
            goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
