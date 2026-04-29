package com.imagetoonsong;

import com.imagetoonsong.core.ImageMetadata;
import com.imagetoonsong.core.ImageSource;
import com.imagetoonsong.core.TessData;
import com.imagetoonsong.events.AppEventBus;
import com.imagetoonsong.events.DropImageEvent;
import com.imagetoonsong.events.PasteImageEvent;
import com.imagetoonsong.events.ShutdownRequestEvent;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.util.Objects;

/**
 * JavaFX application class.
 * <p>
 * Kept separate from MainApp intentionally — see MainApp for the reason.
 * All JavaFX initialisation (loading FXML, setting up the primary stage,
 * etc.) lives here and in your controllers.
 */
public class App extends Application {

    private static final Logger logger = LoggerFactory.getLogger(
            MethodHandles.lookup().lookupClass());
    TessData tessData;

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        tessData = new TessData();
        Scene scene = new Scene(loader.load());
        sceneSetup(scene);
        primaryStageSetup(primaryStage, scene);
        eventBusSetup();
        primaryStage.show();
    }

    private void primaryStageSetup(Stage primaryStage, Scene scene) {
        primaryStage.setScene(scene);
        primaryStage.setTitle("ImageToOnSong");
        primaryStage.setOnCloseRequest(event -> {
            prepareShutdown();
        });

        try {
            URL iconStream = getClass().getResource("/ImageToOnSong-Master.png");
            primaryStage.getIcons().add(new Image(iconStream.toString()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sceneSetup(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_ANY), () -> {
            Clipboard cb = Clipboard.getSystemClipboard();
            if (cb.hasImage()) {
                ImageSource src = ImageSource.fromClipboard(cb.getImage());
                AppEventBus.getInstance().post(new PasteImageEvent(src));
            }
        });
        scene.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles() || event.getDragboard().hasImage()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        scene.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;

            if (db.hasImage()) {
                // Option A: Direct image from another app
                Image image = db.getImage();
                ImageSource imageSource = new ImageSource(image, ImageMetadata.estimateDpiFromDimensions((int)image.getWidth()), "DropEvent") ;
                AppEventBus.getInstance().post(new DropImageEvent(imageSource));
                success = true;
            } else if (db.hasFiles()) {
                // Option B: Image file dropped from Finder/Explorer
                File file = db.getFiles().get(0);
                Image image = new Image(file.toURI().toString());
                ImageSource imageSource = new ImageSource(image, ImageMetadata.estimateDpiFromDimensions((int)image.getWidth()), "DropEvent") ;
                AppEventBus.getInstance().post(new DropImageEvent(imageSource));
                success = true;
            }

            event.setDropCompleted(success);
            event.consume();
        });
    }

    private void eventBusSetup() {
        AppEventBus.getInstance().subscribe(event -> {
            if (event instanceof ShutdownRequestEvent()) {
                prepareShutdown();
            }
        });
    }

    private void prepareShutdown() {
        try {
            this.stop();
            Platform.exit();
            System.exit(0);
        } catch (Exception e){
           System.exit(0);
        }
    }
    @Override
    public void stop() throws Exception {
        logger.info("App.stop() was called");
        AppEventBus.getInstance().shutdown();
        if (tessData != null) {
            tessData.close();
            tessData = null;
        }
        super.stop();
    }
}