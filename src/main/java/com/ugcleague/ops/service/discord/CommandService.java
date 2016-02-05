package com.ugcleague.ops.service.discord;

import com.ugcleague.ops.config.LeagueProperties;
import com.ugcleague.ops.service.DiscordService;
import com.ugcleague.ops.service.discord.command.*;
import com.ugcleague.ops.service.discord.util.DiscordSubscriber;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.mina.util.ConcurrentHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sx.blah.discord.api.DiscordException;
import sx.blah.discord.api.MissingPermissionsException;
import sx.blah.discord.handle.EventSubscriber;
import sx.blah.discord.handle.impl.events.MessageReceivedEvent;
import sx.blah.discord.handle.obj.IChannel;
import sx.blah.discord.handle.obj.IMessage;
import sx.blah.discord.handle.obj.IUser;
import sx.blah.discord.util.HTTP429Exception;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.ugcleague.ops.util.Util.padLeft;
import static com.ugcleague.ops.util.Util.padRight;
import static java.util.Arrays.asList;

@Service
@Transactional
public class CommandService implements DiscordSubscriber {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);
    private static final int LENGTH_LIMIT = 2000;

    private final DiscordService discordService;
    private final LeagueProperties properties;
    private final Set<Command> commandList = new ConcurrentHashSet<>();
    private final Gatekeeper gatekeeper = new Gatekeeper();
    private final Map<String, IMessage> invokerToStatusMap = new ConcurrentHashMap<>();

    private OptionSpec<String> helpNonOptionSpec;
    private OptionSpec<Boolean> helpFullSpec;

    @Autowired
    public CommandService(DiscordService discordService, LeagueProperties properties) {
        this.discordService = discordService;
        this.properties = properties;
    }

    @PostConstruct
    private void configure() {
        OptionParser parser = new OptionParser();
        parser.acceptsAll(asList("?", "h", "help"), "display the help").forHelp();
        helpNonOptionSpec = parser.nonOptions("command to get help about").ofType(String.class);
        helpFullSpec = parser.acceptsAll(asList("f", "full"), "display all commands in a list with their description")
            .withOptionalArg().ofType(Boolean.class).defaultsTo(true);
        commandList.add(CommandBuilder.combined(".beep help").description("Show help about commands")
            .command(this::showCommandList).permission(0).parser(parser).build());
        discordService.subscribe(this);
    }

    // Help command definitions
    /////////////////////////////////

    private String showCommandList(IMessage m, OptionSet o) {
        List<String> nonOptions = o.valuesOf(helpNonOptionSpec);
        if (o.has("?")) {
            return null;
        } else if (nonOptions.isEmpty()) {
            if (o.has(helpFullSpec) && o.valueOf(helpFullSpec)) {
                return "*Commands available to you*\n" + commandList.stream()
                    .filter(c -> canExecute(m.getAuthor(), c))
                    .sorted(Comparator.naturalOrder())
                    .map(c -> padRight("**" + c.getKey() + "**", 20) + "\t\t" + c.getDescription())
                    .collect(Collectors.joining("\n"));
            } else {
                return "*Commands available to you:* " + commandList.stream()
                    .filter(c -> canExecute(m.getAuthor(), c))
                    .sorted(Comparator.naturalOrder())
                    .map(Command::getKey)
                    .collect(Collectors.joining(", "));
            }
        } else {
            List<Command> requested = commandList.stream()
                .filter(c -> isRequested(nonOptions, c.getKey().substring(1)))
                .filter(c -> canExecute(m.getAuthor(), c))
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());
            StringBuilder builder = new StringBuilder();
            for (Command command : requested) {
                appendHelp(builder, command);
            }
            return builder.toString();
        }
    }

    private boolean isRequested(List<String> nonOptions, String substring) {
        return nonOptions.contains(substring);
    }

    public StringBuilder appendHelp(StringBuilder b, Command c) {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            c.getParser().formatHelpWith(new CustomHelpFormatter(140, 5));
            c.getParser().printHelpOn(stream);
            b.append(String.format("• Help for **%s**: %s%s\n", c.getKey(), c.getDescription(),
                c.getPermissionLevel() > 0 ? " (requires permission level " + c.getPermissionLevel() + ")" : ""))
                .append(new String(stream.toByteArray(), "UTF-8")).append("\n");
        } catch (Exception e) {
            b.append("Could not show help for **").append(c.getKey().substring(1)).append("**\n");
            log.warn("Could not show help: {}", e.toString());
        }
        return b;
    }

    // Discord event subscription
    //////////////////////////////////////////////

    @EventSubscriber
    public void onMessageReceived(MessageReceivedEvent event) {
        IMessage m = event.getMessage();
        String content = m.getContent();
        Optional<Command> match = commandList.stream().filter(c -> c.matches(content)).findFirst();
        if (match.isPresent()) {
            Command command = match.get();
            if (canExecute(m.getAuthor(), command)) {
                // cut away the "command" portion of the message
                String args = content.substring(content.indexOf(command.getKey()) + command.getKey().length());
                args = args.startsWith(" ") ? args.split(" ", 2)[1] : null;
                // add gatekeeper logic
                log.debug("User {} executing command {} with args: {}", format(m.getAuthor()),
                    command.getKey(), args);
                if (command.isQueued()) {
                    CommandJob job = new CommandJob(command, m, args);
                    String key = m.getAuthor().getID();
                    if (gatekeeper.getQueuedJobCount(key) > 0) {
                        tryReplyFrom(m, command, "Please wait until your previous command finishes running");
                    } else {
                        statusReplyFrom(m, command, "Your command will be executed shortly...");
                        CompletableFuture<String> future = gatekeeper.queue(key, job);
                        // handle response and then delete status command
                        future.thenAccept(response -> handleResponse(m, command, response))
                            .thenRun(() -> statusReplyFrom(m, command, null));
                    }
                } else {
                    try {
                        String response = command.execute(m, args);
                        handleResponse(m, command, response);
                        // ignore empty responses (no action)
                    } catch (OptionException e) {
                        log.warn("Invalid call: {}", e.toString());
                        helpReplyFrom(m, command, e.toString());
                    }
                    invokerToStatusMap.remove(m.getID());
                }
            } else {
                // fail silently
                log.debug("User {} has no permission to run {} (requires level {})", format(m.getAuthor()),
                    command.getKey(), command.getPermissionLevel());
            }
        }
    }

    private String format(IUser user) {
        return user.getName() + " " + user.toString();
    }

    private boolean canExecute(IUser user, Command command) {
        return getCommandPermission(user).getLevel() >= command.getPermissionLevel();
    }

    private CommandPermission getCommandPermission(IUser user) {
        if (discordService.isMaster(user)) {
            return CommandPermission.MASTER;
        } else if (discordService.hasSupportRole(user)) {
            return CommandPermission.SUPPORT;
        } else {
            return CommandPermission.NONE;
        }
    }

    // Higher level output control
    ////////////////////////////////////////

    private void handleResponse(IMessage message, Command command, String response) {
        if (response == null) {
            helpReplyFrom(message, command); // show help on null responses
        } else if (!response.isEmpty()) {
            tryReplyFrom(message, command, response);
        } // empty responses fall through silently
    }

    /*
     command |--> handleResponse |--> helpReplyFrom -->| replyFrom |--> answer          |--> Discord4J --> API
     execute \                   \-------------------->\           \--> answerPrivately \
             |---------------------------------------->|           |
     */

    public void helpReplyFrom(IMessage message, Command command) {
        helpReplyFrom(message, command, null);
    }

    public void helpReplyFrom(IMessage message, Command command, String comment) {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            command.getParser().formatHelpWith(new CustomHelpFormatter(140, 5));
            command.getParser().printHelpOn(stream);
            StringBuilder response = new StringBuilder();
            if (comment != null) {
                response.append(comment).append("\n");
            }
            response.append(String.format("• Help for **%s**: %s%s%s\n", command.getKey(), command.getDescription(),
                command.getPermissionLevel() > 0 ? " (requires permission level " + command.getPermissionLevel() + ")" : "",
                command.isExperimental() ? " -- Warning, this command is **experimental** and not well tested yet." : ""))
                .append(new String(stream.toByteArray(), "UTF-8"));
            replyFrom(message, command, response.toString());
        } catch (Exception e) {
            log.warn("Could not show help: {}", e.toString());
        }
    }

    public void statusReplyFrom(IMessage message, Command command, String response) {
        IMessage statusMessage = invokerToStatusMap.get(message.getID());
        if (response == null) {
            if (statusMessage != null && !command.isPersistStatus()) {
                tryDelete(statusMessage);
                invokerToStatusMap.remove(message.getID());
                // commands with persisted status will be purged if not updated after a while
            }
        } else if (response.length() < LENGTH_LIMIT) {
            if (statusMessage == null) {
                statusMessage = tryReplyFrom(message, command, response);
                if (statusMessage != null) {
                    invokerToStatusMap.put(message.getID(), statusMessage);
                }
            } else {
                tryEdit(statusMessage, response);
            }
        } // ignore longer "status" responses
    }

    public void deleteStatusFrom(IMessage message) {
        // ignores persist-status setting
        IMessage statusMessage = invokerToStatusMap.get(message.getID());
        if (statusMessage != null) {
            tryDelete(statusMessage);
        }
        invokerToStatusMap.remove(message.getID());
    }

    @Scheduled(cron = "0 0 * * * *")
    void purgeStatuses() {
        // purge messages not updated for 1 hour
        invokerToStatusMap.values().removeIf(m -> LocalDateTime.now().minusHours(1).isAfter(m.getTimestamp()));
    }

    private IMessage tryEdit(IMessage message, String response) {
        try {
            return discordService.editMessage(message, response);
        } catch (HTTP429Exception | DiscordException | MissingPermissionsException e) {
            log.warn("Could not edit message: {}", e.toString());
        }
        return null;
    }

    private void tryDelete(IMessage message) {
        try {
            discordService.deleteMessage(message);
        } catch (HTTP429Exception | DiscordException | MissingPermissionsException e) {
            log.warn("Could not delete message: {}", e.toString());
        }
    }

    public IMessage tryReplyFrom(IMessage message, Command command, String response) {
        try {
            return replyFrom(message, command, response);
        } catch (HTTP429Exception | MissingPermissionsException | DiscordException e) {
            log.warn("Could not reply to user: {}", e.toString());
        }
        return null;
    }

    public IMessage replyFrom(IMessage message, Command command, String response) throws HTTP429Exception, DiscordException, MissingPermissionsException {
        return commonReply(message, command, response, null);
    }

    public void fileReplyFrom(IMessage message, Command command, File file) throws HTTP429Exception, DiscordException, MissingPermissionsException {
        commonReply(message, command, null, file);
    }

    private IMessage commonReply(IMessage message, Command command, String response, File file) throws HTTP429Exception, DiscordException, MissingPermissionsException {
        ReplyMode replyMode = command.getReplyMode();
        if (replyMode == ReplyMode.PRIVATE) {
            // always to private - so ignore all permission calculations
            return answerPrivately(message, response, file);
        } else if (replyMode == ReplyMode.ORIGIN) {
            // use the same channel as invocation
            return answer(message, response, command.isMention(), file);
        } else if (replyMode == ReplyMode.WITH_PERMISSION) {
            int commandLevel = command.getPermissionLevel();
            int channelLevel = 0;
            String channelId = message.getChannel().getID(); // can be null
            String permissionKey = properties.getDiscord().getChannels().get(channelId);
            if (permissionKey != null) {
                CommandPermission perm = CommandPermission.valueOf(permissionKey.toUpperCase());
                if (perm != null) {
                    channelLevel = perm.getLevel();
                } else {
                    log.warn("Channel {} has an invalid permission key: {}", channelId, permissionKey);
                }
            }
            if (channelLevel >= commandLevel) {
                // channel has a higher level than the command's -> print to origin channel
                return answer(message, response, command.isMention(), file);
            } else {
                return answerPrivately(message, response, file);
            }
        } else {
            log.warn("This command ({}) has an invalid reply-mode: {}", command.getKey(), command.getReplyMode());
            return answerPrivately(message, response, null);
        }
    }

    // Lower level output control
    ////////////////////////////////////////

    public IMessage answerToChannel(IChannel channel, String answer) throws HTTP429Exception, DiscordException, MissingPermissionsException {
        if (answer.length() > LENGTH_LIMIT) {
            SplitMessage s = new SplitMessage(answer);
            IMessage last = null;
            for (String str : s.split(LENGTH_LIMIT)) {
                last = answerToChannel(channel, str);
            }
            return last;
        } else {
            return discordService.sendMessage(channel, answer);
        }
    }

    private IMessage answer(IMessage message, String answer, boolean mention, File file) throws HTTP429Exception, DiscordException, MissingPermissionsException {
        if (file != null) {
            try {
                discordService.sendFile(message.getChannel(), file);
            } catch (HTTP429Exception | IOException | MissingPermissionsException | DiscordException e) {
                log.warn("Could not send file to user {} in channel {}: {}",
                    message.getAuthor(), message.getChannel(), e.toString());
            }
            return null;
        } else {
            if (answer.length() > LENGTH_LIMIT) {
                SplitMessage s = new SplitMessage(answer);
                IMessage last = null;
                for (String str : s.split(LENGTH_LIMIT)) {
                    last = answer(message, str, mention, null);
                }
                return last;
            } else {
                return discordService.sendMessage(message.getChannel(), (mention ? message.getAuthor().mention() : "") + answer);
            }
        }
    }

    private IMessage answerPrivately(IMessage message, String answer, File file) throws HTTP429Exception, DiscordException, MissingPermissionsException {
        if (file != null) {
            try {
                discordService.sendFilePrivately(message.getAuthor(), file);
            } catch (Exception e) {
                log.warn("Could not send file to user {} in channel {}: {}",
                    message.getAuthor(), message.getChannel(), e.toString());
            }
        } else {
            if (answer.length() > LENGTH_LIMIT) {
                SplitMessage s = new SplitMessage(answer);
                IMessage last = null;
                for (String split : s.split(LENGTH_LIMIT)) {
                    answerPrivately(message, split, null);
                }
                return last;
            } else {
                return discordService.sendPrivateMessage(message.getAuthor(), answer);
            }
        }
        return null; // on multipart messages
    }

    // Command registering operations
    /////////////////////////////////////

    public Command register(Command command) {
        String description = asList(opt(command.getMatchType(), "", " match", MatchType.STARTS_WITH),
            opt(command.getReplyMode(), "", " replies" +
                opt(command.isMention(), " with mention", "", false, true).orElse(""), null),
            opt(command.isQueued(), "queued", "", false, true),
            opt(command.isPersistStatus(), "persisted status", "", false, true),
            opt(command.isExperimental(), "experimental", "", false, true))
            .stream().filter(Optional::isPresent).map(Optional::get).collect(Collectors.joining(", "));
        log.info("Command {} [{}] {}", padRight(command.getKey(), 20),
            padLeft("" + command.getPermissionLevel(), 3), description);
        commandList.add(command);
        return command;
    }

    private <T> Optional<String> opt(T value, String prefix, String suffix, T ignoredValue) {
        return Optional.ofNullable(value)
            .map(v -> v.equals(ignoredValue) ? null : v)
            .map(v -> prefix + v.toString() + suffix);
    }

    private <T> Optional<String> opt(T value, String prefix, String suffix, T ignoredValue, T hiddenValue) {
        return Optional.ofNullable(value)
            .map(v -> v.equals(ignoredValue) ? null : v)
            .map(v -> prefix + (v.equals(hiddenValue) ? "" : v.toString()) + suffix);
    }

    public void unregister(Command command) {
        log.info("Removing {}", command.getKey());
        commandList.remove(command);
    }
}
