package ca.pjer.zdlb;

import ca.pjer.glbctl.GlbCtl;
import ca.pjer.glbctl.GlbCtlFactory;
import ca.pjer.glbctl.GlbNode;
import com.google.common.collect.ImmutableMap;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.ContainerNotFoundException;
import com.spotify.docker.client.messages.AttachedNetwork;
import com.spotify.docker.client.messages.ContainerInfo;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ca.pjer.zdlb.App.*;

@Component
public class Publisher {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    Environment environment;

    @Autowired
    DockerClient dockerClient;

    private final Map<String, AppInfo> appInfos = new HashMap<>();

    private ContainerInfo selfContainerInfo;

    @PostConstruct
    void init() throws Exception {
        String hostname = InetAddress.getLocalHost().getHostName();
        selfContainerInfo = dockerClient.inspectContainer(hostname);
    }

    private <R> R label(ContainerInfo containerInfo, String name, Function<String, R> transform, Function<ContainerInfo, R> fallback) {
        return Optional.ofNullable(containerInfo.config().labels()).orElse(ImmutableMap.of()).entrySet().stream()
                .filter(it -> it.getKey().equals(name)).map(Map.Entry::getValue)
                .map(transform).findAny().orElse(fallback.apply(containerInfo));
    }

    private String defaultGlbctlHost(ContainerInfo containerInfo) {
        return environment.getProperty(AUTOGLBD_DEFAULT_GLBCTL_HOST, "glbd");
    }

    private int defaultGlbctlPort(ContainerInfo containerInfo) {
        return environment.getProperty(AUTOGLBD_DEFAULT_GLBCTL_PORT, Integer.class, 4444);
    }

    private String defaultAppId(ContainerInfo containerInfo) {
        return StringUtils.substringBeforeLast(containerInfo.config().image(), ":");
    }

    private int defaultAppPort(ContainerInfo containerInfo) {
        return Optional.ofNullable(containerInfo.networkSettings().ports()).orElse(ImmutableMap.of()).keySet().stream()
                .filter(it -> StringUtils.substringAfterLast(it, "/").equals("tcp"))
                .map(it -> StringUtils.substringBeforeLast(it, "/"))
                .map(Integer::parseInt).findFirst()
                .orElse(environment.getProperty(AUTOGLBD_DEFAULT_APP_PORT, Integer.class, -1));
    }

    private String defaultAppDeploy(ContainerInfo containerInfo) {
        return containerInfo.image();
    }

    private String resolve(String host) throws UnknownHostException {
        return InetAddress.getByName(host).getHostAddress();
    }

    public synchronized void publish(ContainerInfo containerInfo) {

        String id = containerInfo.id();

        try {

            String glbctlHost = label(containerInfo, ZDLB_GLBCTL_HOST, String::toString, this::defaultGlbctlHost);

            int glbctlPort = label(containerInfo, ZDLB_GLBCTL_PORT, Integer::parseInt, this::defaultGlbctlPort);

            String appId = label(containerInfo, ZDLB_APP_ID, String::toString, this::defaultAppId);

            int appPort = label(containerInfo, ZDLB_APP_PORT, Integer::parseInt, this::defaultAppPort);
            if (appPort < 0) {
                logger.error("[{}] App has no exposed port to fallback to", id);
                return;
            }

            String appDeploy = label(containerInfo, ZDLB_APP_DEPLOY, String::toString, this::defaultAppDeploy);

            ImmutableMap<String, AttachedNetwork> selfNetworks = selfContainerInfo.networkSettings().networks();
            if (selfNetworks == null || selfNetworks.isEmpty()) {
                logger.warn("[{}] has no network in common with {} ({} has no network)", id, selfContainerInfo.id(), selfContainerInfo.id());
                return;
            }

            ImmutableMap<String, AttachedNetwork> networks = containerInfo.networkSettings().networks();
            if (networks == null || networks.isEmpty()) {
                logger.error("[{}] has no network in common with {} ({} has no network)", id, selfContainerInfo.id(), id);
                return;
            }

            Set<String> selfNetworkNames = selfNetworks.keySet();
            String appHost = networks.entrySet().stream().filter(it -> selfNetworkNames.contains(it.getKey())).map(Map.Entry::getValue).map(AttachedNetwork::ipAddress).findFirst().orElse(null);
            if (StringUtils.isEmpty(appHost)) {
                logger.error("[{}] has no network in common with {} ({} vs {})", id, selfContainerInfo.id(), networks.keySet(), selfNetworkNames);
                return;
            }

            AppInfo appInfo = new AppInfo(id, glbctlHost, glbctlPort, appId, appHost, appPort, appDeploy);
            try (GlbCtl ctl = GlbCtlFactory.connect(resolve(appInfo.glbctlHost), appInfo.glbctlPort)) {
                ctl.update(new GlbNode(appInfo.appHost, appInfo.appPort), 1);
            } catch (Exception e) {
                logger.error("[{}] cannot be added to glbd through glbctl at {}:{}", appInfo.id, appInfo.glbctlHost, appInfo.glbctlPort, e);
                return;
            }

            appInfos.put(appInfo.id, appInfo);
            logger.info("[{}] {}:{} ({}) published to glbd through glbctl at {}:{}", appInfo.id, appInfo.appHost, appInfo.appPort, appInfo.appDeploy, appInfo.glbctlHost, appInfo.glbctlPort);

            appInfos.values().stream()
                    .filter(it -> it.appId.equals(appId))
                    .filter(it -> !it.appDeploy.equals(appDeploy))
                    .peek(it -> logger.info("[{}] {}:{} ({}) replaced by ({})", it.id, it.appHost, it.appPort, it.appDeploy, appDeploy))
                    .map(it -> it.id)
                    .collect(Collectors.toList())
                    .stream()
                    .peek(it -> logger.info("[{}] try start draining (because replaced)", it))
                    .forEach(this::drain);

        } catch (Exception e) {

            logger.error("[{}] error while publishing", id, e);
        }
    }

    public synchronized void drain(String id) {
        try {

            AppInfo appInfo = appInfos.get(id);
            if (appInfo == null) {
                logger.warn("[{}] cannot start draining, app not published", id);
                return;
            }

            try (GlbCtl ctl = GlbCtlFactory.connect(resolve(appInfo.glbctlHost), appInfo.glbctlPort)) {
                ctl.update(new GlbNode(appInfo.appHost, appInfo.appPort), 0);
            } catch (Exception e) {
                logger.error("[{}] {}:{} ({}) cannot start draining from glbd through glbctl at {}:{}", appInfo.id, appInfo.appHost, appInfo.appPort, appInfo.appDeploy, appInfo.glbctlHost, appInfo.glbctlPort, e);
                return;
            }

            logger.info("[{}] {}:{} ({}) draining started from glbd through glbctl at {}:{}", appInfo.id, appInfo.appHost, appInfo.appPort, appInfo.appDeploy, appInfo.glbctlHost, appInfo.glbctlPort);

        } catch (Exception e) {

            logger.error("[{}] error while starting draining", id, e);
        }
    }

    public synchronized void unpublish(String id) {
        try {

            AppInfo appInfo = appInfos.remove(id);
            if (appInfo == null) {
                logger.warn("{} cannot unpublish, app not published", id);
                return;
            }

            try (GlbCtl ctl = GlbCtlFactory.connect(resolve(appInfo.glbctlHost), appInfo.glbctlPort)) {
                ctl.update(new GlbNode(appInfo.appHost, appInfo.appPort), -1);
            } catch (Exception e) {
                logger.error("[{}] {}:{} ({}) cannot unpublish from glbd through glbctl at {}:{}", appInfo.id, appInfo.appHost, appInfo.appPort, appInfo.appDeploy, appInfo.glbctlHost, appInfo.glbctlPort, e);
                return;
            }

            logger.info("[{}] {}:{} ({}) unpublished from glbd through glbctl at {}:{}", appInfo.id, appInfo.appHost, appInfo.appPort, appInfo.appDeploy, appInfo.glbctlHost, appInfo.glbctlPort);

        } catch (Exception e) {

            logger.error("[{}] error while unpublishing", id, e);
        }
    }

    public synchronized void stop(String id) {
        try {

            dockerClient.stopContainer(id, 5);

            logger.info("[{}] stopped", id);

        } catch (ContainerNotFoundException | InterruptedException e) {

            logger.warn("[{}] cannot stop, container not found", id);

        } catch (Exception e) {

            logger.error("[{}] error stopping", id, e);
        }
    }

    @Scheduled(fixedRate = 5000)
    public synchronized void cleanupDrainedApps() {
        appInfos.values().stream()
                .flatMap(appInfo -> {
                    try (GlbCtl ctl = GlbCtlFactory.connect(resolve(appInfo.glbctlHost), appInfo.glbctlPort)) {
                        return ctl.getInfo().getRouter().entrySet().stream()
                                .filter(it -> it.getKey().getHost().equals(appInfo.appHost))
                                .filter(it -> it.getKey().getPort() == appInfo.appPort)
                                .map(Map.Entry::getValue)
                                .filter(it -> it.getWeight() <= 0.0)
                                .filter(it -> it.getConns() <= 0)
                                .peek(it -> logger.info("[{}] {}:{} ({}) drained (connections is now {})", appInfo.id, appInfo.appHost, appInfo.appPort, appInfo.appDeploy, it.getConns()))
                                .map(it -> appInfo.id);
                    } catch (Exception e) {
                        logger.error("[{}] error while trying to know if drained from glbd through glbctl at {}:{}", appInfo.id, appInfo.glbctlHost, appInfo.glbctlPort, e);
                        return Stream.empty();
                    }
                })
                .peek(it -> logger.info("[{}] try to stop (because drained)", it))
                .forEach(this::stop);
    }

    private static final class AppInfo {

        final String id;

        final String glbctlHost;
        final int glbctlPort;

        final String appId;
        final String appHost;
        final int appPort;
        final String appDeploy;

        public AppInfo(String id, String glbctlHost, int glbctlPort, String appId, String appHost, int appPort, String appDeploy) {
            this.id = id;
            this.glbctlHost = glbctlHost;
            this.glbctlPort = glbctlPort;
            this.appId = appId;
            this.appHost = appHost;
            this.appPort = appPort;
            this.appDeploy = appDeploy;
        }
    }
}
