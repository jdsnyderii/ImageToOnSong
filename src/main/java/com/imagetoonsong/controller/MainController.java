package com.imagetoonsong.controller;

import com.imagetoonsong.core.ImageSource;
import com.imagetoonsong.core.OnSongBuilder;
import com.imagetoonsong.core.OcrProcessor;
import com.imagetoonsong.events.AppEventBus;
import com.imagetoonsong.events.PasteImageEvent;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainController {

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
    @FXML private ComboBox<String> styleCombo;

    private String currentOnSongText = "";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ImageSource imageSource;

    @FXML
    public void initialize() {
        // Button actions
        uploadButton.setOnAction(e -> openImage());
        convertButton.setOnAction(e -> convertImage());
        copyButton.setOnAction(e -> copyToClipboard());
        downloadButton.setOnAction(e -> downloadFile());
        clearTextButton.setOnAction(e -> clearText());

        // Drag & Drop support
        imageView.setOnDragOver(this::handleDragOver);
        imageView.setOnDragDropped(this::handleDragDropped);

        // 1. Make it capable of receiving focus
        imageView.setFocusTraversable(true);

        // 2. Give it focus when clicked (so it can hear the keyboard)
        imageView.setOnMouseClicked(e -> imageView.requestFocus());

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
        });

    }


    @FXML
    private void handleExit() {
        Platform.exit();
        System.exit(0);
    }

    @FXML
    private void handleAboutAction() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About ImageToonSong");
        alert.setHeaderText("Image to OnSong Converter v1.0");
        alert.setContentText("A utility for musicians to convert OCR chord sheet images " +
                "into OnSong-compatible bracketed text format.\n\n" +
                "Built with JavaFX.");

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

    private void handleChordImage(ImageSource src) {
        imageSource = src;
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
            System.out.println("=== OCR START ===");


            OcrProcessor ocrProcessor = new OcrProcessor();
            OnSongBuilder builder = new OnSongBuilder();
            final String existingText = resultTextArea.getText();
            boolean emptyTextBox = existingText.isEmpty();
            long startTime = System.currentTimeMillis();

            Task<String> ocrTask = new Task<>() {
                @Override
                protected String call() throws Exception {
                    String rawText = ocrProcessor.extractText(imageSource);
                    System.out.println("OCR finished - raw text length: " + rawText.length());
                    return builder.buildOnSong(rawText, "Untitled Song", "Unknown Artist", emptyTextBox);
                }
            };

            ocrTask.setOnSucceeded(e -> {
                long duration = System.currentTimeMillis() - startTime;
                String result = ocrTask.getValue();
                String resultText = result.isEmpty() ? "No text detected." : result;
                currentOnSongText = existingText + resultText;
                resultTextArea.setText(currentOnSongText);
                statusLabel.setText("✅ Done in " + (duration / 1000) + " seconds");
                progressIndicator.setVisible(false);
                convertButton.setDisable(false);
                copyButton.setDisable(false);
                downloadButton.setDisable(false);
                System.out.println("=== OCR SUCCESS in " + duration + " ms ===");
            });
            ocrTask.setOnFailed(e -> {
                Throwable ex = ocrTask.getException();
                statusLabel.setText("❌ Failed: " + ex.getMessage());
                showError("OCR Failed", ex.getMessage());
                System.err.println("OCR task failed: " + ex.getMessage());
                progressIndicator.setVisible(false);
                convertButton.setDisable(false);
            });
            new Thread(ocrTask).start();
        });

    }

    private void copyToClipboard() {
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
            File file = db.getFiles().get(0);
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