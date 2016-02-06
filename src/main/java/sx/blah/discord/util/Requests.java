/*
 * Discord4J - Unofficial wrapper for Discord API
 * Copyright (c) 2015
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package sx.blah.discord.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import sx.blah.discord.Discord4J;
import sx.blah.discord.api.DiscordException;
import sx.blah.discord.api.internal.DiscordUtils;
import sx.blah.discord.json.responses.RateLimitResponse;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import static sx.blah.discord.Discord4J.*;

/**
 * Represents request types to be sent.
 */
public enum Requests {

    /**
     * Used to send POST Requests
     */
    POST(HttpPost.class),
    /**
     * Used to send GET requests
     */
    GET(HttpGet.class),
    /**
     * Used to send DELETE requests
     */
    DELETE(HttpDelete.class),
    /**
     * Used to send PATCH requests
     */
    PATCH(HttpPatch.class),
    /**
     * Used to send PUT requests
     */
    PUT(HttpPut.class);
    /**
     * The user-agent, as per @Jake's request
     */
    private static final String userAgent = String.format("DiscordBot (%s v%s) - %s %s", URL, VERSION, NAME, DESCRIPTION);

    //Same as HttpClients.createDefault() but with the proper user-agent
    static final HttpClient CLIENT = HttpClients.custom().setUserAgent(userAgent).build();

    final Class<? extends HttpUriRequest> requestClass;

    Requests(Class<? extends HttpUriRequest> clazz) {
        this.requestClass = clazz;
    }

    /**
     * Gets the HttpREQUEST.class represented by the enum.
     *
     * @return The Http request class.
     */
    public Class<? extends HttpUriRequest> getRequestClass() {
        return requestClass;
    }

    /**
     * Makes a request.
     *
     * @param url     The url to make the request to.
     * @param headers The headers to include in the request.
     * @return The result (if any) returned by the request.
     * @throws HTTP429Exception
     * @throws DiscordException
     */
    public String makeRequest(String url, BasicNameValuePair... headers) throws HTTP429Exception, DiscordException {
        try {
            HttpUriRequest request = this.requestClass.getConstructor(String.class).newInstance(url);
            for (BasicNameValuePair header : headers) {
                request.addHeader(header.getName(), header.getValue());
            }
            HttpResponse response = CLIENT.execute(request);
            int responseCode = response.getStatusLine().getStatusCode();

            if (responseCode == 404) {
                LOGGER.error("Received 404 error, please notify the developer and include the URL ({})", url);
            } else if (responseCode == 403) {
                LOGGER.error("Received 403 forbidden error for url {}. If you believe this is a Discord4J error, report this!", url);
            } else if (responseCode == 204) { //There is a no content response when deleting messages
                return null;
            }

            String message = EntityUtils.toString(response.getEntity());

            JsonParser parser = new JsonParser();
            JsonElement element = parser.parse(message);

            if (responseCode == 429) {
                throw new HTTP429Exception(DiscordUtils.GSON.fromJson(element, RateLimitResponse.class));
            }

            if (element.isJsonObject() && parser.parse(message).getAsJsonObject().has("message"))
                throw new DiscordException(element.getAsJsonObject().get("message").getAsString());

            return message;
        } catch (IOException | NoSuchMethodException | IllegalAccessException | InvocationTargetException | InstantiationException e) {
            Discord4J.LOGGER.error("Discord4J Internal Exception", e);
            return null;
        }
    }

    /**
     * Makes a request.
     *
     * @param entity  Any data to send with the request.
     * @param url     The url to make the request to.
     * @param headers The headers to include in the request.
     * @return The result (if any) returned by the request.
     * @throws HTTP429Exception
     * @throws DiscordException
     */
    public String makeRequest(String url, HttpEntity entity, BasicNameValuePair... headers) throws HTTP429Exception, DiscordException {
        try {
            if (HttpEntityEnclosingRequestBase.class.isAssignableFrom(this.requestClass)) {
                HttpEntityEnclosingRequestBase request = (HttpEntityEnclosingRequestBase)
                    this.requestClass.getConstructor(String.class).newInstance(url);
                for (BasicNameValuePair header : headers) {
                    request.addHeader(header.getName(), header.getValue());
                }
                request.setEntity(entity);
                HttpResponse response = CLIENT.execute(request);
                int responseCode = response.getStatusLine().getStatusCode();

                if (responseCode == 404) {
                    LOGGER.error("Received 404 error, please notify the developer and include the URL ({})", url);
                } else if (responseCode == 403) {
                    LOGGER.error("Received 403 forbidden error for url {}. If you believe this is a Discord4J error, report this!", url);
                } else if (responseCode == 204) { //There is a no content response when deleting messages
                    return null;
                }

                String message = EntityUtils.toString(response.getEntity());

                JsonParser parser = new JsonParser();
                JsonElement element = parser.parse(message);

                if (responseCode == 429) {
                    throw new HTTP429Exception(DiscordUtils.GSON.fromJson(element, RateLimitResponse.class));
                }

                if (element.isJsonObject() && parser.parse(message).getAsJsonObject().has("message"))
                    throw new DiscordException(element.getAsJsonObject().get("message").getAsString());

                return message;
            } else {
                LOGGER.error("Tried to attach HTTP entity to invalid type! ({})",
                    this.requestClass.getSimpleName());
            }
        } catch (IOException | NoSuchMethodException | IllegalAccessException | InvocationTargetException | InstantiationException e) {
            Discord4J.LOGGER.error("Discord4J Internal Exception", e);
        }
        return null;
    }
}
