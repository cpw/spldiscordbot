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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.util.function.Tuple3;

import java.io.*;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Bot {
    private static final Snowflake REQUESTCHANNEL = Util.env("REQUEST_CHANNEL");
    private static final Snowflake GUILD = Util.env("GUILD");
    private static final Snowflake APPROVERROLE = Util.env("APPROVER_ROLE");
    private static final ReactionEmoji.Unicode THUMBSUP = ReactionEmoji.unicode("\ud83d\udc4d");
    private static final ReactionEmoji.Unicode TICK = ReactionEmoji.unicode("\u2714");
    private Snowflake me;

    public static void main(String[] args) {
        new Bot(Util.defaultEnv("BOT_TOKEN", "0"));
    }

    private Bot(final String token) {
        DiscordClient client = new DiscordClientBuilder(token).build();
        EventDispatcher eventDispatcher = client.getEventDispatcher();

        Mono<Void> setup = eventDispatcher.on(ReadyEvent.class).flatMap(this::setup).then();
        Mono<Void> catchup = eventDispatcher.on(ReadyEvent.class).flatMap(this::catchupMessages).then();
        Mono<Void> reaction = eventDispatcher.on(ReactionAddEvent.class).flatMap(this::onReaction).then();
        Mono<Void> login = client.login();
        Mono<Snowflake> me = client.getSelf().map(User::getId).map(id -> this.me = id);
        Runtime.getRuntime().addShutdownHook(new Thread(()->client.logout().block()));
        Mono.when(setup, catchup, reaction, me, login).doOnError(this::error).block();
    }


    private void error(final Throwable throwable) {
        LogManager.getLogger().throwing(throwable);
    }

    private Mono<Void> onReaction(final ReactionAddEvent reactionAddEvent) {
        return Flux.just(reactionAddEvent)
                .filter(event -> Objects.equals(THUMBSUP, event.getEmoji()))
                .filterWhen(event -> event.getChannel().map(ch -> Objects.equals(ch.getId(), REQUESTCHANNEL)))
                .flatMap(ReactionAddEvent::getMessage)
                .transform(this::filterMessageFlux)
                .flatMap(this::handleValidMessage)
                .then();
    }

    private Mono<Void> setup(final ReadyEvent event) {
        return event.getClient()
                .getChannelById(REQUESTCHANNEL)
                .ofType(TextChannel.class)
                .flatMap(name -> event.getClient()
                    .updatePresence(Presence.online(Activity.watching(name.getName())))
                );
    }

    private Flux<Message> filterMessageFlux(Flux<Message> messageFlux) {
        return messageFlux
                .filter(mess -> !mess.getAttachments().isEmpty())
                .filter(mess -> mess.getAttachments().stream().anyMatch(attachment -> attachment.getFilename().endsWith(".csr")))
                .filterWhen(m -> m
                        .getReactors(THUMBSUP)
                        .flatMap(r -> r.asMember(GUILD))
                        .any(mem -> mem.getRoleIds().contains(APPROVERROLE)))
                .filterWhen(m -> m
                        .getReactors(TICK)
                        .map(User::getId)
                        .collect(Collectors.toSet())
                        .map(set -> !set.contains(me)));
    }

    private Mono<Void> catchupMessages(final ReadyEvent evt) {
        Snowflake now = Snowflake.of(Instant.now());
        return evt.getClient()
                .getGuildById(GUILD)
                .flatMap(g -> g.getChannelById(REQUESTCHANNEL).ofType(TextChannel.class))
                .flatMapMany(tc -> tc.getMessagesBefore(now))
                .transform(this::filterMessageFlux)
                .flatMap(this::handleValidMessage)
                .then();
    }

    private Mono<Void> handleValidMessage(Message msg) {
        Mono<InputStream> cert = msg.getAttachments().stream().findFirst().map(this::generateCert).orElse(Mono.empty());
        Mono<Member> userName = msg.getAuthorAsMember();
        Mono<TextChannel> channel = msg.getChannel().ofType(TextChannel.class);
        Mono<Message> message = Mono.zip(cert, userName, channel).flatMap(this::buildAttachment);
        Mono<Void> reaction = msg.addReaction(TICK);
        return message.then(reaction);
    }

    private Mono<Message> buildAttachment(final Tuple3<InputStream, Member, TextChannel> tuple) {
        final TextChannel tc = tuple.getT3();
        final Member user = tuple.getT2();
        final InputStream cert = tuple.getT1();
        LogManager.getLogger().info("Generating certificate for {}", user.getUsername());
        return tc.createMessage(spec -> spec
                .addFile(user.getUsername() + ".pem", cert)
                .setContent(user.getMention() + " your certificate is here. Download and install it in your servermods folder.")
        );
    }

    private Mono<InputStream> generateCert(final Attachment attachment) {
        HttpClient httpClient = HttpClient.create()
                .headers(h->h.set(HttpHeaderNames.USER_AGENT, "SPL Discord Bot (1.0)").set(HttpHeaderNames.ACCEPT, "*/*"));
        return httpClient.get().uri(attachment.getUrl()).responseSingle(this::handleCertDownload);
    }

    private Mono<InputStream> handleCertDownload(HttpClientResponse r, ByteBufMono buf) {
        if (r.status() == HttpResponseStatus.OK) {
            return buf.asInputStream().map(CertSigner::signCSR);
        } else {
            LogManager.getLogger().error("Failed to retrieve certificate request from discord: {}", buf.asString().block());
            return Mono.error(new IllegalStateException("Invalid status response "+r.status()));
        }
    }
}