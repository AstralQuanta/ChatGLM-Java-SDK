package top.pulselink.chatglm;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static top.pulselink.chatglm.ConstantValue.*;

public class SSEInvokeModel {

    private BlockingQueue<Character> charQueue = new LinkedBlockingQueue<>();
    private volatile String getMessage = "";
    private volatile StringBuilder queueResult = new StringBuilder();

    public synchronized CompletableFuture<String> SSERequest(String token, String input, String url) {
        return SSEInvokeRequestMethod(token, input, url)
                .thenApply(this::responseDataBody)
                .exceptionally(ex -> "HTTP request failed with status code: " + ex.getMessage());
    }

    private CompletableFuture<String> SSEInvokeRequestMethod(String token, String message, String apiUrl) {
        String jsonRequestBody = String.format("{\"model\":\"%s\", \"messages\":[{\"role\":\"%s\",\"content\":\"%s\"},{\"role\":\"%s\",\"content\":\"%s\"}], \"stream\":true,\"temperture\":%f,\"top_p\":%f}",
                Language_Model, system_role, system_content, user_role, message, temp_float, top_p_float);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequestBody))
                .build();

        return HttpClient.newHttpClient()
                .sendAsync(request, HttpResponse.BodyHandlers.fromLineSubscriber(new SSESubscriber()))
                .thenApply(response -> null);
    }

    private synchronized String responseDataBody(String responseData) {
        try (JsonReader jsonReader = new JsonReader(new StringReader(responseData))) {
            jsonReader.setLenient(true);
            JsonElement jsonElement = JsonParser.parseReader(jsonReader);

            if (jsonElement.isJsonObject()) {
                JsonObject jsonResponse = jsonElement.getAsJsonObject();

                if (jsonResponse.has("choices")) {
                    JsonArray choices = jsonResponse.getAsJsonArray("choices");

                    if (!choices.isEmpty()) {
                        JsonObject choice = choices.get(0).getAsJsonObject();

                        if (choice.has("delta")) {
                            JsonObject delta = choice.getAsJsonObject("delta");

                            if (delta.has("content")) {
                                String content = delta.get("content").getAsString();
                                getMessage = convertUnicodeEmojis(content);
                                getMessage = getMessage.replaceAll("\"", "")
                                        .replaceAll("\\\\n\\\\n", "\n")
                                        .replaceAll("\\\\nn", "\n")
                                        .replaceAll("\\n", "\n")
                                        .replaceAll("\\\\", "")
                                        .replaceAll("\\\\", "");

                                for (char c : getMessage.toCharArray()) {
                                    charQueue.offer(c);
                                }

                                while (!charQueue.isEmpty()) {
                                    queueResult.append(charQueue.poll());
                                }
                            }
                        }
                    }
                }
            } else {
                System.out.println("Invalid JSON format: " + jsonElement);
            }
        } catch (IOException e) {
            System.out.println("Error reading JSON: " + e.getMessage());
        }

        return queueResult.toString();
    }

    private String convertUnicodeEmojis(String input) {
        String regex = "\\\\u[0-9a-fA-F]{4}";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(input);

        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String emoji = Character.toString((char) Integer.parseInt(matcher.group().substring(2), 16));
            matcher.appendReplacement(result, emoji);
        }
        matcher.appendTail(result);
        getMessage = result.toString(); // Update getMessage here
        return getMessage;
    }

    public synchronized String getContentMessage() {
        return queueResult.toString();
    }

    public class SSESubscriber implements Flow.Subscriber<String> {

        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(String item) {
            if (item.startsWith("data: ")) {
                String jsonData = item.substring("data: ".length());
                //System.out.println("Received SSE item: " + jsonData); //Debug

                if (!jsonData.equals("[DONE]")) {
                    responseDataBody(jsonData.replaceAll("Invalid JSON format: \\[\"DONE\"\\]", ""));
                }
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            System.out.println("Error in SSESubscriber: " + throwable.getMessage());
        }

        @Override
        public void onComplete() {
            //System.out.println("SSESubscriber completed");
        }
    }

}
