package com.imagetoonsong;

import com.imagetoonsong.core.TessData;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * JavaFX application class.
 *
 * Kept separate from MainApp intentionally — see MainApp for the reason.
 * All JavaFX initialisation (loading FXML, setting up the primary stage,
 * etc.) lives here and in your controllers.
 */
public class App extends Application {

    TessData tessData;
    @Override
    public void start(Stage primaryStage) throws Exception {
        // TODO: load your root FXML / scene here
         FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
         Scene scene = new Scene(loader.load());
         primaryStage.setScene(scene);
         primaryStage.setTitle("ImageToOnSong");
         primaryStage.show();

         tessData = new TessData();
    }

    @Override
    public void stop() throws Exception {
        if  (tessData != null) {
            tessData.close();
        }
        super.stop();
    }
}