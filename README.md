# openapi-visual-diff

A Swagger-UI-shaped visual diff of two OpenAPI specs — because everybody already
knows how to read Swagger UI, and nobody wants to read a nested `<ul>`.

It renders the **new** spec in real Swagger UI (from CDN), then:

- **fades out** every endpoint the diff didn't touch (hover to read one anyway),
- gives every impacted one a coloured spine + badge — 🔴 breaking, 🟠 modified,
  🟢 added, ⚪️ removed,
- prints the concrete changes under each operation summary, in oasdiff's words,
- grafts **removed** operations back in from the old spec (struck through), so a
  deleted endpoint still gets a row instead of silently vanishing,
- collects non-endpoint changes (components, servers, security) in a box on top.

The page follows the system theme — light or dark — and `?theme=dark` / `?theme=light`
pins it, which is what an embedding page uses when it wants the frame to match rather
than guess. Swagger UI ships light-only, so the dark side is a colour-only override
layer: every dimension, weight and radius stays Swagger UI's, and the page still reads
as the screen everyone already knows.

The toolbar counts double as filters: click a chip to hide that class of change.
`only touched endpoints` collapses the untouched noise entirely and is remembered
in the URL hash (`#only-touched`), so the filtered view is what a colleague sees
when you paste them the link.

## Usage

```bash
brew install oasdiff                      # the diff engine
pip3 install pyyaml                       # spec parsing
```

`oadiff` is the everyday entry point — run it from inside the repo and it finds
the spec, pulls both sides out of git, builds the report and opens it:

```bash
oadiff                      # spec at HEAD   vs the working copy
oadiff HEAD~5               # spec at HEAD~5 vs the working copy
oadiff HEAD~5 HEAD          # two commits
oadiff origin/main          # what this branch does to the API
oadiff old.yaml new.yaml    # two plain files
```

An argument that exists as a file is used as-is; anything else is treated as a
git revision. `-f <path>` picks the spec when auto-detection guesses wrong,
`-o <path>` sets the output, `-n` skips opening the browser.

The generator underneath takes two files and nothing else:

```bash
./openapi-visual-diff.py old.yaml new.yaml -o diff.html --label-old A --label-new B
```

The output is a single self-contained HTML file (Swagger UI itself comes from
cdnjs, so viewing it needs network; everything else is inlined).

## Why the spec is dereferenced

Swagger UI resolves `#/components/schemas/X` against the *page* URL. Opened from
disk that means fetching `file:///…/diff.html`, which the browser refuses — and
every `$ref` in the report turns into a red `Resolver error` wall. Handing it a
`blob:` or `data:` URL instead doesn't help either; the resolver can't build a
URL from those (`Failed to construct 'URL'`). So the generator expands every
internal `$ref` itself, keeping the schema name in `title` so the UI still shows
`OwnerDto` rather than an anonymous object, and cutting recursive references
with a stub. Swagger UI then has nothing left to look up.

## Why oasdiff underneath

`oasdiff changelog -f json` already does the hard part — semantic, per-operation
changes with a severity level (3 = breaking, 2 = warning, 1 = info). This tool is
purely the presentation layer over it. Which also means the breaking-change
verdict here is the same one `oasdiff breaking` would fail your build on.

## License

Released into the public domain under [The Unlicense](LICENSE).
