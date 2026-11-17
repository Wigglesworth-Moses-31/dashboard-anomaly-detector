package lab.anomaly.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** add-dashboard-json: committed dashboard files are served, uncommitted edits and other paths are not, spaces work. */
class DashboardFilesControllerTest {

    @Test
    void servesTheCommittedFileOnly(@TempDir Path tmp) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("a folder with spaces/config-repo"));
        Files.createDirectories(repo.resolve("dashboards/us"));
        Files.writeString(repo.resolve("dashboards/us/shop.json"), "{\"title\":\"v1\"}");
        Files.writeString(repo.resolve(".env"), "SECRET=x");
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern("dashboards").call();
            git.commit().setMessage("v1").setAuthor("t", "t@localhost").setCommitter("t", "t@localhost").setSign(false).call();
        }
        Files.writeString(repo.resolve("dashboards/us/shop.json"), "{\"title\":\"v2, not committed\"}");
        DashboardFilesController c = new DashboardFilesController(repo.toUri().toString(), "main");

        assertThat(c.file("/dashboards/us/shop.json").getBody()).isEqualTo("{\"title\":\"v1\"}");
        assertThat(c.file("/dashboards/us/missing.json").getStatusCode().value()).isEqualTo(404);
        assertThat(c.file("/dashboards/../.env").getStatusCode().value()).isEqualTo(400);
        assertThat(c.file("/.env").getStatusCode().value()).isEqualTo(400);
        assertThat(c.file("/collector.yml").getStatusCode().value()).isEqualTo(400);
    }
}
