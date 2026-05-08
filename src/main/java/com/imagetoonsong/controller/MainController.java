package com.imagetoonsong.controller;

import com.imagetoonsong.core.ImageSource;
import com.imagetoonsong.core.OcrProcessor;
import com.imagetoonsong.core.OnSongBuilder;
import com.imagetoonsong.events.AppEventBus;
import com.imagetoonsong.events.DropImageEvent;
import com.imagetoonsong.events.PasteImageEvent;
import com.imagetoonsong.events.ShutdownRequestEvent;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainController {

    private static final Logger logger = LoggerFactory.getLogger(
            MethodHandles.lookup().lookupClass());
    @FXML public MenuItem aboutMenuItem;
    @FXML public MenuItem openFileMenuItem;
    @FXML public MenuItem exitMenuItem;
    @FXML private ImageView imageView;
    @FXML private TextArea resultTextArea;
    @FXML private Label imageInfoLabel;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Button uploadButton;
    @FXML private Button convertButton;
    @FXML private Button copyButton;
    @FXML private Button downloadButton;
    @FXML public Button clearTextButton;
    @FXML public Button viewHtmlButton;
    @FXML private ComboBox<String> styleCombo;
    // --- New FXML fields ---
    @FXML private WebView htmlWebView;
    @FXML private Tab htmlViewerTab;
    @FXML private TabPane mainTabPane;
    @FXML private Label htmlFileLabel;


    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ImageSource imageSource;

    @FXML
    public void initialize() {
        // Button actions
        uploadButton.setOnAction(_ -> openImage());
        convertButton.setOnAction(_ -> convertImage());
        copyButton.setOnAction(_ -> copyToClipboard());
        downloadButton.setOnAction(_ -> downloadFile());
        clearTextButton.setOnAction(_ -> clearText());
        viewHtmlButton.setOnAction(_ -> openHtmlFile());
        
        // Drag & Drop support
        imageView.setOnDragOver(this::handleDragOver);
        imageView.setOnDragDropped(this::handleDragDropped);

        // 1. Make it capable of receiving focus
        imageView.setFocusTraversable(true);

        // 2. Give it focus when clicked (so it can hear the keyboard)
        imageView.setOnMouseClicked(_ -> imageView.requestFocus());

        // Disable buttons initially
        convertButton.setDisable(true);
        copyButton.setDisable(true);
        downloadButton.setDisable(true);

        // Populate ComboBox in code (more reliable than FXML)
        styleCombo.getItems().addAll(
                "Bracketed Chords [C]",
                "Chords Over Lyrics"
        );
        styleCombo.setValue("Bracketed Chords [C]");
        AppEventBus.getInstance().subscribe(event -> {
            if (event instanceof PasteImageEvent(ImageSource src)) {
                handleChordImage(src);
            }
            if (event instanceof DropImageEvent(ImageSource src)) {
                handleChordImage(src);
            }
        });

    }


    @FXML
    private void handleExit() {
        shutdown();
        AppEventBus.getInstance().post(new ShutdownRequestEvent());
//        Platform.exit();
//        System.exit(0);
    }

    @FXML
    private void handleAboutAction() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About ImageToonSong");
        alert.setHeaderText("Image to OnSong Converter v1.0");
        alert.setContentText("""
                A utility for musicians to convert OCR chord sheet images \
                into OnSong-compatible bracketed text format.
                
                Built with JavaFX.""");

        alert.showAndWait();
    }

    @FXML
    private void openImage() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Chord Sheet Image");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.bmp")
        );

        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            loadImage(file);
        }
    }


    // --- Handler for the "View HTML" button ---
    @FXML
    private void openHtmlFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open HTML File");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("HTML Files", "*.html", "*.htm")
        );

        File file = fileChooser.showOpenDialog(mainTabPane.getScene().getWindow());
        if (file != null) {
            htmlWebView.getEngine().load(file.toURI().toString());
            htmlFileLabel.setText(file.getName());
            htmlFileLabel.setStyle(""); // clear italic-only style if desired
            // Switch to the HTML Viewer tab automatically
            mainTabPane.getSelectionModel().select(htmlViewerTab);
            statusLabel.setText("Loaded: " + file.getName());
        }
    }

    private void handleChordImage(ImageSource src) {
        imageSource = src;
        imageSource.saveImage(new File("build/clipboard.png"));
        Image image = imageSource.image();
        imageView.setImage(image);
        imageInfoLabel.setText(imageSource.source() + " (" + (int)image.getWidth() + "×" + (int)image.getHeight() + ")");
        statusLabel.setText("Image loaded. Click Convert to process.");
        convertButton.setDisable(false);
    }

    private void loadImage(File file) {
        try {
            ImageSource imageSource = ImageSource.fromFile(file);
            handleChordImage(imageSource);
        } catch (Exception e) {
            showError("Failed to load image", e.getMessage());
        }
    }

    private void convertImage() {
        progressIndicator.setVisible(true);
        statusLabel.setText("Starting OCR...");
        convertButton.setDisable(true);
        executor.submit(() -> {
            logger.info("=== OCR START ===");

            long startTime = System.currentTimeMillis();
            Task<String> ocrTask = new Task<>() {
                @Override
                protected String call() throws Exception {
                    OcrProcessor ocrProcessor = new OcrProcessor();
                    OnSongBuilder builder = new OnSongBuilder();
                    final String existingText = resultTextArea.getText();
                    boolean emptyTextBox = existingText.isEmpty();
                    String rawText = ocrProcessor.extractText(imageSource);
                    logger.info("OCR finished - raw text length: {}", rawText.length());
                    return builder.buildOnSong(rawText, "Untitled Song", "Unknown Artist", emptyTextBox);
                }
            };

            ocrTask.setOnSucceeded(_ -> {
                long duration = System.currentTimeMillis() - startTime;
                String result = ocrTask.getValue();
                String resultText = result.isEmpty() ? "No text detected." : result;
                String currentOnSongText = resultTextArea.getText() + resultText;
                resultTextArea.setText(currentOnSongText);
                statusLabel.setText("✅ Done in " + (duration / 1000) + " seconds");
                progressIndicator.setVisible(false);
                convertButton.setDisable(false);
                copyButton.setDisable(false);
                downloadButton.setDisable(false);
                logger.info("=== OCR SUCCESS in {} ms ===", duration);
            });
            ocrTask.setOnFailed(_ -> {
                Throwable ex = ocrTask.getException();
                statusLabel.setText("❌ Failed: " + ex.getMessage());
                showError("OCR Failed", ex.getMessage());
                logger.error("OCR task failed: {}", ex.getMessage());
                progressIndicator.setVisible(false);
                convertButton.setDisable(false);
            });
            Thread ocrThread = new Thread(ocrTask);
            ocrThread.setName("ocr-worker-" + imageSource.source());
            ocrThread.setDaemon(true);
            ocrThread.start();
        });

    }

    private void copyToClipboard() {
        String currentOnSongText = resultTextArea.getText();
        if (currentOnSongText.isEmpty()) return;
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(currentOnSongText);
        clipboard.setContent(content);
        statusLabel.setText("Copied to clipboard!");
    }

    private void clearText() {
        resultTextArea.setText("");
    }

    private void downloadFile() {
        String currentOnSongText = resultTextArea.getText();
        if (currentOnSongText.isEmpty()) return;

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save OnSong File");
        fileChooser.setInitialFileName("song.onsong");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("OnSong File", "*.onsong"));

        File file = fileChooser.showSaveDialog(null);
        if (file != null) {
            try {
                Files.writeString(file.toPath(), currentOnSongText);
                statusLabel.setText("File saved: " + file.getName());
            } catch (IOException e) {
                showError("Save Failed", e.getMessage());
            }
        }
    }

    private void handleDragOver(DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void handleDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        boolean success = false;
        if (db.hasFiles()) {
            File file = db.getFiles().getFirst();
            if (file.getName().toLowerCase().matches(".*\\.(png|jpg|jpeg|bmp)$")) {
                loadImage(file);
                success = true;
            }
        }
        event.setDropCompleted(success);
        event.consume();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    // Clean shutdown
    public void shutdown() {
        executor.shutdown();
    }
}