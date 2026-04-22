package com.imagetoonsong;

import com.imagetoonsong.core.ImageSource;
import com.imagetoonsong.core.TessData;
import com.imagetoonsong.events.AppEventBus;
import com.imagetoonsong.events.PasteImageEvent;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;

/**
 * JavaFX application class.
 * <p>
 * Kept separate from MainApp intentionally — see MainApp for the reason.
 * All JavaFX initialisation (loading FXML, setting up the primary stage,
 * etc.) lives here and in your controllers.
 */
public class App extends Application {

    TessData tessData;

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        Scene scene = new Scene(loader.load());
        primaryStage.setScene(scene);
        primaryStage.setTitle("ImageToOnSong");
        primaryStage.show();
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_ANY), () -> {
            Clipboard cb = Clipboard.getSystemClipboard();
            if (cb.hasImage()) {
                ImageSource src = ImageSource.fromClipboard(cb.getImage());
                AppEventBus.getInstance().post(new PasteImageEvent(src));
            }
        });

        tessData = new TessData();
    }

    @Override
    public void stop() throws Exception {
        if (tessData != null) {
            tessData.close();
        }
        super.stop();
    }
}