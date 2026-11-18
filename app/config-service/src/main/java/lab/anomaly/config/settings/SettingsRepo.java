package lab.anomaly.config.settings;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Writes to the settings repository for the Settings tab (add-settings-page S3): one change at a time (one lock), only
 * on top of the commit the page was shown (else "changed meanwhile"), never over uncommitted hand edits, files
 * replaced atomically, then one commit. Nothing is pushed.
 */
public class SettingsRepo {

    /** What a change writes: path → new text, or null to remove the file. */
    public record Change(Map<String, String> files, String message) {}

    private final Path repo;
    private final String label;
    private final ReentrantLock lock = new ReentrantLock();

    public SettingsRepo(Path repo, String label) {
        this.repo = repo;
        this.label = label;
    }

    public static SettingsRepo fromUri(String uri, String label) {
        return uri != null && uri.startsWith("file:") ? new SettingsRepo(Path.of(URI.create(uri.replace(" ", "%20"))), label) : null;
    }

    /** The last commit id of the label, or "" when there is none. */
    public String head() throws IOException {
        try (Git git = Git.open(repo.toFile())) {
            ObjectId id = git.getRepository().resolve("refs/heads/" + label);
            return id == null ? "" : id.name();
        }
    }

    /** A file as committed on the label, or null. */
    public String committed(String path) throws IOException {
        try (Git git = Git.open(repo.toFile())) {
            ObjectId id = git.getRepository().resolve("refs/heads/" + label);
            if (id == null) {
                return null;
            }
            try (RevWalk walk = new RevWalk(git.getRepository());
                 TreeWalk tree = TreeWalk.forPath(git.getRepository(), path, walk.parseCommit(id).getTree())) {
                return tree == null ? null : new String(git.getRepository().open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * Applies changes computed from the committed state, each its own commit, and answers the new head. The first
     * function sees the head it must build on; it may answer several changes (for example the one-time move, then the
     * page's change). {@code expectedHead} null skips the lost-update check (tests).
     */
    public String apply(String expectedHead, Function<SettingsRepo, List<Change>> changes) throws Exception {
        lock.lock();
        try (Git git = Git.open(repo.toFile())) {
            String branch = git.getRepository().getBranch();
            if (!label.equals(branch)) {
                throw new SettingsConflict("config-repo is on branch '" + branch + "', not '" + label + "'; switch back first");
            }
            String head = head();
            if (expectedHead != null && !expectedHead.equals(head)) {
                throw new SettingsConflict("the settings changed meanwhile; reload the page");
            }
            List<Change> list = changes.apply(this);
            Status status = git.status().call();
            Set<String> dirty = new HashSet<>();
            dirty.addAll(status.getModified());
            dirty.addAll(status.getChanged());
            dirty.addAll(status.getAdded());
            dirty.addAll(status.getRemoved());
            dirty.addAll(status.getMissing());
            dirty.addAll(status.getUntracked());
            Set<String> touched = new HashSet<>(List.of("regions.yml", "collector.yml"));
            list.forEach(c -> touched.addAll(c.files().keySet()));
            for (String p : touched) {
                if (dirty.contains(p)) {
                    throw new SettingsConflict(p + " has uncommitted edits in config-repo: commit or discard your edit first");
                }
            }
            for (Change c : list) {
                for (Map.Entry<String, String> f : c.files().entrySet()) {
                    Path target = repo.resolve(f.getKey()).normalize();
                    if (!target.startsWith(repo.normalize())) {
                        throw new SettingsConflict("not a settings path: " + f.getKey());
                    }
                    if (f.getValue() == null) {
                        if (Files.exists(target)) {
                            git.rm().addFilepattern(f.getKey()).call();
                        }
                    } else {
                        write(target, f.getValue());
                        git.add().addFilepattern(f.getKey()).call();
                    }
                }
                PersonIdent who = new PersonIdent("settings page", "settings-page@localhost");
                RevCommit commit = git.commit().setMessage(c.message()).setAuthor(who).setCommitter(who).call();
                head = commit.getId().name();
            }
            return head;
        } finally {
            lock.unlock();
        }
    }

    private static void write(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".settings-page.", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Helper for changes: one file. */
    public static Map<String, String> files(Object... pathThenText) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pathThenText.length; i += 2) {
            m.put((String) pathThenText[i], (String) pathThenText[i + 1]);
        }
        return m;
    }
}
