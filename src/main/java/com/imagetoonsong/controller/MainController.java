package com.imagetoonsong.controller;

import com.imagetoonsong.core.OnSongBuilder;
import com.imagetoonsong.core.OcrProcessor;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;
//import javafx.stage.Stage;

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
    @FXML private ComboBox<String> styleCombo;

    private File currentImageFile;
    private String currentOnSongText = "";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @FXML
    public void initialize() {
        // Button actions
        uploadButton.setOnAction(e -> openImage());
        convertButton.setOnAction(e -> convertImage());
        copyButton.setOnAction(e -> copyToClipboard());
        downloadButton.setOnAction(e -> downloadFile());

        // Drag & Drop support
        imageView.setOnDragOver(this::handleDragOver);
        imageView.setOnDragDropped(this::handleDragDropped);

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

    private void loadImage(File file) {
        try {
            currentImageFile = file;
            Image image = new Image(file.toURI().toString());
            imageView.setImage(image);
            imageInfoLabel.setText(file.getName() + " (" + (int)image.getWidth() + "×" + (int)image.getHeight() + ")");

            statusLabel.setText("Image loaded. Click Convert to process.");
            convertButton.setDisable(false);

        } catch (Exception e) {
            showError("Failed to load image", e.getMessage());
        }
    }

    private void convertImage() {
        if (currentImageFile == null) return;

        progressIndicator.setVisible(true);
        statusLabel.setText("Starting OCR...");
        convertButton.setDisable(true);
        resultTextArea.setText("");

        executor.submit(() -> {
            try {
                System.out.println("=== OCR START ===");
                System.out.println("Image file: " + currentImageFile.getAbsolutePath());

                long startTime = System.currentTimeMillis();

                OcrProcessor ocr = new OcrProcessor();
                System.out.println("OcrProcessor created");

                String rawText = ocr.extractText(currentImageFile);
                System.out.println("OCR finished - raw text length: " + rawText.length());

                String barLineDetection = ocr.detectBarlinePattern(currentImageFile, new File(currentImageFile.getParent(), "barline.png"));
                long duration = System.currentTimeMillis() - startTime;
                System.out.printf("BarLine Text : %s\n", barLineDetection);

                OnSongBuilder builder = new OnSongBuilder();
                String result = builder.buildOnSong(rawText, "Untitled Song", "Unknown Artist");

                System.out.println("OnSong build complete");

                Platform.runLater(() -> {
                    currentOnSongText = result;
                    resultTextArea.setText(result.isEmpty() ? "No text detected." : result);
                    statusLabel.setText("✅ Done in " + (duration / 1000) + " seconds");
                    progressIndicator.setVisible(false);
                    convertButton.setDisable(false);
                    copyButton.setDisable(false);
                    downloadButton.setDisable(false);
                });

                System.out.println("=== OCR SUCCESS in " + duration + " ms ===");

            } catch (Exception e) {
                System.err.println("=== OCR FAILED ===");
                e.printStackTrace();
                Platform.runLater(() -> {
                    showError("OCR Failed", e.getMessage());
                    progressIndicator.setVisible(false);
                    convertButton.setDisable(false);
                    statusLabel.setText("Failed - check console");
                });
            }
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