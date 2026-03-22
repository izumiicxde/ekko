package com.semantic.ekko.ui.qa;

public class QAMessage {

    public static final int TYPE_USER   = 0;
    public static final int TYPE_ANSWER = 1;
    public static final int TYPE_ERROR  = 2;

    public final int    type;
    public final String text;
    public final String sourceDocumentName;

    public QAMessage(int type, String text, String sourceDocumentName) {
        this.type               = type;
        this.text               = text;
        this.sourceDocumentName = sourceDocumentName;
    }
}
