package com.openclaw.audiolistener;

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

/**
 * Java helper to bypass Kotlin null-safety checks when passing null AssetManager.
 * Sherpa-ONNX JNI treats null AssetManager as "load from filesystem path".
 */
public class SpeakerHelper {
    public static SpeakerEmbeddingExtractor createExtractor(SpeakerEmbeddingExtractorConfig config) {
        return new SpeakerEmbeddingExtractor(null, config);
    }
}
