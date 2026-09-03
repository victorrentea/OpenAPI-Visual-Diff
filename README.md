# openapi-visual-diff

A Swagger-UI-shaped visual diff of two OpenAPI specs — because everybody already
knows how to read Swagger UI, and nobody wants to read a nested `<ul>`.

It renders the **new** spec in real Swagger UI (from CDN), then:

- **fades out** every endpoint the diff didn't touch (hover to read one anyway),
- gives every impacted one a coloured spine + badge — 🔴 breaking, 🟠 modified,
  🟢 added, ⚪️ removed,
- prints the concrete changes under each operation summary, in oasdiff's words,
- **`expand impacted` opens the way down to each changed field**, not just the
  operation: it walks the response (or request) schema through every array and
  object between the root and the property oasdiff named, and highlights the leaf
  where it lands. A field added to a schema four levels down — `items → pets →
  items → visits → items → vetId` — is otherwise reachable only by opening the
  operation, switching to the *Schema* tab and expanding four nodes by hand, which
  is exactly how a change goes unreviewed. Siblings stay collapsed: it opens the
  path, not the world. Anything it cannot open — a removed property, a chain past
  its depth bound — is counted in a line under the operation rather than passed
  over in silence,
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

The generator underneath takes two files, or a git revision to diff the working tree
against — the form a review pipeline calls:

```bash
./openapi-visual-diff.py old.yaml new.yaml -o diff.html --label-old A --label-new B
./openapi-visual-diff.py --base "$MERGE_BASE" --spec openapi.yaml -o diff.html
```

With `--base`, a spec that did not exist at that revision is treated as an empty one, not
as a crash: a branch that introduces the API renders as one big "added".

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

## How `expand impacted` finds a field it has never rendered

oasdiff names the property it changed as a slash path in its own prose —
`items/pets/items/visits/items/vetId`, where `items` is an array descent and
anything else is an object property. The generator resolves that path against the
already-dereferenced schema and ships the page a list of steps to walk, so the
ancestor chain is known **before** anything renders. Nothing is discovered by
expanding and looking: Swagger UI rebuilds a collapsed subtree from scratch, so
state read out of the rendered tree is gone the moment it re-renders.

Two things make this fussier than it sounds. Swagger UI renders a collapsed node
as an empty `<div>` — the children do not exist in the DOM until the parent is
open — so ancestors are opened strictly in order and each step *waits* for the
node it asked for. And Swagger UI has **two** schema renderers: a 3.1 spec gets
the JSON-Schema-2020-12 tree of `<article>`s, a 3.0 spec gets the older
`<span class="model">` boxes. Same page, same Swagger UI, entirely different DOM,
so each gets its own walker and the root decides which is in play.

A path is only walked when it resolves against the real schema. A property that is
*gone* from the revision being rendered has nowhere to point, a chain longer than
12 steps is refused whole rather than walked halfway, and no more than 12 leaves
per operation are opened (most severe first). Every field left shut is counted in
a line under the operation — a tree that quietly opens 12 of 30 fields is the same
lie as one that opens none, only harder to notice.

## Why oasdiff underneath

`oasdiff changelog -f json` already does the hard part — semantic, per-operation
changes with a severity level (3 = breaking, 2 = warning, 1 = info). This tool is
purely the presentation layer over it. Which also means the breaking-change
verdict here is the same one `oasdiff breaking` would fail your build on.

## License

Released into the public domain under [The Unlicense](LICENSE).
