package com.ugcleague.ops.service.discord;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.ugcleague.ops.domain.Publisher;
import com.ugcleague.ops.domain.Subscriber;
import com.ugcleague.ops.event.*;
import com.ugcleague.ops.repository.PublisherRepository;
import com.ugcleague.ops.repository.SubscriberRepository;
import com.ugcleague.ops.service.DiscordService;
import com.ugcleague.ops.service.discord.command.CommandBuilder;
import com.ugcleague.ops.util.DateUtil;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sx.blah.discord.handle.obj.IChannel;
import sx.blah.discord.handle.obj.IMessage;
import sx.blah.discord.handle.obj.IUser;
import sx.blah.discord.util.MessageBuilder;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

@Service
@Transactional
public class AnnouncerService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncerService.class);
    private static final String nonOptDesc = "announcement publishers: updates, issues";
    private static final String SUPPORT = "support";
    private static final String CHANNEL = "channel";

    private final DiscordService discordService;
    private final PublisherRepository publisherRepository;
    private final SubscriberRepository subscriberRepository;
    private final CommandService commandService;

    private OptionSpec<String> startNonOptionSpec;
    private OptionSpec<String> stopNonOptionSpec;

    @Autowired
    public AnnouncerService(DiscordService discordService, PublisherRepository publisherRepository,
                            SubscriberRepository subscriberRepository, CommandService commandService) {
        this.discordService = discordService;
        this.publisherRepository = publisherRepository;
        this.subscriberRepository = subscriberRepository;
        this.commandService = commandService;
    }

    @PostConstruct
    private void configure() {
        initStartAnnounceCommand();
        initStopAnnounceCommand();
    }

    private void initStartAnnounceCommand() {
        // +announce (non-option: publishers)
        OptionParser parser = new OptionParser();
        parser.posixlyCorrect(true);
        parser.acceptsAll(asList("?", "h", "help"), "display the help").forHelp();
        startNonOptionSpec = parser.nonOptions(nonOptDesc).ofType(String.class);
        commandService.register(CommandBuilder.startsWith("+announce")
            .description("Enables multi-purpose announcements to this channel").permission("support")
            .parser(parser).command(this::executeStartAnnounceCommand).build());
    }

    private void initStopAnnounceCommand() {
        // -announce (non-option: publishers)
        OptionParser parser = new OptionParser();
        parser.posixlyCorrect(true);
        parser.acceptsAll(asList("?", "h", "help"), "display the help").forHelp();
        stopNonOptionSpec = parser.nonOptions(nonOptDesc).ofType(String.class);
        commandService.register(CommandBuilder.startsWith("-announce")
            .description("Disables an already subscribed announcer from this channel").permission("support")
            .parser(parser).command(this::executeStopAnnounceCommand).build());
    }

    private String executeStartAnnounceCommand(IMessage m, OptionSet o) {
        if (!o.has("?")) {
            StringBuilder message = new StringBuilder();
            for (String opt : o.valuesOf(startNonOptionSpec)) {
                String line = subscribeAnnouncer(m.getAuthor(), opt, m.getChannel());
                if (line != null) {
                    message.append(line).append("\n");
                }
            }
            return message.toString();
        }
        return null;
    }

    private String executeStopAnnounceCommand(IMessage m, OptionSet o) {
        if (!o.has("?")) {
            StringBuilder message = new StringBuilder();
            for (String opt : o.valuesOf(stopNonOptionSpec)) {
                String line = unsubscribeAnnouncer(m.getAuthor(), opt, m.getChannel());
                if (line != null) {
                    message.append(line).append("\n");
                }
            }
            return message.toString();
        }
        return null;
    }

    private String subscribeAnnouncer(IUser author, String announcer, IChannel channel) {
        String key = announcer.trim();
        if (!key.isEmpty() && !key.equals(SUPPORT)) {
            Publisher publisher = publisherRepository.findByName(key).orElseGet(() -> newPublisher(key));
            Subscriber subscriber;
            if (channel.isPrivate()) {
                subscriber = subscriberRepository.findByUserId(author.getID())
                    .orElseGet(() -> newUserSubscriber(author));
            } else {
                subscriber = subscriberRepository.findByUserIdAndName(channel.getID(), CHANNEL)
                    .orElseGet(() -> newChannelSubscriber(channel));
            }
            Set<Subscriber> subs = publisher.getSubscribers();
            if (subs.stream().anyMatch(s -> s.getUserId().equals(channel.getID()) && s.getName().equals(CHANNEL))) {
                return "That channel is already receiving announcements about **" + announcer + "**";
            } else {
                subs.add(subscriber);
                subscriberRepository.save(subscriber);
                publisherRepository.save(publisher);
                if (channel.isPrivate()) {
                    return ":satellite: I'll PM you about **" + announcer + "**";
                } else {
                    try {
                        discordService.channelMessage(channel.getID())
                            .appendContent(":satellite: I'll let this channel know about ")
                            .appendContent(announcer, MessageBuilder.Styles.BOLD).send();
                    } catch (Exception e) {
                        log.warn("Could not send channel message to {}: {}", channel, e.toString());
                    }
                    return String.format("Subscription of %s to **%s** successful", channel.mention(), announcer);
                }
            }
        } else {
            log.debug("Attempted to subscribe announcer: {}", author);
        }
        return null;
    }

    private String unsubscribeAnnouncer(IUser author, String announcer, IChannel channel) {
        String key = announcer.trim();
        if (!key.isEmpty() && !key.equals(SUPPORT)) {
            Optional<Subscriber> o = subscriberRepository.findByUserIdAndName(channel.getID(), CHANNEL);
            if (o.isPresent()) {
                Subscriber sub = o.get();
                Publisher publisher = publisherRepository.findByName(key).orElseGet(() -> newPublisher(key));
                Set<Subscriber> subs = publisher.getSubscribers();
                if (subs.stream().anyMatch(s -> s.getUserId().equals(channel.getID()))) {
                    subs.remove(sub);
                    publisherRepository.save(publisher);
                    try {
                        discordService.channelMessage(channel.getID())
                            .appendContent(":speak_no_evil: I'll stop sending messages about ")
                            .appendContent(announcer, MessageBuilder.Styles.BOLD)
                            .appendContent(" to this channel").send();
                    } catch (Exception e) {
                        log.warn("Could not send channel message to {}: {}", channel, e.toString());
                    }
                    return String.format("Cancelled subscription of %s to **%s**", channel.mention(), announcer);
                } else {
                    return "That channel is not receiving announcements about **" + announcer + "**";
                }
            }
        } else {
            log.debug("Attempted to unsubscribe announcer: {}", author);
        }
        return null;
    }

    private Publisher newPublisher(String name) {
        Publisher publisher = new Publisher();
        publisher.setName(name);
        return publisher;
    }

    private Subscriber newChannelSubscriber(IChannel channel) {
        Subscriber subscriber = new Subscriber();
        subscriber.setUserId(channel.getID());
        subscriber.setName(CHANNEL);
        return subscriber;
    }

    private Subscriber newUserSubscriber(IUser user) {
        Subscriber subscriber = new Subscriber();
        subscriber.setUserId(user.getID());
        subscriber.setName("user:" + user.getName());
        return subscriber;
    }

    @EventListener
    private void onFeedUpdate(FeedUpdatedEvent event) {
        SyndFeed syndFeed = event.getSource();
        announce("updates", buildFeedString(syndFeed));
    }

    private String buildFeedString(SyndFeed syndFeed) {
        // title published on date
        String latestEntryTitle = "News item";
        Optional<SyndEntry> o = syndFeed.getEntries().stream().findFirst();
        if (o.isPresent()) {
            latestEntryTitle = o.get().getTitle();
        }
        return String.format("%s published **%s**", latestEntryTitle,
            DateUtil.formatRelative(Duration.between(Instant.now(), syndFeed.getPublishedDate().toInstant())));
    }

    @EventListener
    private void onUpdateStarted(GameUpdateStartedEvent event) {
        announce("updates", "Preparing to update UGC game servers");
    }

    @EventListener
    private void onUpdateCompleted(GameUpdateCompletedEvent event) {
        announce("updates", ":ok_hand: TF2 game servers updated to v" + event.getVersion());
    }

    @EventListener
    private void onUpdateDelayed(GameUpdateDelayedEvent event) {
        String list = event.getServers().stream()
            .map(s -> String.format("_%s_", s.getName()))
            .collect(Collectors.joining(", "));
        announce("issues", "Server updates are taking a bit too long. Pending: " + list);
    }

    @EventListener
    private void onServerDeath(GameServerDeathEvent event) {
        String list = event.getSource().entrySet().stream()
            .map(e -> String.format("*%s* (%s) is unresponsive since %s",
                e.getKey().getName(), e.getKey().getAddress(),
                DateUtil.formatRelative(Duration.between(Instant.now(), e.getValue().getCreated()))))
            .collect(Collectors.joining("\n"));
        announce("issues", "Game Server Status:\n" + list);
    }

    private void announce(String publisherName, String message) {
        publisherRepository.findByName(publisherName).ifPresent(pub -> {
            Set<Subscriber> subs = pub.getSubscribers();
            subs.stream().forEach(sub -> {
                try {
                    if (sub.getName().equals(CHANNEL)) {
                        discordService.channelMessage(sub.getUserId())
                            .appendContent("<" + publisherName + "> ")
                            .appendContent(message).send();
                    } else {
                        discordService.privateMessage(sub.getUserId())
                            .appendContent("<" + publisherName + "> ")
                            .appendContent(message).send();
                    }
                } catch (Exception e) {
                    log.warn("Could not send message to '{}': {}", publisherName, e.toString());
                }
            });
        });
    }
}
