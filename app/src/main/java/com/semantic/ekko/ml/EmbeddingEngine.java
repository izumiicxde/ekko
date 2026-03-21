package com.semantic.ekko.ml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class EmbeddingEngine {

    private static final String TAG = "EmbeddingEngine";
    private static final String MODEL_PATH = "all_minilm_l6_v2.tflite";
    private static final int MAX_SEQ_LENGTH = 128;
    private static final int EMBEDDING_DIM = 384;

    private final Interpreter interpreter;
    private final int inputCount;

    // =========================
    // INIT
    // =========================

    public EmbeddingEngine(Context context) throws IOException {
        interpreter = new Interpreter(loadModelFile(context));
        inputCount = interpreter.getInputTensorCount();

        // Debug: log all input and output tensor shapes
        for (int i = 0; i < inputCount; i++) {
            Log.d(TAG, "Input " + i + ": "
                    + Arrays.toString(interpreter.getInputTensor(i).shape())
                    + " dtype=" + interpreter.getInputTensor(i).dataType());
        }
        Log.d(TAG, "Output 0: "
                + Arrays.toString(interpreter.getOutputTensor(0).shape())
                + " dtype=" + interpreter.getOutputTensor(0).dataType());
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(MODEL_PATH);
        FileInputStream stream = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel = stream.getChannel();
        return channel.map(FileChannel.MapMode.READ_ONLY,
                fd.getStartOffset(), fd.getDeclaredLength());
    }

    // =========================
    // EMBED
    // =========================

    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) return null;

        int[] inputIds      = tokenize(text);
        int[] attentionMask = buildAttentionMask(inputIds);

        ByteBuffer inputIdsBuf      = intArrayToBuffer(inputIds);
        ByteBuffer attentionMaskBuf = intArrayToBuffer(attentionMask);

        ByteBuffer outputBuf = ByteBuffer.allocateDirect(EMBEDDING_DIM * 4)
                .order(ByteOrder.nativeOrder());

        Map<Integer, Object> outputMap = new HashMap<>();
        outputMap.put(0, outputBuf);

        if (inputCount >= 3) {
            int[] tokenTypeIds = new int[MAX_SEQ_LENGTH];
            ByteBuffer tokenTypeIdsBuf = intArrayToBuffer(tokenTypeIds);
            ByteBuffer[] inputs = { inputIdsBuf, attentionMaskBuf, tokenTypeIdsBuf };
            interpreter.runForMultipleInputsOutputs(inputs, outputMap);
        } else {
            ByteBuffer[] inputs = { inputIdsBuf, attentionMaskBuf };
            interpreter.runForMultipleInputsOutputs(inputs, outputMap);
        }

        outputBuf.rewind();
        float[] embedding = new float[EMBEDDING_DIM];
        for (int i = 0; i < EMBEDDING_DIM; i++) embedding[i] = outputBuf.getFloat();

        return normalize(embedding);
    }

    public float[] embedQuery(String query) {
        return embed(query);
    }

    // =========================
    // SIMILARITY
    // =========================

    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null) return 0f;
        if (a.length != b.length) return 0f;

        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        float denom = (float)(Math.sqrt(normA) * Math.sqrt(normB));
        return denom == 0f ? 0f : dot / denom;
    }

    // =========================
    // VECTOR UTILS
    // =========================

    public static byte[] toBytes(float[] embedding) {
        if (embedding == null) return null;
        ByteBuffer buf = ByteBuffer.allocate(embedding.length * 4);
        for (float v : embedding) buf.putFloat(v);
        return buf.array();
    }

    public static float[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        float[] embedding = new float[bytes.length / 4];
        for (int i = 0; i < embedding.length; i++) embedding[i] = buf.getFloat();
        return embedding;
    }

    public static float[] centroid(float[][] vectors) {
        if (vectors == null || vectors.length == 0) return null;
        int dim = vectors[0].length;
        float[] result = new float[dim];
        for (float[] v : vectors)
            for (int i = 0; i < dim; i++) result[i] += v[i];
        for (int i = 0; i < dim; i++) result[i] /= vectors.length;
        return normalize(result);
    }

    // =========================
    // INTERNAL
    // =========================

    private ByteBuffer intArrayToBuffer(int[] arr) {
        ByteBuffer buf = ByteBuffer.allocateDirect(arr.length * 4)
                .order(ByteOrder.nativeOrder());
        for (int v : arr) buf.putInt(v);
        buf.rewind();
        return buf;
    }

    private int[] tokenize(String text) {
        int[] ids = new int[MAX_SEQ_LENGTH];
        ids[0] = 101; // [CLS]

        String[] words = text.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "")
                .trim()
                .split("\\s+");

        int pos = 1;
        for (String word : words) {
            if (pos >= MAX_SEQ_LENGTH - 1) break;
            if (word.isEmpty()) continue;
            ids[pos++] = wordToId(word);
        }

        if (pos < MAX_SEQ_LENGTH) ids[pos] = 102; // [SEP]
        return ids;
    }

    private int wordToId(String word) {
        return Math.abs(word.hashCode()) % (30521 - 103) + 103;
    }

    private int[] buildAttentionMask(int[] inputIds) {
        int[] mask = new int[MAX_SEQ_LENGTH];
        for (int i = 0; i < MAX_SEQ_LENGTH; i++) mask[i] = inputIds[i] != 0 ? 1 : 0;
        return mask;
    }

    private static float[] normalize(float[] vector) {
        float norm = 0f;
        for (float v : vector) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm == 0f) return vector;
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) out[i] = vector[i] / norm;
        return out;
    }

    // =========================
    // CLEANUP
    // =========================

    public void close() {
        if (interpreter != null) interpreter.close();
    }

    public int getEmbeddingDim() { return EMBEDDING_DIM; }
    public int getInputCount()   { return inputCount; }
}
