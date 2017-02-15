package ca.pjer.zdlb;

import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.EventStream;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.Container;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.ContainerState;
import com.spotify.docker.client.messages.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Objects;
import java.util.concurrent.Executor;

import static ca.pjer.zdlb.App.ZDLB;

@Component
public class EventListener {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    DockerClient dockerClient;

    @Autowired
    Publisher publisher;

    @Autowired
    Executor executor;

    private EventStream eventStream;

    @PostConstruct
    void start() throws DockerException, InterruptedException {
        eventStream = dockerClient.events(DockerClient.EventsParam.type(Event.Type.CONTAINER), DockerClient.EventsParam.label(ZDLB));
        executor.execute(this::consumeRemainingEvent);
        dockerClient.listContainers(DockerClient.ListContainersParam.withLabel(ZDLB)).stream()
                .map(Container::id)
                .map(it -> {
                    try {
                        return dockerClient.inspectContainer(it);
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .forEach(publisher::publish);
    }

    @PreDestroy
    void stop() {
        eventStream.close();
    }

    private void consumeRemainingEvent() {
        eventStream.forEachRemaining(event -> {
            try {
                Event.Actor actor = event.actor();
                String action = event.action();
                if (actor != null && action != null) {
                    String id = actor.id();
                    switch (action) {
                        case "start":
                        case "restart":
                        case "unpause":
                        case "health_status: healthy":
                            ContainerInfo containerInfo = dockerClient.inspectContainer(id);
//                            logger.info("{}", objectMapper.writeValueAsString(containerInfo));
                            ContainerState.Health health = containerInfo.state().health();
                            if (health == null || "healthy".equals(health.status())) {
                                logger.info("[{}] try to publish (because {})", id, action);
                                publisher.publish(containerInfo);
                            }
                            break;
                        case "die":
                        case "oom":
                        case "pause":
                        case "health_status: unhealthy":
                            logger.info("[{}] try to unpublish (because {})", id, action);
                            publisher.unpublish(id);
                            break;
                    }
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        });
    }
}
