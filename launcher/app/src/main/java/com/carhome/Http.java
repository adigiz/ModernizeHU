package com.carhome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** The launcher's one HTTP client: TLS 1.3 through Conscrypt, and an identifying User-Agent. */
final class Http {

    private static final String USER_AGENT = "carhome/1.0 (personal car head unit launcher)";
    private static OkHttpClient client;

    private Http() {
    }

    private static synchronized OkHttpClient client() {
        if (client == null) {
            Tls.install();
            client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
        }
        return client;
    }

    /**
     * The body of a 200 answer; null for 404 and 204 ("nothing here"); any other answer throws, to be
     * retried later. headers: name, value, name, value, ...
     */
    static byte[] get(String url, String... headers) throws IOException {
        Request.Builder request = new Request.Builder().url(url).header("User-Agent", USER_AGENT);
        for (int i = 0; i + 1 < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        try (Response response = client().newCall(request.build()).execute()) {
            int code = response.code();
            if (code == 404 || code == 204) return null;
            if (code != 200) throw new IOException("HTTP " + code + " from " + response.request().url().host());
            ResponseBody body = response.body();
            return body != null ? body.bytes() : new byte[0];
        }
    }

    /** Like {@link #get}, but "nothing here" is an error too: for APIs that always answer. */
    static String getText(String url, String... headers) throws IOException {
        byte[] body = get(url, headers);
        if (body == null) throw new IOException("no answer from " + url);
        return new String(body, StandardCharsets.UTF_8);
    }
}
