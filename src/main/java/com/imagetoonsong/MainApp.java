package com.imagetoonsong;

import com.imagetoonsong.controller.MainController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(MainApp.class.getResource("/fxml/main.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1280, 820);

        MainController controller = fxmlLoader.getController();
        stage.setOnCloseRequest(e -> controller.shutdown());

        stage.setTitle("ImageToOnSong - Chord Chart to OnSong (Bracketed)");
        stage.setScene(scene);
        stage.setMinWidth(1000);
        stage.setMinHeight(700);
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}