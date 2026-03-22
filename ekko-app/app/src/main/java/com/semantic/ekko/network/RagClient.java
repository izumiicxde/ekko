package com.semantic.ekko.network;

import com.semantic.ekko.BuildConfig;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Singleton Retrofit client for the Ekko RAG backend.
 *
 * Base URL is configured via local.properties at build time:
 *   rag.base.url=http://192.168.x.x:8000/
 *
 * If not set, defaults to http://10.0.2.2:8000/ which works for the
 * Android emulator pointing to the host machine.
 *
 * For physical devices, set rag.base.url in local.properties to your
 * machine's LAN IP. See README for setup instructions.
 */
public class RagClient {

    private static volatile RagApiService instance;

    public static RagApiService getInstance() {
        if (instance == null) {
            synchronized (RagClient.class) {
                if (instance == null) {
                    Retrofit retrofit = new Retrofit.Builder()
                        .baseUrl(BuildConfig.RAG_BASE_URL)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build();
                    instance = retrofit.create(RagApiService.class);
                }
            }
        }
        return instance;
    }
}
