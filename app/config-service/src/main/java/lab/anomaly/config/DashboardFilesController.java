package lab.anomaly.config;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the dashboard JSON files of the settings repository as they are <b>committed</b> on the label (add-dashboard-json
 * design J2). Spring Cloud Config's own plain-file endpoint answered 404 when the project path contains spaces (it
 * compares an encoded and a raw path), so this small endpoint reads the file from the commit with JGit instead.
 *
 * <p>Only {@code dashboards/**.json} paths are served, never {@code ..}, so nothing else (keys, other files) can be read.
 * Cost: one tree walk along the path, O(path depth).
 */
@RestController
class DashboardFilesController {

    private static final Pattern ALLOWED = Pattern.compile("^dashboards/[A-Za-z0-9._\\-]+(/[A-Za-z0-9._\\-]+)*\\.json$");

    private final Path repo;
    private final String label;

    DashboardFilesController(@Value("${spring.cloud.config.server.git.uri}") String uri,
                             @Value("${spring.cloud.config.server.git.default-label:main}") String label) {
        this.repo = uri.startsWith("file:") ? Path.of(URI.create(uri.replace(" ", "%20"))) : null;
        this.label = label;
    }

    @GetMapping(value = "/files/{*path}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> file(@PathVariable String path) throws IOException {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (repo == null || p.contains("..") || !ALLOWED.matcher(p).matches()) {
            return ResponseEntity.badRequest().build();
        }
        String text = read(repo, label, p);
        return text == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(text);
    }

    /** The file at {@code path} in the last commit of {@code label}, or null. */
    static String read(Path repo, String label, String path) throws IOException {
        try (Repository git = new FileRepositoryBuilder().setWorkTree(repo.toFile()).setGitDir(repo.resolve(".git").toFile()).build()) {
            ObjectId head = git.resolve("refs/heads/" + label);
            if (head == null) {
                return null;
            }
            try (RevWalk walk = new RevWalk(git); TreeWalk tree = TreeWalk.forPath(git, path, walk.parseCommit(head).getTree())) {
                return tree == null ? null : new String(git.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
