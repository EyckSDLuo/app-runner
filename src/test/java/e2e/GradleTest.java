package e2e;

import com.danielflower.apprunner.App;
import com.danielflower.apprunner.Config;
import com.danielflower.apprunner.runners.GradleRunnerTest;
import com.danielflower.apprunner.web.WebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import scaffolding.AppRepo;
import scaffolding.RestClient;

import java.io.File;
import java.util.HashMap;

import static com.danielflower.apprunner.FileSandbox.fullPath;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ContentResponseMatcher.equalTo;

public class GradleTest {
    private final String port = String.valueOf(WebServer.getAFreePort());
    private final String appRunnerUrl = "http://localhost:" + port;
    private final RestClient restClient = RestClient.create(appRunnerUrl);
    private final String appId = "gradle";
    private final AppRepo appRepo = AppRepo.create(appId);

    private final App app = new App(new Config(new HashMap<String,String>() {{
        put(Config.SERVER_HTTP_PORT, port);
        put(Config.DATA_DIR, fullPath(new File("target/datadirs/" + System.currentTimeMillis())));
    }}));

    @Before
    public void start() throws Exception {
        GradleRunnerTest.ignoreTestIfNotSupported();
        app.start();
    }

    @After
    public void shutdownApp() {
        app.shutdown();
    }

    @Test
    public void canCreateAnAppViaTheRestAPI() throws Exception {
        assertThat(restClient.createApp(appRepo.gitUrl()).getStatus(), is(201));
        assertThat(restClient.deploy(appId).getStatus(), is(200));
        assertThat(restClient.homepage(appId), is(equalTo(200, containsString("My Gradle App"))));
    }
}
