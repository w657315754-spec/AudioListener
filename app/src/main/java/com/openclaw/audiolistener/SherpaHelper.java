package com.openclaw.audiolistener;

import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;

/**
 * Java helper to bypass Kotlin null-safety checks when passing null AssetManager.
 * Sherpa-ONNX JNI treats null AssetManager as "load from filesystem path".
 */
public class SherpaHelper {
    public static OfflineRecognizer createRecognizer(OfflineRecognizerConfig config) {
        return new OfflineRecognizer(null, config);
    }
}
