package client.audio;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

public class BackgroundMusicPlayer {
    private MediaPlayer player;
    private boolean enabled = true;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            play();
        } else {
            pause();
        }
    }

    public void play() {
        if (!enabled) {
            return;
        }
        MediaPlayer mediaPlayer = player();
        if (mediaPlayer != null) {
            mediaPlayer.play();
        }
    }

    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    public void stop() {
        if (player != null) {
            player.stop();
        }
    }

    public void dispose() {
        if (player != null) {
            player.dispose();
            player = null;
        }
    }

    private MediaPlayer player() {
        if (player != null) {
            return player;
        }
        var resource = getClass().getResource("/audio/background.mp3");
        if (resource == null) {
            System.err.println("background music not found: /audio/background.mp3");
            return null;
        }
        player = new MediaPlayer(new Media(resource.toExternalForm()));
        player.setCycleCount(MediaPlayer.INDEFINITE);
        player.setVolume(0.35);
        return player;
    }
}
