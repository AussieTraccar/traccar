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
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.MessageException;
import org.traccar.notification.NotificationFormatter;
import org.traccar.notification.NotificationMessage;

import java.nio.charset.StandardCharsets;

@Singleton
public class NotificatorGotify extends Notificator {

    private final Client client;

    private String url;
    private String token;
    private final Integer priority;

    public static class JsonPayload {
        @JsonProperty("title")
        private String title;
        @JsonProperty("message")
        private String message;
        @JsonProperty("priority")
        private Integer priority;
    }

    @Inject
    public NotificatorGotify(Config config, NotificationFormatter notificationFormatter, Client client) {
        super(notificationFormatter);
        this.client = client;
        url = config.getString(Keys.NOTIFICATOR_GOTIFY_URL);
        token = config.getString(Keys.NOTIFICATOR_GOTIFY_TOKEN);
        priority = config.getInteger(Keys.NOTIFICATOR_GOTIFY_PRIORITY);
    }

    private Invocation.Builder getRequestBuilder() {
        return client.target(url + "/message?token=" + token).request();
    }

    @Override
    public void send(User user, NotificationMessage message, Event event, Position position) throws MessageException {

        JsonPayload json = new JsonPayload();

        if (user.hasAttribute("gotifyUrl")) {
            url = user.getString("gotifyUrl");
        }
        if (user.hasAttribute("gotifyToken")) {
            token = user.getString("gotifyToken");
        }
        if (user.hasAttribute("gotifyPriority")) {
            json.priority = user.getInteger("gotifyPriority");
        } else {
            json.priority = this.priority;
        }

        json.title = message.subject();
        json.message = message.digest();

        if ((url != null && !url.isEmpty()) && (token != null && !token.isEmpty())) {
        try (Response response = getRequestBuilder().post(
                Entity.entity(json, MediaType.APPLICATION_JSON_TYPE.withCharset(StandardCharsets.UTF_8.name())))) {
            if (response.getStatus() / 100 != 2) {
                throw new MessageException(response.readEntity(String.class));
            }
        }
        } else {
            throw new MessageException("Gotify service URL or token not defined");
        }

    }

}
