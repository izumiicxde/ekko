package com.semantic.ekko.processing.extractor;

import android.content.Context;
import android.net.Uri;
import com.semantic.ekko.util.FileUtils;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import java.io.IOException;
import java.io.InputStream;

public class PptxTextExtractor {

    /**
     * Extracts plain text from a PPTX file at the given URI.
     * Iterates over all slides and all text shapes per slide.
     * Returns empty string on failure.
     */
    public static String extract(Context context, Uri uri) {
        try (InputStream is = FileUtils.openInputStream(context, uri)) {
            if (is == null) return "";

            try (XMLSlideShow ppt = new XMLSlideShow(is)) {
                StringBuilder sb = new StringBuilder();

                for (XSLFSlide slide : ppt.getSlides()) {
                    for (XSLFShape shape : slide.getShapes()) {
                        if (shape instanceof XSLFTextShape) {
                            String text = ((XSLFTextShape) shape).getText();
                            if (text != null && !text.trim().isEmpty()) {
                                sb.append(text.trim()).append("\n");
                            }
                        }
                    }
                    sb.append("\n");
                }

                return sb.toString();
            }

        } catch (IOException e) {
            return "";
        }
    }
}
