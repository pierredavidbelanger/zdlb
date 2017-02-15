package ca.pjer.zdlb;

import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@SpringBootApplication
@EnableScheduling
public class App {

    public static final String AUTOGLBD_DEFAULT_GLBCTL_HOST = "AUTOGLBD_DEFAULT_GLBCTL_HOST";
    public static final String AUTOGLBD_DEFAULT_GLBCTL_PORT = "AUTOGLBD_DEFAULT_GLBCTL_PORT";
    public static final String AUTOGLBD_DEFAULT_APP_PORT = "AUTOGLBD_DEFAULT_APP_PORT";

    public static final String ZDLB = "zdlb.enabled";
    public static final String ZDLB_GLBCTL_HOST = "zdlb.glbctl.host";
    public static final String ZDLB_GLBCTL_PORT = "zdlb.glbctl.port";
    public static final String ZDLB_APP_ID = "zdlb.app.id";
    public static final String ZDLB_APP_PORT = "zdlb.app.port";
    public static final String ZDLB_APP_DEPLOY = "zdlb.app.deploy";

    @Bean(destroyMethod = "close")
    DockerClient dockerClient() throws DockerCertificateException {
        return DefaultDockerClient.fromEnv().build();
    }

    @Bean(destroyMethod = "shutdown")
    Executor executor() {
        return Executors.newCachedThreadPool();
    }

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
