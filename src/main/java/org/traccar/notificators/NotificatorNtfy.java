/*
 * Copyright 2020 - 2024 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.notificators;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.DataConverter;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.MessageException;
import org.traccar.notification.NotificationFormatter;
import org.traccar.notification.NotificationMessage;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

@Singleton
public class NotificatorNtfy extends Notificator {

    private final Client client;

    private String url;
    private String authorization;
    private final String topic;
    private final Integer priority;
    private final String tags;

    public static class JsonPayload {
        @JsonProperty("topic")
        private String topic;
        @JsonProperty("priority")
        private Integer priority;
        @JsonProperty("tags")
        private String tags;
        @JsonProperty("title")
        private String title;
        @JsonProperty("message")
        private String message;
    }

    @Inject
    public NotificatorNtfy(Config config, NotificationFormatter notificationFormatter, Client client) {
        super(notificationFormatter);
        this.client = client;
        url = config.getString(Keys.NOTIFICATOR_NTFY_URL);
        if (config.hasKey(Keys.NOTIFICATOR_NTFY_TOKEN)) {
            authorization = "Bearer "
                        + config.getString(Keys.NOTIFICATOR_NTFY_TOKEN);
        } else {
            String user = config.getString(Keys.NOTIFICATOR_NTFY_USER);
            String password = config.getString(Keys.NOTIFICATOR_NTFY_PASSWORD);
            if (user != null && password != null) {
                authorization = "Basic "
                        + DataConverter.printBase64((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            } else {
                authorization = null;
            }
        }
        topic = config.getString(Keys.NOTIFICATOR_NTFY_TOPIC);
        priority = config.getInteger(Keys.NOTIFICATOR_NTFY_PRIORITY);
        tags = config.getString(Keys.NOTIFICATOR_NTFY_TAGS);
    }

    private String prepareValue(String value) throws UnsupportedEncodingException {
        return new String(JsonStringEncoder.getInstance().quoteAsString(value));
    }

    private String preparePayload(String topic, String title, String message, Integer priority, String tags) {
        try {
            return "{\"topic\": \"{topic}\",\"title\": \"{title}\",\"message\": \"{message}\",\"priority\": {priority},\"tags\": [{tags}]}"
                    .replace("{topic}", prepareValue(topic))
                    .replace("{title}", prepareValue(title))
                    .replace("{message}", prepareValue(message))
                    .replace("{priority}", prepareValue(String.valueOf(priority)))
                    .replace("{tags}", (tags != null && !tags.isEmpty()  ? "\"" + prepareValue(tags) + "\"" : ""));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private Invocation.Builder getRequestBuilder() {
        Invocation.Builder builder = client.target(url).request();
        if (authorization != null) {
            builder = builder.header("Authorization", authorization);
        }
        return builder;
    }

    @Override
    public void send(User user, NotificationMessage message, Event event, Position position) throws MessageException {

        JsonPayload json = new JsonPayload();

        if (user.hasAttribute("ntfyUrl")) {
            url = user.getString("ntfyUrl");
        }
        if (user.hasAttribute("ntfyToken")) {
            authorization = "Bearer "
                    + user.getString("ntfyToken");
        }
        if (user.hasAttribute("ntfyMessageTopic")) {
            json.topic = user.getString("ntfyMessageTopic");
        } else {
            json.topic = this.topic;
        }
        if (user.hasAttribute("ntfyMessagePriority")) {
            json.priority = user.getInteger("ntfyMessagePriority");
        } else {
            json.priority = this.priority;
        }
        if (user.hasAttribute("ntfyMessageTags")) {
            json.tags = user.getString("ntfyMessageTags");
        } else {
            json.tags = this.tags;
        }

        json.title = message.subject();
        json.message = message.digest();

        try (Response response = getRequestBuilder().post(
                Entity.entity(preparePayload(json.topic, json.title, json.message, json.priority, json.tags), MediaType.APPLICATION_JSON_TYPE.withCharset(StandardCharsets.UTF_8.name())))) {
            if (response.getStatus() / 100 != 2) {
                throw new MessageException(response.readEntity(String.class));
            }
        }

    }

}
