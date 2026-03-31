package com.semantic.ekko.network;

import com.semantic.ekko.BuildConfig;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RagClient {

    private static volatile RagApiService instance;
    private static volatile OkHttpClient streamingClient;

    // Standard client for non-streaming calls
    private static OkHttpClient buildStandardClient() {
        return new OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
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
        return BuildConfig.RAG_BASE_URL;
    }

    public static RagApiService getInstance() {
        if (instance == null) {
            synchronized (RagClient.class) {
                if (instance == null) {
                    Retrofit retrofit = new Retrofit.Builder()
                        .baseUrl(BuildConfig.RAG_BASE_URL)
                        .client(buildStandardClient())
                        .addConverterFactory(GsonConverterFactory.create())
                        .build();
                    instance = retrofit.create(RagApiService.class);
                }
            }
        }
        return instance;
    }
}
