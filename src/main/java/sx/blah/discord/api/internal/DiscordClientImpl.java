package sx.blah.discord.api.internal;

import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicNameValuePair;
import sx.blah.discord.Discord4J;
import sx.blah.discord.api.DiscordEndpoints;
import sx.blah.discord.api.DiscordException;
import sx.blah.discord.api.IDiscordClient;
import sx.blah.discord.handle.EventDispatcher;
import sx.blah.discord.handle.impl.obj.User;
import sx.blah.discord.handle.obj.*;
import sx.blah.discord.json.requests.*;
import sx.blah.discord.json.responses.*;
import sx.blah.discord.util.HTTP429Exception;
import sx.blah.discord.util.Requests;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Defines the client.
 * This class receives and
 * sends messages, as well
 * as holds our user data.
 */
public final class DiscordClientImpl implements IDiscordClient {
    /**
     * Used for keep alive. Keeps last time (in ms)
     * that we sent the keep alive so we can accurately
     * time our keep alive messages.
     */
    protected volatile long timer = System.currentTimeMillis();

    /**
     * User we are logged in as
     */
    protected User ourUser;

    /**
     * Our token, so we can send XHR to Discord.
     */
    protected String token;

    /**
     * Time (in ms) between keep alive
     * messages.
     */
    protected volatile long heartbeat;

    /**
     * Local copy of all guilds/servers.
     */
    protected final List<IGuild> guildList = Collections.synchronizedList(new ArrayList<>());

    /**
     * Private copy of the email you logged in with.
     */
    protected String email;

    /**
     * Private copy of the password you used to log in.
     */
    protected String password;

    /**
     * WebSocket over which to communicate with Discord.
     */
    protected DiscordWS ws;

    /**
     * Event dispatcher.
     */
    protected EventDispatcher dispatcher;

    /**
     * All of the private message channels that the bot is connected to.
     */
    protected final List<IPrivateChannel> privateChannels = Collections.synchronizedList(new ArrayList<>());

    /**
     * Whether the api is logged in.
     */
    protected boolean isReady = false;

    /**
     * The websocket session id.
     */
    protected String sessionId;

    /**
     * Caches the last operation done by the websocket, required for handling redirects.
     */
    protected long lastSequence = 0;

    /**
     * Caches the available regions for discord.
     */
    protected final List<IRegion> REGIONS = new ArrayList<>();

    public DiscordClientImpl(String email, String password) {
        this.dispatcher = new EventDispatcher(this);
        this.email = email;
        this.password = password;
    }

    @Override
    public EventDispatcher getDispatcher() {
        return dispatcher;
    }

    @Override
    public String getToken() {
        return token;
    }

    @Override
    public void login() throws DiscordException {
        try {
            if (null != ws) {
                ws.close();
            }

            LoginResponse response = DiscordUtils.GSON.fromJson(Requests.POST.makeRequest(DiscordEndpoints.LOGIN,
                new StringEntity(DiscordUtils.GSON.toJson(new LoginRequest(email, password))),
                new BasicNameValuePair("content-type", "application/json")), LoginResponse.class);
            this.token = response.token;

            this.ws = new DiscordWS(this, new URI(obtainGateway(this.token)));
        } catch (Exception e) {
            throw new DiscordException("Login error occurred! Are your login details correct?");
        }
    }

    @Override
    public void logout() throws HTTP429Exception {
        if (isReady()) {
            ws.disconnect();

            Requests.POST.makeRequest(DiscordEndpoints.LOGOUT,
                new BasicNameValuePair("authorization", token));
        } else
            Discord4J.LOGGER.error("Bot has not signed in yet!");
    }

    /**
     * Gets the WebSocket gateway
     *
     * @param token Our login token
     * @return the WebSocket URL of which to connect
     */
    private String obtainGateway(String token) {
        String gateway = null;
        try {
            GatewayResponse response = DiscordUtils.GSON.fromJson(Requests.GET.makeRequest("https://discordapp.com/api/gateway",
                new BasicNameValuePair("authorization", token)), GatewayResponse.class);
            gateway = response.url.replaceAll("wss", "ws");
        } catch (HTTP429Exception e) {
            e.printStackTrace();
        }
        Discord4J.LOGGER.debug("Obtained gateway {}.", gateway);
        return gateway;
    }

    @Override
    public void changeAccountInfo(Optional<String> username, Optional<String> email, Optional<String> password, Optional<Image> avatar) throws HTTP429Exception {
        Discord4J.LOGGER.debug("Changing account info.");

        if (!isReady()) {
            Discord4J.LOGGER.error("Bot has not signed in yet!");
            return;
        }

        try {
            AccountInfoChangeResponse response = DiscordUtils.GSON.fromJson(Requests.PATCH.makeRequest(DiscordEndpoints.USERS + "@me",
                new StringEntity(DiscordUtils.GSON.toJson(new AccountInfoChangeRequest(email.orElse(this.email),
                    this.password, password.orElse(this.password), username.orElse(getOurUser().getName()),
                    avatar.isPresent() ? avatar.get().getData() : Image.defaultAvatar().getData()))),
                new BasicNameValuePair("Authorization", token),
                new BasicNameValuePair("content-type", "application/json; charset=UTF-8")), AccountInfoChangeResponse.class);

            if (!this.token.equals(response.token)) {
                Discord4J.LOGGER.debug("Token changed, updating it.");
                this.token = response.token;
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void updatePresence(boolean isIdle, Optional<String> game) {
        if (!isReady()) {
            Discord4J.LOGGER.error("Bot has not signed in yet!");
            return;
        }

        ws.send(DiscordUtils.GSON.toJson(new PresenceUpdateRequest(isIdle ? System.currentTimeMillis() : null, game.orElse(null))));

        ((User) getOurUser()).setPresence(isIdle ? Presences.IDLE : Presences.ONLINE);
        ((User) getOurUser()).setGame(game);
    }

    @Override
    public boolean isReady() {
        return isReady && ws != null;
    }

    @Override
    public IUser getOurUser() {
        if (!isReady()) {
            Discord4J.LOGGER.error("Bot has not signed in yet!");
            return null;
        }
        return ourUser;
    }

    @Override
    public IChannel getChannelByID(String id) {
        for (IGuild guild : guildList) {
            for (IChannel channel : guild.getChannels()) {
                if (channel.getID().equalsIgnoreCase(id))
                    return channel;
            }
        }

        for (IPrivateChannel channel : privateChannels) {
            if (channel.getID().equalsIgnoreCase(id))
                return channel;
        }

        return null;
    }

    @Override
    public IVoiceChannel getVoiceChannelByID(String id) {
        for (IGuild guild : guildList) {
            for (IVoiceChannel channel : guild.getVoiceChannels()) {
                if (channel.getID().equals(id))
                    return channel;
            }
        }

        return null;
    }

    @Override
    public IGuild getGuildByID(String guildID) {
        for (IGuild guild : guildList) {
            if (guild.getID().equalsIgnoreCase(guildID))
                return guild;
        }

        return null;
    }

    @Override
    public List<IGuild> getGuilds() {
        return guildList;
    }

    @Override
    public IUser getUserByID(String userID) {
        IUser user = null;
        for (IGuild guild : guildList) {
            if (null == user) {
                user = guild.getUserByID(userID);
            }
        }

        return ourUser != null && ourUser.getID().equals(userID) ? ourUser : user;
    }

    @Override
    public IPrivateChannel getOrCreatePMChannel(IUser user) throws Exception {
        if (!isReady()) {
            Discord4J.LOGGER.error("Bot has not signed in yet!");
            return null;
        }

        for (IPrivateChannel channel : privateChannels) {
            if (channel.getRecipient().getID().equalsIgnoreCase(user.getID())) {
                return channel;
            }
        }

        PrivateChannelResponse response = DiscordUtils.GSON.fromJson(Requests.POST.makeRequest(DiscordEndpoints.USERS + this.ourUser.getID() + "/channels",
            new StringEntity(DiscordUtils.GSON.toJson(new PrivateChannelRequest(user.getID()))),
            new BasicNameValuePair("authorization", this.token),
            new BasicNameValuePair("content-type", "application/json")), PrivateChannelResponse.class);

        IPrivateChannel channel = DiscordUtils.getPrivateChannelFromJSON(this, response);
        privateChannels.add(channel);
        return channel;
    }

    @Override
    public IInvite getInviteForCode(String code) {
        if (!isReady()) {
            Discord4J.LOGGER.error("Bot has not signed in yet!");
            return null;
        }

        try {
            InviteJSONResponse response = DiscordUtils.GSON.fromJson(Requests.GET.makeRequest(DiscordEndpoints.INVITE + code,
                new BasicNameValuePair("authorization", token)), InviteJSONResponse.class);

            return DiscordUtils.getInviteFromJSON(this, response);
        } catch (HTTP429Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public List<IRegion> getRegions() throws HTTP429Exception {
        if (REGIONS.isEmpty()) {
            RegionResponse[] regions = DiscordUtils.GSON.fromJson(Requests.GET.makeRequest(
                DiscordEndpoints.VOICE + "regions",
                new BasicNameValuePair("authorization", this.token)),
                RegionResponse[].class);

            for (RegionResponse regionResponse : regions) {
                REGIONS.add(DiscordUtils.getRegionFromJSON(this, regionResponse));
            }
        }

        return REGIONS;
    }

    @Override
    public IRegion getRegionForID(String regionID) {
        try {
            for (IRegion region : getRegions()) {
                if (region.getID().equals(regionID))
                    return region;
            }
        } catch (HTTP429Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public IGuild createGuild(String name, Optional<String> regionID, Optional<Image> icon) throws HTTP429Exception {
        try {
            GuildResponse guildResponse = DiscordUtils.GSON.fromJson(Requests.POST.makeRequest(DiscordEndpoints.APIBASE + "/guilds",
                new StringEntity(DiscordUtils.GSON_NO_NULLS.toJson(
                    new CreateGuildRequest(name, regionID.orElse(null), icon.orElse(null)))),
                new BasicNameValuePair("authorization", this.token),
                new BasicNameValuePair("content-type", "application/json")), GuildResponse.class);
            IGuild guild = DiscordUtils.getGuildFromJSON(this, guildResponse);
            guildList.add(guild);
            return guild;
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return null;
    }
}
