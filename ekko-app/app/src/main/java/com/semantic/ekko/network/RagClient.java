package com.semantic.ekko.network;

import com.semantic.ekko.BuildConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RagClient {

    private static final ConcurrentHashMap<String, RagApiService> SERVICES =
        new ConcurrentHashMap<>();
    private static volatile OkHttpClient streamingClient;
    private static volatile String activeBaseUrl = normalizeBaseUrl(
        BuildConfig.RAG_BASE_URL
    );

    // Standard client for non-streaming calls
    private static OkHttpClient buildStandardClient() {
        return new OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build();
    }

    // Streaming client with no read timeout so the connection stays open
    // for the full duration of Ollama generation
    public static OkHttpClient getStreamingClient() {
        if (streamingClient == null) {
            synchronized (RagClient.class) {
                if (streamingClient == null) {
                    streamingClient = new OkHttpClient.Builder()
                        .retryOnConnectionFailure(true)
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(0, TimeUnit.SECONDS) // no timeout for streaming
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .callTimeout(180, TimeUnit.SECONDS)
                        .build();
                }
            }
        }
        return streamingClient;
    }

    public static String getBaseUrl() {
        return activeBaseUrl;
    }

    public static List<String> getCandidateBaseUrls() {
        Set<String> urls = new LinkedHashSet<>();
        urls.add(normalizeBaseUrl(BuildConfig.RAG_BASE_URL));
        urls.add("http://127.0.0.1:8000/");
        urls.add("http://10.0.2.2:8000/");
        urls.add("http://localhost:8000/");
        return new ArrayList<>(urls);
    }

    public static void setActiveBaseUrl(String baseUrl) {
        activeBaseUrl = normalizeBaseUrl(baseUrl);
    }

    public static RagApiService getService(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        return SERVICES.computeIfAbsent(normalized, url -> {
            Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(url)
                .client(buildStandardClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build();
            return retrofit.create(RagApiService.class);
        });
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String safe = baseUrl == null ? "" : baseUrl.trim();
        if (safe.isEmpty()) {
            safe = "http://127.0.0.1:8000/";
        }
        if (!safe.endsWith("/")) {
            safe = safe + "/";
        }
        return safe;
    }

    public static RagApiService getInstance() {
        return getService(activeBaseUrl);
    }
}
