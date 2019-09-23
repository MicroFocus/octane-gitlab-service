package com.microfocus.octane.gitlab.helpers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class StreamHelper {

    private StreamHelper() {
    }

    public static void copyStream(InputStream is, OutputStream os) throws IOException {
        if (is == null) {
            return;
        }

        byte[] buf = new byte[4096];
        int n;

        while ((n = is.read(buf, 0, 4096)) > 0) {
            os.write(buf, 0, n);
        }

        os.flush();
    }

}
