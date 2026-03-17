package com.openclaw.audiolistener;

import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;

/**
 * Java helper to bypass Kotlin null-safety checks when passing null AssetManager.
 * Sherpa-ONNX JNI treats null AssetManager as "load from filesystem path".
 */
public class SherpaHelper {
    public static OfflineRecognizer createRecognizer(OfflineRecognizerConfig config) {
        return new OfflineRecognizer(null, config);
    }

    public static OnlineRecognizer createOnlineRecognizer(OnlineRecognizerConfig config) {
        return new OnlineRecognizer(null, config);
    }
}
