package com.semantic.ekko.network;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Singleton Retrofit client for the Ekko RAG backend.
 *
 * Base URL targets the FastAPI server running on the host machine.
 * Use 10.0.2.2:8000 for the Android emulator (maps to host localhost).
 * Replace with the host machine's LAN IP when running on a physical device,
 * for example http://192.168.1.x:8000/
 */
public class RagClient {

    public static final String BASE_URL = "http://10.0.2.2:8000/";

    private static volatile RagApiService instance;

    public static RagApiService getInstance() {
        if (instance == null) {
            synchronized (RagClient.class) {
                if (instance == null) {
                    Retrofit retrofit = new Retrofit.Builder()
                        .baseUrl(BASE_URL)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build();
                    instance = retrofit.create(RagApiService.class);
                }
            }
        }
        return instance;
    }
}
