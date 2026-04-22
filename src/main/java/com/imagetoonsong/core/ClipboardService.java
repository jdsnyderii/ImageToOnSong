package com.imagetoonsong.core;

import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;

public class ClipboardService {
    public static void pasteImageTo(ImageView view) {
        Clipboard cb = Clipboard.getSystemClipboard();
        if (cb.hasImage()) {
            view.setImage(cb.getImage());
        }
    }
}