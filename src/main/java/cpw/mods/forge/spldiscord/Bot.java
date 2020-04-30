package cpw.mods.forge.spldiscord;

import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.event.EventDispatcher;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.event.domain.message.ReactionAddEvent;
import discord4j.core.object.entity.*;
import discord4j.core.object.presence.Activity;
import discord4j.core.object.presence.Presence;
import discord4j.core.object.reaction.ReactionEmoji;
import discord4j.core.object.util.Snowflake;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.logging.log4j.LogManager;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.GroupedFlux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.util.function.Tuple3;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Bot {
    private static final Snowflake REQUESTCHANNEL = Util.env("REQUEST_CHANNEL");
    private static final Snowflake MODUPDATECHANNEL = Util.env("MODS_CHANNEL");
    private static final Snowflake GUILD = Util.env("GUILD");
    private static final Snowflake APPROVERROLE = Util.env("APPROVER_ROLE");
    private static final ReactionEmoji.Unicode THUMBSUP = ReactionEmoji.unicode("\ud83d\udc4d");
    private static final ReactionEmoji.Unicode UNAMUSED = ReactionEmoji.unicode("\ud83d\ude12");
    private static final ReactionEmoji.Unicode TICK = ReactionEmoji.unicode("\u2714");
    private static final ReactionEmoji.Unicode CROSS = ReactionEmoji.unicode("\u274C");
    private static final Path MODDIR = Paths.get(Util.defaultEnv("OUTPUT_DIR", "."));
    private Snowflake me;
    private static final List<Snowflake> MONITORED_CHANNELS = Arrays.asList(REQUESTCHANNEL, MODUPDATECHANNEL);
    private final Map<Snowflake, Function<Flux<ReactionAddEvent>, Publisher<Void>>> messageHandlerByChannel;

    public static void main(String[] args) {
        LogManager.getLogger().info("HELLO");
        new Bot(Util.defaultEnv("BOT_TOKEN", "0"));
    }

    private Bot(final String token) {
        messageHandlerByChannel = new HashMap<>();
        messageHandlerByChannel.put(REQUESTCHANNEL, this::handleAuthChannel);
        messageHandlerByChannel.put(MODUPDATECHANNEL, this::handleModChannel);

        DiscordClient client = new DiscordClientBuilder(token).build();
        EventDispatcher eventDispatcher = client.getEventDispatcher();

        Mono<Void> setup = eventDispatcher.on(ReadyEvent.class).flatMap(this::setup).then();
        Mono<Void> catchup = eventDispatcher.on(ReadyEvent.class).flatMap(this::catchupMessages).then();
        Mono<Void> reaction = eventDispatcher.on(ReactionAddEvent.class)
                .transform(this::filterReactionEvents)
                .groupBy(ReactionAddEvent::getChannelId)
                .transform(this::dispatchReactionEventToHandler)
                .then();
        Mono<Void> login = client.login();
        Mono<Snowflake> me = client.getSelf().map(User::getId).map(id -> this.me = id);
        Runtime.getRuntime().addShutdownHook(new Thread(()->client.logout().block()));
        Mono.when(setup, catchup, reaction, me, login).doOnError(this::error).block();
    }

    private Mono<Void> setup(final ReadyEvent event) {
        return Flux.fromIterable(MONITORED_CHANNELS)
                .flatMap(event.getClient()::getChannelById)
                .ofType(TextChannel.class)
                .map(GuildChannel::getName)
                .collect(Collectors.joining(", "))
                .flatMap(name -> event.getClient()
                        .updatePresence(Presence.online(Activity.watching(name)))
                );
    }

    private Mono<Void> catchupMessages(final ReadyEvent evt) {
        Snowflake now = Snowflake.of(Instant.now());
        return evt.getClient()
                .getGuildById(GUILD)
                .flatMap(g -> g.getChannelById(REQUESTCHANNEL).ofType(TextChannel.class))
                .flatMapMany(tc -> tc.getMessagesBefore(now))
                .transform(this::authChannelFilter)
                .flatMap(this::handleValidAuthMessage)
                .then();
    }

    private Flux<ReactionAddEvent> filterReactionEvents(final Flux<ReactionAddEvent> reactions) {
        return reactions
                .filter(event -> Objects.equals(THUMBSUP, event.getEmoji()))
                .filter(event -> MONITORED_CHANNELS.contains(event.getChannelId()));
    }

    private Flux<Void> dispatchReactionEventToHandler(Flux<GroupedFlux<Snowflake, ReactionAddEvent>> flux) {
        return flux.flatMap(gf -> gf.transform(messageHandlerByChannel.get(gf.key())));
    }

    private void error(final Throwable throwable) {
        LogManager.getLogger().throwing(throwable);
    }

    private Publisher<Void> handleAuthChannel(final Flux<ReactionAddEvent> reactionEvent) {
        return reactionEvent
                .flatMap(ReactionAddEvent::getMessage)
                .transform(this::authChannelFilter)
                .flatMap(this::handleValidAuthMessage)
                .then();
    }

    private Publisher<Void> handleModChannel(final Flux<ReactionAddEvent> reactionEvent) {
        return reactionEvent
                .flatMap(ReactionAddEvent::getMessage)
                .transform(this::modChannelFilter)
                .flatMap(this::handleValidModMessage)
                .then();
    }

    private Flux<Message> authChannelFilter(Flux<Message> messages) {
        return messages
                .filter(mess -> !mess.getAttachments().isEmpty())
                .filter(mess -> mess.getAttachments().stream().anyMatch(attachment -> attachment.getFilename().endsWith(".csr")))
                .transform(this::filterMessageReactions);
    }

    private Flux<Message> modChannelFilter(Flux<Message> messages) {
        return messages.transform(this::filterMessageReactions);
    }

    private Flux<Message> filterMessageReactions(Flux<Message> messages) {
        return messages
                .filterWhen(m -> m
                        .getReactors(THUMBSUP)
                        .flatMap(r -> r.asMember(GUILD))
                        .any(mem -> mem.getRoleIds().contains(APPROVERROLE)))
                .filterWhen(m -> m
                        .getReactors(TICK)
                        .map(User::getId)
                        .collect(Collectors.toSet())
                        .map(set -> !set.contains(me)))
                .filterWhen(m -> m
                        .getReactors(CROSS)
                        .map(User::getId)
                        .collect(Collectors.toSet())
                        .map(set -> !set.contains(me)))
                .filterWhen(m -> m
                        .getReactors(UNAMUSED)
                        .map(User::getId)
                        .collect(Collectors.toSet())
                        .map(set -> !set.contains(me)));
    }

    private Mono<Void> handleValidAuthMessage(Message msg) {
        Mono<InputStream> cert = msg.getAttachments()
                .stream()
                .findFirst()
                .map(attachment -> downloadAttachment(attachment, CertSigner::signCSR)).orElse(Mono.empty());
        Mono<Member> userName = msg.getAuthorAsMember();
        Mono<TextChannel> channel = msg.getChannel().ofType(TextChannel.class);
        Mono<Message> message = Mono.zip(cert, userName, channel).flatMap(this::buildCertificateAttachment);
        Mono<Void> ok = msg.addReaction(TICK);
        return message.then(ok).onErrorResume(t->this.reactionError(t, msg));
    }

    private Mono<Void> handleValidModMessage(final Message message) {
        Mono<Void> ok = message.addReaction(TICK);
        Mono<Void> files;
        if (message.getAttachments().stream().anyMatch(att->att.getFilename().endsWith(".jar"))) {
            files = Flux.from(message
                    .getAttachments()
                    .stream()
                    .findFirst()
                    .map(attachment -> downloadAttachment(attachment,
                            inputStream -> saveFile(inputStream, attachment.getFilename())))
                    .orElse(Mono.empty()))
                    .then(ok);
        } else if (message.getEmbeds().stream().anyMatch(e->e.getUrl().map(s->s.endsWith(".jar")).orElse(Boolean.FALSE))) {
            files = Flux
                    .fromIterable(message.getEmbeds())
                    .flatMap(e->e.getUrl()
                            .map(url-> downloadUrl(url,
                                    inputStream -> saveFile(inputStream, getFilenameFromUrlString(url))))
                            .orElse(Mono.empty()))
                    .then(ok);
        } else if (message.getContent().map(s->s.startsWith("http") && s.endsWith(".jar")).orElse(Boolean.FALSE)) {
            files = Flux.from(message.getContent()
                    .map(url -> downloadUrl(url,
                            inputStream -> saveFile(inputStream, getFilenameFromUrlString(url)))).orElse(Mono.empty()))
                    .then(ok);
        } else if (message.getContent().map(s->s.startsWith("https://www.curseforge.com/minecraft/")).orElse(Boolean.FALSE)) {
            files = downloadUrl("https://addons-ecs.forgesvc.net/api/v2/addon/0/file/"+ getFilenameFromUrlString(message.getContent().get())+"/download-url", this::getInputStreamAsString)
                    .flatMap(actual->downloadUrl(actual,
                            is-> saveFile(is, getFilenameFromUrlString(actual))))
                    .then(ok);
        } else {
            files = message.addReaction(UNAMUSED);
        }
        return files.onErrorResume(throwable -> reactionError(throwable, message));
    }

    private Mono<Void> reactionError(final Throwable throwable, final Message message) {
        LogManager.getLogger().catching(throwable);
        return message.addReaction(CROSS);
    }

    private String getInputStreamAsString(final InputStream is) {
        Scanner s = new Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private String getFilenameFromUrlString(final String url) {
        return url.substring(url.lastIndexOf('/')+1).trim();
    }

    private String saveFile(final InputStream inputStream, final String filename) {
        try {
            LogManager.getLogger().info("Downloading file to {}", filename);
            Files.copy(inputStream, MODDIR.resolve(filename));
            LogManager.getLogger().info("File download to {} complete", filename);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return filename;
    }

    private Mono<Message> buildCertificateAttachment(final Tuple3<InputStream, Member, TextChannel> tuple) {
        final TextChannel tc = tuple.getT3();
        final Member user = tuple.getT2();
        final InputStream cert = tuple.getT1();
        LogManager.getLogger().info("Generating certificate for {}", user.getUsername());
        return tc.createMessage(spec -> spec
                .addFile(user.getUsername() + ".pem", cert)
                .setContent(user.getMention() + " your certificate is here. Download and install it in your servermods folder.")
        );
    }

    private <T> Mono<T> downloadAttachment(final Attachment attachment, final Function<InputStream, T> inputStreamHandler) {
        return downloadUrl(attachment.getUrl(), inputStreamHandler);
    }

    private <T> Mono<T> downloadUrl(final String url, final Function<InputStream, T> inputStreamHandler) {
        HttpClient httpClient = HttpClient.create().followRedirect(true)
                .headers(h->h.set(HttpHeaderNames.USER_AGENT, "SPL Discord Bot (1.0)").set(HttpHeaderNames.ACCEPT, "*/*"));
        return httpClient.get().uri(url).responseSingle((r, buf) -> handleDownload(r, url, buf, inputStreamHandler));
    }

    private <T> Mono<T> handleDownload(HttpClientResponse r, final String url, ByteBufMono buf, final Function<InputStream, T> inputStreamHandler) {
        if (r.status() == HttpResponseStatus.OK) {
            return buf.asInputStream().map(inputStreamHandler);
        } else {
            buf.asString().doFinally(s->LogManager.getLogger().error("Failed to download {} : {}", url, s));
            return Mono.error(new IllegalStateException("Invalid status response "+r.status()));
        }
    }
}