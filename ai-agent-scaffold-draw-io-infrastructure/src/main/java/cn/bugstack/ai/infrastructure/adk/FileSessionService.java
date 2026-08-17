package cn.bugstack.ai.infrastructure.adk;

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Restart-safe local ADK session store. One JSON file contains state and the event journal. */
@Service
@ConditionalOnProperty(name="ai.agent.persistence.mode",havingValue="file")
public class FileSessionService implements BaseSessionService {
    private final Path root;

    public FileSessionService(@Value("${ai.agent.persistence.root:${user.dir}/data/agent-runtime}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize().resolve("sessions");
    }

    @Override
    public Single<Session> createSession(String appName, String userId, ConcurrentMap<String, Object> state, String sessionId) {
        return Single.fromCallable(() -> {
            String id = sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
            ConcurrentMap<String, Object> initialState = state == null ? new ConcurrentHashMap<>() : state;
            Session session = Session.builder(id).appName(appName).userId(userId).state(initialState)
                    .events(new ArrayList<>()).lastUpdateTime(Instant.now()).build();
            persist(session);
            return session;
        });
    }

    @Override
    public Maybe<Session> getSession(String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
        return Maybe.fromCallable(() -> read(path(appName, userId, sessionId)).orElse(null));
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        return Single.fromCallable(() -> {
            List<Session> sessions = new ArrayList<>();
            Path directory = directory(appName, userId);
            if (Files.isDirectory(directory)) {
                try (var files = Files.list(directory)) {
                    files.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .forEach(path -> read(path).ifPresent(sessions::add));
                }
            }
            return ListSessionsResponse.builder().sessions(sessions).build();
        });
    }

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
        return Completable.fromAction(() -> Files.deleteIfExists(path(appName, userId, sessionId)));
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        return getSession(appName, userId, sessionId, Optional.empty())
                .switchIfEmpty(Single.error(new IllegalStateException("Session 不存在: " + sessionId)))
                .map(session -> ListEventsResponse.builder().events(session.events()).build());
    }

    @Override
    public Single<Event> appendEvent(Session session, Event event) {
        return BaseSessionService.super.appendEvent(session, event).doOnSuccess(ignored -> persist(session));
    }

    @Override
    public Completable closeSession(Session session) {
        return Completable.fromAction(() -> persist(session));
    }

    private synchronized void persist(Session session) {
        try {
            session.lastUpdateTime(Instant.now());
            Path target = path(session.appName(), session.userId(), session.id());
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), session.id(), ".tmp");
            Files.writeString(temporary, session.toString(), StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            throw new IllegalStateException("持久化 ADK Session 失败", e);
        }
    }

    private Optional<Session> read(Path path) {
        if (!Files.exists(path)) return Optional.empty();
        try { return Optional.of(Session.fromJson(Files.readString(path, StandardCharsets.UTF_8))); }
        catch (IOException e) { throw new IllegalStateException("读取 ADK Session 失败", e); }
    }

    private Path path(String appName, String userId, String sessionId) {
        return directory(appName, userId).resolve(safe(sessionId) + ".json");
    }

    private Path directory(String appName, String userId) {
        return root.resolve(safe(appName)).resolve(safe(userId));
    }

    private String safe(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
