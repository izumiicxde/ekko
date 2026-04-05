package com.semantic.ekko.network;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class RagRequest {

    @SerializedName("question")
    public final String question;

    @SerializedName("chunks")
    public final List<String> chunks;

    @SerializedName("document_name")
    public final String documentName;

    @SerializedName("allow_general_knowledge")
    public final boolean allowGeneralKnowledge;

    public RagRequest(
        String question,
        List<String> chunks,
        String documentName,
        boolean allowGeneralKnowledge
    ) {
        this.question = question;
        this.chunks = chunks;
        this.documentName = documentName != null ? documentName : "";
        this.allowGeneralKnowledge = allowGeneralKnowledge;
    }
}
