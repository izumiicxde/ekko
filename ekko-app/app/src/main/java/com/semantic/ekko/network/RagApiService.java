package com.semantic.ekko.network;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;

public interface RagApiService {
    @GET("health")
    Call<Void> health();

    @POST("rag")
    Call<RagResponse> ask(@Body RagRequest request);

    @POST("summary")
    Call<SummaryResponse> summarize(@Body SummaryRequest request);
}
