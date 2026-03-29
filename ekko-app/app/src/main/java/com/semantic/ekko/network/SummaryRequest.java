package com.semantic.ekko.network;

import com.google.gson.annotations.SerializedName;

public class SummaryRequest {

    @SerializedName("context")
    public final String context;

    @SerializedName("document_name")
    public final String documentName;

    public SummaryRequest(String context, String documentName) {
        this.context = context != null ? context : "";
        this.documentName = documentName != null ? documentName : "";
    }
}
