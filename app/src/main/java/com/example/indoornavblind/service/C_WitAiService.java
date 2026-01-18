package com.example.indoornavblind.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.*;
import okio.BufferedSink;

public class C_WitAiService {
    private static final String TAG = "WitAiService";
    private static final String WIT_API_URL = "https://api.wit.ai/message?v=20251119&q=";
    private static final String WIT_API_KEY = "Bearer XXO7YTXPVO2JGNGIRRC56KZUSDHGJ3Q4";

    private final OkHttpClient client = new OkHttpClient();

    public interface WitCallback {
        void onSuccess(String intent, Map<String, String> entities);
        void onError(String error);
    }

    public void recognizeVoice(InputStream audioStream, WitCallback callback) {
        try {
            RequestBody body = new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.parse("audio/wav");
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = audioStream.read(buffer)) != -1) {
                        sink.write(buffer, 0, bytesRead);
                    }
                }
            };

            Request request = new Request.Builder()
                    .url(WIT_API_URL)
                    .addHeader("Authorization", WIT_API_KEY)
                    .addHeader("Content-Type", "audio/wav")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callback.onError(e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        callback.onError("HTTP Error: " + response.code());
                        return;
                    }

                    String json = response.body().string();
                    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                    String intent = root.has("intents") && root.getAsJsonArray("intents").size() > 0
                            ? root.getAsJsonArray("intents").get(0).getAsJsonObject().get("name").getAsString()
                            : "unknown";

                    Map<String, String> entities = new HashMap<>();
                    if (root.has("entities")) {
                        JsonObject entityObj = root.getAsJsonObject("entities");
                        for (String key : entityObj.keySet()) {
                            JsonObject ent = entityObj.getAsJsonArray(key).get(0).getAsJsonObject();
                            entities.put(key, ent.get("value").getAsString());
                        }
                    }

                    callback.onSuccess(intent, entities);
                }
            });
        } catch (Exception e) {
            callback.onError("Exception: " + e.getMessage());
        }
    }
}
