package com.microfocus.octane.gitlab.helpers;

import org.mozilla.universalchardet.UniversalDetector;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class EncodingHelper {

    public static String detectCharset(InputStream is) throws IOException {
        return UniversalDetector.detectCharset(is);
    }

    public static String detectCharset(File file) throws IOException {
        return UniversalDetector.detectCharset(file);
    }
}
