package com.example.onlinemeeting.controller;

import com.example.onlinemeeting.client.AudioClient;
import com.example.onlinemeeting.client.VideoClient;
import com.example.onlinemeeting.client.VideoCredentials;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import com.example.onlinemeeting.client.AudioCredentials;
import com.example.onlinemeeting.client.TCPClient;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.shape.Rectangle;
import javafx.geometry.Pos;
import java.util.Locale;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.application.Platform;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.util.Duration;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

public class MeetingController {

    @FXML
    private StackPane meetingRoot;

    @FXML
    private Label toastLabel;

    // Accessed only on the JavaFX application thread.
    private final Deque<String> toastQueue = new ArrayDeque<>();
    private SequentialTransition toastAnimation;
    private boolean meetingActive;
    private boolean leaving;
    @FXML private Button cameraButton;
    @FXML private Label videoStatusLabel;
    @FXML private Label connectionLabel;
    @FXML private Label participantCountLabel;
    @FXML private VBox participantList;
    @FXML private ScrollPane participantsScroll;
    @FXML private ScrollPane chatScroll;
    private long videoGeneration;
    private VideoClient videoClient;
    private CompletableFuture<VideoCredentials> videoRequest;
    private boolean videoUiPending;
    private final Map<String, BufferedImage> pendingVideoFrames = new HashMap<>();
    private final Set<String> enabledCameras = new HashSet<>();
    // The following maps are accessed only on JavaFX.
    private final Map<String, Image> participantImages = new HashMap<>();
    private final Map<String, ImageView> participantViews = new HashMap<>();
    private final Map<String, Label> cameraPlaceholders = new HashMap<>();
    private static final Executor AUDIO_TASKS =
            task -> Thread.ofVirtual().name("meeting-audio-lifecycle").start(task);
    private final Object audioLock = new Object();
    private long meetingGeneration;
    private boolean audioLifecycleActive;
    // Credentials belong to the controller until transferred to AudioClient.
    private AudioClient audioClient;
    private boolean audioReady;
    private CompletableFuture<AudioCredentials> audioRequest;
    private Thread credentialExpiry;
    private TCPClient.ConnectionSubscription connectionSubscription;
    private Scene observedScene;
    private Window observedWindow;
    private final EventHandler<WindowEvent> hiddenHandler = event -> dispose();
    private final ChangeListener<Window> windowListener =
            (property, oldWindow, newWindow) -> observeWindow(newWindow);

    @FXML
    private void initialize() {
        resetMicrophoneControls();
        cameraButton.setDisable(true);
        setMediaButtonStyle(cameraButton, "media-unavailable");
        participantsScroll.viewportBoundsProperty().addListener((property, before, after) ->
                layoutParticipantTiles());
        Tooltip audioTooltip = new Tooltip();
        audioTooltip.textProperty().bind(statusLabel.textProperty());
        statusLabel.setTooltip(audioTooltip);
        Tooltip videoTooltip = new Tooltip();
        videoTooltip.textProperty().bind(videoStatusLabel.textProperty());
        videoStatusLabel.setTooltip(videoTooltip);
        meetingRoot.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.windowProperty().removeListener(windowListener);
            }
            observedScene = newScene;
            if (newScene != null) {
                newScene.windowProperty().addListener(windowListener);
                observeWindow(newScene.getWindow());
            } else {
                observeWindow(null);
                dispose();
            }
        });
    }

    private void observeWindow(Window window) {
        Window previous = observedWindow;
        if (previous != null) {
            previous.removeEventHandler(WindowEvent.WINDOW_HIDDEN, hiddenHandler);
        }
        observedWindow = window;
        if (window != null) {
            window.addEventHandler(WindowEvent.WINDOW_HIDDEN, hiddenHandler);
        } else if (previous != null) {
            dispose();
        }
    }

    /** JavaFX lifecycle hook; call on the JavaFX application thread. */
    public void dispose() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Dispose the meeting on the JavaFX thread");
        }
        invalidateAudioSession();
        meetingActive = false;
        clearToasts();
        resetMicrophoneControls();
        if (observedScene != null) {
            observedScene.windowProperty().removeListener(windowListener);
            observedScene = null;
        }
        if (observedWindow != null) {
            observedWindow.removeEventHandler(WindowEvent.WINDOW_HIDDEN, hiddenHandler);
            observedWindow = null;
        }
    }

    private void showToast(String message) {
        if (!meetingActive) {
            return;
        }
        toastQueue.addLast(message);
        if (toastAnimation == null) {
            showNextToast();
        }
    }

    private void showNextToast() {
        if (toastQueue.isEmpty()) {
            toastLabel.setVisible(false);
            return;
        }
        toastLabel.setText(toastQueue.removeFirst());
        toastLabel.setOpacity(0);
        toastLabel.setVisible(true);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(180), toastLabel);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.millis(2640));
        FadeTransition fadeOut = new FadeTransition(Duration.millis(180), toastLabel);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        toastAnimation = new SequentialTransition(fadeIn, hold, fadeOut);
        toastAnimation.setOnFinished(event -> {
            toastAnimation = null;
            showNextToast();
        });
        toastAnimation.play();
    }

    private void clearToasts() {
        toastQueue.clear();
        if (toastAnimation != null) {
            toastAnimation.stop();
            toastAnimation = null;
        }
        toastLabel.setVisible(false);
        toastLabel.setText("");
    }

    @FXML
    private Label roomIdLabel;

    @FXML
    private Label statusLabel;

    @FXML
    private Button micButton;

    @FXML
    private GridPane participantsGrid;

    @FXML
    private VBox chatBox;

    @FXML
    private TextField messageField;

    private final Set<String> participants =
            new LinkedHashSet<>();

    private TCPClient tcpClient;

    private String username;
    private String roomId;

    public void setData(
            TCPClient tcpClient,
            String username,
            String roomId
    ) {
        if (tcpClient == null || username == null || username.isBlank()
                || roomId == null || !roomId.matches("[0-9A-F]{6}")) {
            throw new IllegalArgumentException("Valid meeting data is required");
        }
        invalidateAudioSession();
        synchronized (audioLock) {
            this.tcpClient = tcpClient;
        }
        participantImages.clear();
        this.username = username;
        this.roomId = roomId;
        meetingActive = true;
        leaving = false;
        showConnectionStatus(true);

        roomIdLabel.setText(
                "Mã phòng · " + roomId
        );

        participants.clear();
        participants.add(username);

        renderParticipants();

        tcpClient.setEventListener(
                this::handleServerEvent
        );

        if (registerConnectionLifecycle()) {
            requestAudioCredentials();
            requestVideoCredentials();
        }
    }

    private boolean registerConnectionLifecycle() {
        try {
            synchronized (audioLock) {
                long generation = meetingGeneration;
                TCPClient client = tcpClient;
                // Holding audioLock closes the race with immediate delivery for
                // a connection that disconnected just before registration.
                connectionSubscription = client.addConnectionLifecycleListener(
                        connectionId -> onTcpDisconnected(client, generation, connectionId));
            }
            return true;
        } catch (IllegalStateException e) {
            invalidateAudioSession();
            resetMicrophoneControls();
            showConnectionStatus(false);
            statusLabel.setText("Mất kết nối tới máy chủ. Phiên âm thanh đã được xóa.");
            return false;
        }
    }

    private void onTcpDisconnected(TCPClient client, long generation, long connectionId) {
        synchronized (audioLock) {
            if (tcpClient != client || connectionSubscription == null
                    || connectionSubscription.getConnectionId() != connectionId
                    || meetingGeneration != generation) {
                return;
            }
        }
        // Wipe immediately on the lifecycle worker, even if JavaFX is busy.
        long disconnectedGeneration = invalidateAudioSession(generation);
        if (disconnectedGeneration < 0) {
            return;
        }
        Platform.runLater(() -> {
            synchronized (audioLock) {
                if (tcpClient != client || meetingGeneration != disconnectedGeneration) {
                    return;
                }
            }
            meetingActive = false;
            leaving = false;
            clearToasts();
            resetMicrophoneControls();
            showConnectionStatus(false);
            statusLabel.setText("Mất kết nối tới máy chủ. Phiên âm thanh đã được xóa.");
        });
    }
    private void handleServerEvent(String message) {
        long generation;
        synchronized (audioLock) {
            generation = meetingGeneration;
        }
        Platform.runLater(() -> {
            synchronized (audioLock) {
                if (generation != meetingGeneration || !meetingActive) {
                    return;
                }
            }

            if (message.startsWith("MEMBERS|")) {

                participants.clear();

                String data =
                        message.substring(
                                "MEMBERS|".length()
                        );

                if (!data.isBlank()) {

                    String[] users =
                            data.split(",");

                    for (String user : users) {
                        participants.add(user);
                    }
                }

                renderParticipants();

            } else if (
                    message.startsWith("USER_JOINED|")
            ) {

                String user =
                        message.substring(
                                "USER_JOINED|".length()
                        );

                boolean added = participants.add(user);

                renderParticipants();
                if (added) {
                    showToast(user + " đã tham gia phòng.");
                }

            } else if (
                    message.startsWith("USER_LEFT|")
            ) {

                String user =
                        message.substring(
                                "USER_LEFT|".length()
                        );

                boolean removed = participants.remove(user);

                renderParticipants();
                if (removed) {
                    showToast(user + " đã rời phòng.");
                }
            } else if (message.startsWith("CHAT|")) {

                handleChatEvent(message);
            }
        });
    }

    private void handleChatEvent(String message) {

        String[] parts =
                message.split("\\|", 3);

        if (parts.length < 3) {
            return;
        }

        String sender = parts[1];
        String content = parts[2];

        addChatMessage(
                sender,
                content
        );
    }

    private void addChatMessage(String sender, String content) {
        boolean followLatest = chatBox.getChildren().isEmpty() || chatScroll.getVvalue() >= 0.95;
        Label senderLabel = new Label(sender.equals(username) ? sender + " · Bạn" : sender);
        senderLabel.getStyleClass().add("chat-sender");
        senderLabel.setWrapText(true);
        Label messageLabel = new Label(content);
        messageLabel.setWrapText(true);
        messageLabel.setMinWidth(0);
        messageLabel.setMaxWidth(Double.MAX_VALUE);
        messageLabel.getStyleClass().add("chat-content");
        VBox messageBox = new VBox(5, senderLabel, messageLabel);
        messageBox.getStyleClass().add("chat-message");
        if (sender.equals(username)) messageBox.getStyleClass().add("own-message");
        messageBox.setMinWidth(0);
        messageBox.setMaxWidth(Double.MAX_VALUE);
        chatBox.getChildren().add(messageBox);
        if (followLatest) Platform.runLater(() -> chatScroll.setVvalue(1));
    }

    private void renderParticipants() {
        participantsGrid.getChildren().clear();
        participantImages.keySet().retainAll(participants);
        participantViews.clear();
        cameraPlaceholders.clear();
        participantList.getChildren().clear();
        participantCountLabel.setText(participants.size() + " người");

        for (String participant : participants) {
            participantsGrid.getChildren().add(createParticipantTile(participant));
            Label avatar = new Label(initial(participant));
            avatar.getStyleClass().add("member-avatar");
            Label name = new Label(participant);
            name.getStyleClass().add("member-name");
            name.setMaxWidth(Double.MAX_VALUE);
            name.setMinWidth(0);
            name.setTooltip(new Tooltip(participant));
            Label detail = new Label(participant.equals(username) ? "Bạn" : "Thành viên");
            detail.getStyleClass().addAll("muted-text", "small-text");
            VBox identity = new VBox(3, name, detail);
            identity.setMinWidth(0);
            HBox.setHgrow(identity, Priority.ALWAYS);
            HBox row = new HBox(10, avatar, identity);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("member-row");
            participantList.getChildren().add(row);
        }
        layoutParticipantTiles();
    }

    /** Only relayout existing nodes on resize; live video references remain intact. */
    private void layoutParticipantTiles() {
        int count = participantsGrid.getChildren().size();
        if (count == 0) return;
        double width = participantsScroll.getViewportBounds().getWidth();
        double height = participantsScroll.getViewportBounds().getHeight();
        int columns = count == 1 || width < 640 ? 1 : count > 2 && width >= 1180 ? 3 : 2;
        int rows = (count + columns - 1) / columns;
        if (participantsGrid.getColumnConstraints().size() != columns) {
            participantsGrid.getColumnConstraints().clear();
            for (int i = 0; i < columns; i++) {
                ColumnConstraints column = new ColumnConstraints();
                column.setPercentWidth(100.0 / columns);
                column.setMinWidth(0);
                column.setHgrow(Priority.ALWAYS);
                participantsGrid.getColumnConstraints().add(column);
            }
        }
        if (participantsGrid.getRowConstraints().size() != rows) {
            participantsGrid.getRowConstraints().clear();
            for (int i = 0; i < rows; i++) {
                RowConstraints row = new RowConstraints(220, 220, Double.MAX_VALUE);
                row.setVgrow(Priority.ALWAYS);
                row.setPercentHeight(100.0 / rows);
                participantsGrid.getRowConstraints().add(row);
            }
        }
        for (int i = 0; i < count; i++) {
            javafx.scene.Node tile = participantsGrid.getChildren().get(i);
            GridPane.setColumnIndex(tile, i % columns);
            GridPane.setRowIndex(tile, i / columns);
        }
        double minimum = rows * 220.0 + (rows - 1) * participantsGrid.getVgap() + 3;
        participantsGrid.setMinHeight(minimum);
        participantsGrid.setPrefHeight(Math.max(minimum, height));
    }

    private VBox createParticipantTile(String participant) {
        Label nameLabel = new Label(participant);
        nameLabel.getStyleClass().add("participant-name");
        nameLabel.setMinWidth(0);
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        nameLabel.setTooltip(new Tooltip(participant));
        HBox.setHgrow(nameLabel, Priority.ALWAYS);
        HBox footer = new HBox(8, nameLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("tile-footer");
        if (participant.equals(username)) {
            Label self = new Label("Bạn");
            self.getStyleClass().add("self-badge");
            footer.getChildren().add(self);
        }

        // Keep the same ImageView/placeholder maps used by live frame callbacks.
        Label cameraLabel = new Label(initial(participant));
        cameraLabel.getStyleClass().add("camera-avatar");
        Label caption = new Label("Camera đang tắt");
        caption.getStyleClass().add("camera-caption");
        VBox placeholder = new VBox(8, cameraLabel, caption);
        placeholder.setAlignment(Pos.CENTER);
        placeholder.setMouseTransparent(true);
        placeholder.visibleProperty().bind(cameraLabel.visibleProperty());

        ImageView view = new ImageView(participantImages.get(participant));
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setVisible(view.getImage() != null);
        cameraLabel.setVisible(view.getImage() == null);
        StackPane picture = new StackPane(view, placeholder);
        picture.getStyleClass().add("video-picture");
        picture.setMinSize(0, 120);
        picture.setPrefSize(320, 240);
        view.fitWidthProperty().bind(picture.widthProperty());
        view.fitHeightProperty().bind(picture.heightProperty());
        Rectangle clip = new Rectangle();
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        clip.widthProperty().bind(picture.widthProperty());
        clip.heightProperty().bind(picture.heightProperty());
        picture.setClip(clip);
        participantViews.put(participant, view);
        cameraPlaceholders.put(participant, cameraLabel);

        VBox tile = new VBox(8, picture, footer);
        tile.getStyleClass().add("video-tile");
        if (participant.equals(username)) tile.getStyleClass().add("self-tile");
        VBox.setVgrow(picture, Priority.ALWAYS);
        tile.setMinSize(0, 220);
        tile.setPrefWidth(320);
        tile.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        GridPane.setHgrow(tile, Priority.ALWAYS);
        GridPane.setVgrow(tile, Priority.ALWAYS);
        return tile;
    }

    private static String initial(String name) {
        return name == null || name.isEmpty() ? "?"
                : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
    }

    private void showConnectionStatus(boolean connected) {
        connectionLabel.setText(connected ? "Đã kết nối" : "Mất kết nối");
        connectionLabel.getStyleClass().remove("disconnected");
        if (!connected) connectionLabel.getStyleClass().add("disconnected");
    }

    private void setMediaButtonStyle(Button button, String state) {
        button.setStyle("");
        button.getStyleClass().removeAll("media-active", "media-muted", "media-idle", "media-unavailable");
        button.getStyleClass().add(state);
    }

    private void requestVideoCredentials() {
        long generation;
        TCPClient control;
        String expectedRoom;
        synchronized (audioLock) {
            if (connectionSubscription == null || leaving) return;
            generation = ++videoGeneration;
            control = tcpClient;
            expectedRoom = roomId;
        }
        cameraButton.setDisable(true);
        setMediaButtonStyle(cameraButton, "media-unavailable");
        cameraButton.setText("Camera tắt");
        videoStatusLabel.setText("Đang kết nối video…");
        CompletableFuture<VideoCredentials> request = control.requestVideoSession(
                expectedRoom, java.time.Duration.ofSeconds(5));
        synchronized (audioLock) {
            if (generation == videoGeneration) videoRequest = request;
        }
        request.whenCompleteAsync((credentials, failure) -> {
            synchronized (audioLock) {
                if (generation != videoGeneration || videoRequest != request) {
                    if (credentials != null) credentials.clear();
                    return;
                }
                videoRequest = null;
                if (failure != null || credentials == null) {
                    videoStatus(generation, "Video unavailable. Chat and audio remain available.");
                    return;
                }
                try {
                    String host = control.getConnectedServerAddress().getHostAddress();
                    VideoClient candidate = new VideoClient(host, credentials, videoListener(generation));
                    videoClient = candidate;
                    candidate.startAsync().whenCompleteAsync((ignored, error) -> {
                        synchronized (audioLock) {
                            if (generation != videoGeneration || videoClient != candidate) {
                                candidate.stopAsync(); return;
                            }
                        }
                        if (error != null) {
                            candidate.stopAsync();
                            Throwable cause = error;
                            while (cause instanceof CompletionException && cause.getCause() != null)
                                cause = cause.getCause();
                            videoStatus(generation, cause.getMessage() != null
                                    && cause.getMessage().contains("VIDEO_FULL")
                                    ? "Video full: only two video participants are allowed in this room."
                                    : "Video unavailable. Chat and audio remain available.");
                        } else {
                            Platform.runLater(() -> {
                                synchronized (audioLock) {
                                    if (generation != videoGeneration || videoClient != candidate
                                            || !candidate.isRunning()) return;
                                }
                                cameraButton.setDisable(false);
                                setMediaButtonStyle(cameraButton, "media-idle");
                                cameraButton.setText("Bật camera");
                                videoStatusLabel.setText("Video connected — camera off");
                            });
                        }
                    }, AUDIO_TASKS);
                } catch (RuntimeException | LinkageError e) {
                    credentials.clear();
                    videoStatus(generation, "Video unavailable. Chat and audio remain available.");
                }
            }
        }, AUDIO_TASKS);
        synchronized (audioLock) {
            if (generation != videoGeneration) request.cancel(false);
        }
    }

    private VideoClient.Listener videoListener(long generation) {
        return new VideoClient.Listener() {
            @Override public void onFrame(String name, BufferedImage image, boolean local) {
                synchronized (audioLock) {
                    if (generation != videoGeneration || videoClient == null) return;
                    String key = local ? username : name;
                    if (!enabledCameras.contains(key)) return;
                    // Two authorized video peers; keep only their latest frames.
                    if (!pendingVideoFrames.containsKey(key) && pendingVideoFrames.size() >= 2) return;
                    pendingVideoFrames.put(key, image);
                    if (videoUiPending) return;
                    videoUiPending = true;
                }
                Platform.runLater(() -> renderVideoFrames(generation));
            }
            @Override public void onCameraState(String name, boolean on, boolean local) {
                String key;
                synchronized (audioLock) {
                    if (generation != videoGeneration) return;
                    key = local ? username : name;
                    boolean enabled = local ? videoClient != null && videoClient.isCameraOn()
                            && videoClient.isCameraRequested() : on;
                    if (enabled) enabledCameras.add(key);
                    else { enabledCameras.remove(key); pendingVideoFrames.remove(key); }
                }
                Platform.runLater(() -> {
                    synchronized (audioLock) {
                        if (generation != videoGeneration) return;
                        if (!enabledCameras.contains(key)) setParticipantImage(key, null);
                        if (local && videoClient != null) {
                            boolean active = videoClient.isCameraRequested();
                            cameraButton.setText(active ? "Tắt camera" : "Bật camera");
                            setMediaButtonStyle(cameraButton, active ? "media-active" : "media-idle");
                            if (videoClient.isRunning())
                                videoStatusLabel.setText(active ? "Video connected — camera on"
                                        : "Video connected — camera off");
                        }
                    }
                });
            }
            @Override public void onStatus(String message) { videoStatus(generation, message); }
            @Override public void onDisconnected() {
                Platform.runLater(() -> {
                    synchronized (audioLock) {
                        if (generation != videoGeneration) return;
                        enabledCameras.clear();
                        pendingVideoFrames.clear();
                        videoClient = null;
                    }
                    participantImages.clear();
                    participantViews.keySet().forEach(name -> setParticipantImage(name, null));
                    cameraButton.setDisable(true);
                    cameraButton.setText("Camera tắt");
                    setMediaButtonStyle(cameraButton, "media-unavailable");
                    // Preserve a specific rejection/error already reported by the client.
                    if (videoStatusLabel.getText().startsWith("Video connected"))
                        videoStatusLabel.setText("Video disconnected. Chat and audio remain available.");
                });
            }
        };
    }

    private void renderVideoFrames(long generation) {
        Map<String, BufferedImage> frames;
        synchronized (audioLock) {
            if (generation != videoGeneration) return;
            frames = new HashMap<>(pendingVideoFrames);
            pendingVideoFrames.clear();
            videoUiPending = false;
        }
        frames.forEach((name, frame) -> {
            synchronized (audioLock) {
                if (generation != videoGeneration || !enabledCameras.contains(name)) return;
            }
            int width = frame.getWidth(), height = frame.getHeight();
            WritableImage image = new WritableImage(width, height);
            int[] pixels = frame.getRGB(0, 0, width, height, null, 0, width);
            image.getPixelWriter().setPixels(0, 0, width, height,
                    PixelFormat.getIntArgbInstance(), pixels, 0, width);
            setParticipantImage(name, image);
        });
    }

    private void setParticipantImage(String name, Image image) {
        if (image == null) participantImages.remove(name); else participantImages.put(name, image);
        ImageView view = participantViews.get(name);
        if (view != null) { view.setImage(image); view.setVisible(image != null); }
        Label placeholder = cameraPlaceholders.get(name);
        if (placeholder != null) placeholder.setVisible(image == null);
    }

    private void videoStatus(long generation, String message) {
        Platform.runLater(() -> {
            synchronized (audioLock) { if (generation != videoGeneration) return; }
            videoStatusLabel.setText(message);
        });
    }

    @FXML private void handleCamera() {
        synchronized (audioLock) {
            if (videoClient == null || !videoClient.isRunning()) return;
            boolean enable = !videoClient.isCameraRequested();
            videoClient.setCameraEnabled(enable);
            cameraButton.setText(enable ? "Đang mở..." : "Bật camera");
        }
    }

    /** Safe from any lifecycle thread. No device or socket operations on JavaFX. */
    private void invalidateVideo() {
        long generation;
        CompletableFuture<VideoCredentials> request;
        VideoClient client;
        synchronized (audioLock) {
            generation = ++videoGeneration;
            request = videoRequest; videoRequest = null;
            client = videoClient; videoClient = null;
            enabledCameras.clear(); pendingVideoFrames.clear(); videoUiPending = false;
        }
        if (request != null) request.cancel(false);
        if (client != null) client.stopAsync();
        Platform.runLater(() -> {
            synchronized (audioLock) { if (generation != videoGeneration) return; }
            participantImages.clear();
            participantViews.keySet().forEach(name -> setParticipantImage(name, null));
            cameraButton.setDisable(true);
            cameraButton.setText("Camera tắt");
            setMediaButtonStyle(cameraButton, "media-unavailable");
            videoStatusLabel.setText("Video stopped");
        });
    }

    private void requestAudioCredentials() {
        resetMicrophoneControls();
        statusLabel.setText("Đang yêu cầu phiên âm thanh…");
        long generation;
        synchronized (audioLock) {
            if (connectionSubscription == null) {
                return;
            }
            audioLifecycleActive = true;
            generation = meetingGeneration;
        }
        CompletableFuture<AudioCredentials> request = tcpClient.requestAudioSession(
                roomId, java.time.Duration.ofSeconds(5));
        boolean abandoned;
        synchronized (audioLock) {
            abandoned = !audioLifecycleActive || generation != meetingGeneration;
            if (!abandoned) {
                audioRequest = request;
            }
        }
        // Always attach cleanup, even if disconnect won the assignment race
        // and successful credentials have already reached the future.
        request.whenCompleteAsync((credentials, failure) -> {
            synchronized (audioLock) {
                if (!audioLifecycleActive || generation != meetingGeneration
                        || audioRequest != request) {
                    if (credentials != null) credentials.clear();
                    return;
                }
                audioRequest = null;
            }
            if (failure != null || credentials == null || credentials.isExpired()) {
                if (credentials != null) credentials.clear();
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                updateAudioStatus(generation, cause instanceof TimeoutException
                        ? "Yêu cầu phiên âm thanh quá thời gian chờ."
                        : "Không thể cấp phiên âm thanh. Trò chuyện vẫn khả dụng.");
                return;
            }
            startAudio(generation, credentials);
        }, AUDIO_TASKS);
        if (abandoned) {
            request.cancel(false);
        }
    }

    // Runs on AUDIO_TASKS, never on the JavaFX thread.
    private void startAudio(long generation, AudioCredentials credentials) {
        AudioClient candidate = null;
        try {
            synchronized (audioLock) {
                if (!audioLifecycleActive || generation != meetingGeneration
                        || audioClient != null) {
                    credentials.clear();
                    return;
                }
                String host = tcpClient.getConnectedServerAddress().getHostAddress();
                candidate = new AudioClient(host, credentials);
                // AudioClient now owns credentials and wipes them in stopAsync().
                audioClient = candidate;
                audioReady = false;
            }
            AudioClient client = candidate;
            long expiresAt = credentials.getExpiresAt();
            updateAudioStatus(generation, "Đang kết nối âm thanh…");
            client.startAsync().whenCompleteAsync((ignored, failure) -> {
                if (failure != null || !client.isRunning()
                        || System.currentTimeMillis() >= expiresAt) {
                    finishAudio(generation, client, "Không thể khởi động âm thanh. Trò chuyện vẫn khả dụng.");
                    return;
                }
                synchronized (audioLock) {
                    if (!audioLifecycleActive || generation != meetingGeneration
                            || audioClient != client) {
                        client.stopAsync();
                        return;
                    }
                    audioReady = true;
                    credentialExpiry = Thread.ofVirtual().name("meeting-audio-monitor")
                            .start(() -> monitorAudio(generation, client, expiresAt));
                }
                refreshAudioControls(generation, client);
            }, AUDIO_TASKS);
        } catch (RuntimeException e) {
            if (candidate == null) {
                credentials.clear();
                updateAudioStatus(generation, "Không thể kết nối âm thanh. Trò chuyện vẫn khả dụng.");
            } else {
                finishAudio(generation, candidate, "Không thể khởi động âm thanh. Trò chuyện vẫn khả dụng.");
            }
        }
    }

    private void monitorAudio(long generation, AudioClient client, long expiresAt) {
        boolean receiveOnly = client.isReceiveOnly();
        try {
            while (System.currentTimeMillis() < expiresAt && client.isRunning()) {
                Thread.sleep(250);
                synchronized (audioLock) {
                    if (generation != meetingGeneration || audioClient != client) return;
                }
                if (receiveOnly != client.isReceiveOnly()) {
                    receiveOnly = client.isReceiveOnly();
                    refreshAudioControls(generation, client);
                }
            }
            finishAudio(generation, client, System.currentTimeMillis() >= expiresAt
                    ? "Phiên âm thanh đã hết hạn. Mic đã tắt."
                    : "Kết nối âm thanh đã dừng. Trò chuyện vẫn khả dụng.");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // Audio failure/expiration does not end the TCP meeting subscription.
    private void finishAudio(long generation, AudioClient client, String status) {
        synchronized (audioLock) {
            if (generation == meetingGeneration && audioClient == client) {
                audioClient = null;
                audioReady = false;
                if (credentialExpiry != null) credentialExpiry.interrupt();
                credentialExpiry = null;
            }
        }
        client.stopAsync();
        updateAudioStatus(generation, status);
    }

    private void refreshAudioControls(long generation, AudioClient client) {
        Platform.runLater(() -> {
            synchronized (audioLock) {
                if (!audioLifecycleActive || generation != meetingGeneration
                        || audioClient != client || !audioReady) return;
                boolean available = client.isRunning() && !client.isReceiveOnly();
                boolean muted = client.isMuted();
                micButton.setDisable(!available);
                micButton.setText(!available ? "Mic tắt" : muted ? "Bật mic" : "Tắt mic");
                setMediaButtonStyle(micButton, !available ? "media-unavailable"
                        : muted ? "media-muted" : "media-active");
                statusLabel.setText(!available ? "Receive-only"
                        : muted ? "Mic đã tắt." : "Âm thanh đã kết nối. Mic đang bật.");
            }
        });
    }

    private void resetMicrophoneControls() {
        micButton.setDisable(true);
        micButton.setText("Mic tắt");
        setMediaButtonStyle(micButton, "media-unavailable");
    }

    private void updateAudioStatus(long generation, String status) {
        Platform.runLater(() -> {
            synchronized (audioLock) {
                if (!audioLifecycleActive || generation != meetingGeneration) return;
                statusLabel.setText(status);
                resetMicrophoneControls();
            }
        });
    }

    private void invalidateAudioSession() {
        invalidateAudioSession(-1);
    }

    /**
     * -1 invalidates unconditionally; a nonnegative value invalidates only the
     * matching meeting generation, so old connection callbacks cannot wipe a
     * newer meeting's credentials.
     */
    private long invalidateAudioSession(long expectedGeneration) {
        CompletableFuture<AudioCredentials> pending;
        Thread expiry;
        AudioClient stoppedClient;
        TCPClient.ConnectionSubscription subscription;
        long invalidatedGeneration;
        synchronized (audioLock) {
            if (expectedGeneration >= 0 && meetingGeneration != expectedGeneration) {
                return -1;
            }
            invalidateVideo();
            meetingGeneration++;
            invalidatedGeneration = meetingGeneration;
            audioLifecycleActive = false;
            subscription = connectionSubscription;
            connectionSubscription = null;
            pending = audioRequest;
            audioRequest = null;
            expiry = credentialExpiry;
            credentialExpiry = null;
            stoppedClient = audioClient;
            audioClient = null;
            audioReady = false;
        }
        if (subscription != null) {
            subscription.close();
        }
        if (pending != null) {
            // The completion handler clears a result that won a cancellation race.
            pending.cancel(false);
        }
        if (expiry != null) {
            expiry.interrupt();
        }
        if (stoppedClient != null) stoppedClient.stopAsync();
        return invalidatedGeneration;
    }

    @FXML
    private void handleMic() {
        AudioClient client;
        long generation;
        synchronized (audioLock) {
            client = audioClient;
            generation = meetingGeneration;
            if (!audioReady || client == null || !client.isRunning()
                    || client.isReceiveOnly()) {
                resetMicrophoneControls();
                return;
            }
            if (client.isMuted()) client.unmute();
            else client.mute();
        }
        refreshAudioControls(generation, client);
    }

    @FXML
    private void handleLeave() {
        if (leaving) return;
        leaving = true;
        stopAudioForLeave();
        invalidateVideo();
        resetMicrophoneControls();
        long generation;
        TCPClient leavingClient;
        synchronized (audioLock) {
            generation = meetingGeneration;
            leavingClient = tcpClient;
        }
        AUDIO_TASKS.execute(() -> {
        try {
            synchronized (audioLock) {
                if (generation != meetingGeneration || tcpClient != leavingClient) return;
            }

            if (leavingClient != null) {
                leavingClient.send("LEAVE_ROOM");

                String response = leavingClient.receive();

                if (!"LEAVE_SUCCESS".equals(response)) {
                    Platform.runLater(() -> {
                        synchronized (audioLock) { if (generation != meetingGeneration) return; }
                        leaving = false;
                        statusLabel.setText("Không thể rời phòng.");
                    });
                    return;
                }

                // Quan trọng
                synchronized (audioLock) {
                    if (generation != meetingGeneration || tcpClient != leavingClient) return;
                    leavingClient.clearEventListener();
                }
            }

            Platform.runLater(() -> {
                synchronized (audioLock) { if (generation != meetingGeneration) return; }
                invalidateAudioSession();
                try { openHome(); }
                catch (Exception e) { leaving = false; statusLabel.setText("Không thể mở trang chủ."); }
            });

        } catch (Exception e) {
            Platform.runLater(() -> {
                synchronized (audioLock) { if (generation != meetingGeneration) return; }
                leaving = false;
                statusLabel.setText("Lỗi khi rời phòng.");
            });
        }
        });
    }

    /** Stop audio immediately, retaining the TCP subscription until leave succeeds. */
    private void stopAudioForLeave() {
        CompletableFuture<AudioCredentials> pending;
        Thread monitor;
        AudioClient client;
        synchronized (audioLock) {
            // Invalidates pending startup callbacks without suppressing room events
            // or changing the generation captured by the TCP lifecycle listener.
            audioLifecycleActive = false;
            audioReady = false;
            pending = audioRequest;
            audioRequest = null;
            monitor = credentialExpiry;
            credentialExpiry = null;
            client = audioClient;
            audioClient = null;
        }
        if (pending != null) pending.cancel(false);
        if (monitor != null) monitor.interrupt();
        if (client != null) client.stopAsync();
    }

    private void openHome() throws Exception {

        FXMLLoader loader =
                new FXMLLoader(
                        getClass().getResource(
                                "/com/example/onlinemeeting/fxml/home-view.fxml"
                        )
                );

        Stage stage =
                (Stage) roomIdLabel
                        .getScene()
                        .getWindow();

        Scene scene =
                new Scene(
                        loader.load(),
                        stage.getScene().getWidth(),
                        stage.getScene().getHeight()
                );

        HomeController controller =
                loader.getController();

        // Dùng lại TCP connection hiện tại
        controller.setData(
                tcpClient,
                username
        );

        stage.setScene(scene);
    }

    @FXML
    private void handleSendMessage() {

        String message =
                messageField.getText().trim();

        if (message.isEmpty()) {
            return;
        }

        if (tcpClient == null) {
            return;
        }

        try {
            tcpClient.send("CHAT|" + message);
            messageField.clear();
        } catch (IllegalStateException e) {
            invalidateAudioSession();
            resetMicrophoneControls();
            showConnectionStatus(false);
            statusLabel.setText("Mất kết nối tới máy chủ. Mic vẫn tắt.");
        }
    }
}