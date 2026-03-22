package com.semantic.ekko.network;

import com.google.gson.annotations.SerializedName;

public class RagResponse {

    @SerializedName("answer")
    public String answer;

    @SerializedName("chunks_used")
    public int chunksUsed;
}
