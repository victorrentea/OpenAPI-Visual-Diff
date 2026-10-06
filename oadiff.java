///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6
//FILES openapi-visual-diff.py

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * should do the same as the {@code oadiff} bash script but works on systems where there is no bash but jbang and java instead 
 * Visueller OpenAPI-Diff direkt aus der Git-Historie, als HTML-Bericht im Stil von Swagger UI.
 *
 * <p>Ein Argument, das als Datei existiert, wird unverändert verwendet; jedes andere gilt als
 * Git-Revision. Ohne zweites Argument ist die neue Seite die Arbeitskopie.
 *
 * <p>Die Darstellung erzeugt {@code openapi-visual-diff.py}. JBang reicht den Pfad des Skripts nicht
 * an das Programm weiter, deshalb liegt die Python-Datei per {@code //FILES} im Klassenpfad und wird
 * zur Laufzeit in das Temp-Verzeichnis geschrieben — so läuft das Skript aus jedem Verzeichnis.
 * 
 * see also https://github.com/victorrentea/OpenAPI-Visual-Diff/oadiff bash script
 */
@Command(
    name = "oadiff",
    mixinStandardHelpOptions = true,
    sortOptions = false,
    description = "Visual OpenAPI diff straight out of git history.",
    footer = {
      "",
      "Examples:",
      "  oadiff.java                      spec at HEAD   vs the working copy",
      "  oadiff.java HEAD~5               spec at HEAD~5 vs the working copy",
      "  oadiff.java HEAD~5 HEAD          two commits",
      "  oadiff.java origin/main          what this branch does to the API",
      "  oadiff.java old.yaml new.yaml    two plain files"
    })
public class oadiff implements Callable<Integer> {

  private static final String GENERATOR = "openapi-visual-diff.py";
  private static final List<String> SPEC_CANDIDATES =
      List.of(
          "api/openapi.yaml",
          "openapi.yaml",
          "openapi.yml",
          "openapi.json",
          "docs/openapi.yaml",
          "src/main/resources/openapi.yaml");

  @Parameters(
      index = "0",
      arity = "0..1",
      paramLabel = "OLD",
      description = "Spec file or git revision. Default: HEAD.")
  String oldSide = "HEAD";

  @Parameters(
      index = "1",
      arity = "0..1",
      paramLabel = "NEW",
      description = "Spec file or git revision. Default: the working copy.")
  String newSide;

  @Option(
      names = {"-f", "--spec"},
      description = "Spec file relative to the repo root. Default: auto-detected (api/openapi.yaml).")
  String spec;

  @Option(
      names = {"-o", "--out"},
      description = "Output HTML. Default: a temp file.")
  Path out;

  @Option(
      names = {"-n", "--no-open"},
      description = "Don't open the result in the browser.")
  boolean noOpen;

  @Option(
      names = "--python",
      description =
          "Python interpreter with PyYAML. Default: <repo>/.venv if present, then python3,"
              + " python, py -3.")
  String python;

  private Path repoRoot;

  public static void main(String[] args) {
    System.exit(new CommandLine(new oadiff()).execute(args));
  }

  @Override
  public Integer call() throws Exception {
    try {
      requireOasdiff();
      List<String> pythonCommand = findPython();
      Path tmp = Files.createTempDirectory("oadiff-");
      try {
        Path oldFile = tmp.resolve("old.yaml");
        String oldLabel = materialise(oldSide, oldFile);

        Path newFile = tmp.resolve("new.yaml");
        String newLabel;
        if (newSide == null) {
          Path workingCopy = repoRoot().resolve(spec());
          if (!Files.isRegularFile(workingCopy)) {
            throw new Failure("no working copy of '" + spec() + "'");
          }
          Files.copy(workingCopy, newFile);
          newLabel = "working copy";
        } else {
          newLabel = materialise(newSide, newFile);
        }

        Path report = out != null ? out : Files.createTempFile("openapi-diff-", ".html");
        Path generator = extractGenerator(tmp);
        List<String> command = new ArrayList<>(pythonCommand);
        command.addAll(
            List.of(
                generator.toString(),
                oldFile.toString(),
                newFile.toString(),
                "-o",
                report.toString(),
                "--label-old",
                oldLabel,
                "--label-new",
                newLabel));
        int exit = new ProcessBuilder(command).inheritIO().start().waitFor();
        if (exit != 0) {
          return exit;
        }
        if (!noOpen) {
          openInBrowser(report);
        }
        return 0;
      } finally {
        deleteRecursively(tmp);
      }
    } catch (Failure failure) {
      System.err.println("oadiff: " + failure.getMessage());
      return 1;
    }
  }

  /** Eine existierende Datei wird kopiert, alles andere per {@code git show} aufgelöst. */
  private String materialise(String arg, Path target) throws IOException, InterruptedException {
    Path asFile = Path.of(arg);
    if (Files.isRegularFile(asFile)) {
      Files.copy(asFile, target);
      return asFile.getFileName().toString();
    }
    String gitPath = spec().replace('\\', '/');
    Process show =
        new ProcessBuilder("git", "show", arg + ":" + gitPath)
            .directory(repoRoot().toFile())
            .redirectOutput(target.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (show.waitFor() != 0) {
      throw new Failure("no '" + gitPath + "' at revision '" + arg + "'");
    }
    return arg;
  }

  private String spec() throws IOException, InterruptedException {
    if (spec == null) {
      spec = detectSpec().orElseThrow(() -> new Failure("can't find the spec — pass -f <path>"));
    }
    return spec;
  }

  private Optional<String> detectSpec() throws IOException, InterruptedException {
    Path root = repoRoot();
    for (String candidate : SPEC_CANDIDATES) {
      if (Files.isRegularFile(root.resolve(candidate))) {
        return Optional.of(candidate);
      }
    }
    List<String> tracked =
        run(root, "git", "ls-files", "*openapi*.yaml", "*openapi*.yml", "*openapi*.json");
    return tracked.size() == 1 ? Optional.of(tracked.get(0)) : Optional.empty();
  }

  private Path repoRoot() throws IOException, InterruptedException {
    if (repoRoot == null) {
      List<String> lines = run(Path.of("."), "git", "rev-parse", "--show-toplevel");
      if (lines.isEmpty()) {
        throw new Failure("not inside a git repository");
      }
      repoRoot = Path.of(lines.get(0));
    }
    return repoRoot;
  }

  private static void requireOasdiff() throws IOException, InterruptedException {
    if (!succeeds(List.of("oasdiff", "--version"))) {
      throw new Failure(
          "oasdiff not found — brew install oasdiff, or see https://github.com/oasdiff/oasdiff");
    }
  }

  private List<String> findPython() throws IOException, InterruptedException {
    List<List<String>> candidates = new ArrayList<>();
    if (python != null) {
      candidates.add(List.of(python.split(" ")));
    } else {
      for (String venvPython : List.of(".venv/bin/python", ".venv/Scripts/python.exe")) {
        Path path = repoRoot().resolve(venvPython);
        if (Files.isExecutable(path)) {
          candidates.add(List.of(path.toString()));
        }
      }
      candidates.addAll(List.of(List.of("python3"), List.of("python"), List.of("py", "-3")));
    }
    for (List<String> candidate : candidates) {
      List<String> probe = new ArrayList<>(candidate);
      probe.addAll(List.of("-c", "import yaml"));
      if (succeeds(probe)) {
        return candidate;
      }
    }
    throw new Failure(
        "no Python with PyYAML found (tried "
            + candidates.stream().map(c -> String.join(" ", c)).toList()
            + ") — pip3 install pyyaml, or pass --python <interpreter>");
  }

  private static Path extractGenerator(Path dir) throws IOException {
    Path target = dir.resolve(GENERATOR);
    try (InputStream in = oadiff.class.getClassLoader().getResourceAsStream(GENERATOR)) {
      if (in == null) {
        throw new Failure(GENERATOR + " is missing from the JBang build");
      }
      Files.copy(in, target);
    }
    return target;
  }

  private static void openInBrowser(Path report) throws IOException {
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    String file = report.toAbsolutePath().toString();
    List<String> command;
    if (os.contains("win")) {
      command = List.of("rundll32", "url.dll,FileProtocolHandler", file);
    } else if (os.contains("mac")) {
      command = List.of("open", file);
    } else {
      command = List.of("xdg-open", file);
    }
    new ProcessBuilder(command).inheritIO().start();
  }

  private static boolean succeeds(List<String> command) throws InterruptedException {
    try {
      Process process =
          new ProcessBuilder(command)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      return process.waitFor() == 0;
    } catch (IOException notOnPath) {
      return false;
    }
  }

  private static List<String> run(Path dir, String... command)
      throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) {
      return List.of();
    }
    return output.lines().filter(line -> !line.isBlank()).toList();
  }

  private static void deleteRecursively(Path dir) throws IOException {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static final class Failure extends RuntimeException {
    Failure(String message) {
      super(message);
    }
  }
}
