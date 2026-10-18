# CS2 script editor

Decompiles RuneScape clientscripts (cache index 12) into TypeScript, recompiles that
TypeScript back into bytecode, and runs it against a live interface.

The toolchain itself lives in `cs2/src/main/kotlin/org/drynor/cs2/` - its own Gradle module,
headless and depending only on `:core`. The CLI (`Cs2Cli.kt`, still
`tools/src/main/kotlin/org/drynor/tools/cs2/`) and the interface editor
(`tools/.../rsinterface/`) stay in `tools`, which is where LWJGL, GLFW and ImGui come in.

The split exists because the server compiles clientscripts too: index 12 of a cache source tree
is stored as exactly the TypeScript `cs2 decompile-all` writes (`docs/cache-source.md`), and
rebuilding a cache must not link a windowing toolkit. `org.drynor.cs2.Cs2Compiler` is the entry
point for that - see *Compiling from server code* below.

## Commands

All of these run through the tools CLI:

```bash
./gradlew :tools:run --args="cs2 <sub-command>"
```

| Sub-command | What it does |
|---|---|
| `decompile <id>` | Prints one script as TypeScript, then its dead code |
| `decompile-all [dir]` | Writes every script, the declarations, `tsconfig.json` and `dead-code.md`; the folder defaults to `cs2-dump`. This is the cache source tree's `clientscripts/` layout |
| `roundtrip [n]` | The stability check (see *Fidelity*) over every script |
| `fixpoint <id> [dir]` | The same checks for one script, writing both sources out to diff |
| `types [OP:slot]` | What the corpus says constants mean; with a slot, why it says so |
| `diff <id>` | Shows where one script's re-compiled bytecode diverges |
| `verify` | Decodes and re-encodes every script, asserting byte identity |
| `analyse` | Stack-simulates every script; confirms every opcode's stack effect balances |
| `dump <id>` / `info <id>` | Raw instruction listing / header |
| `run <id> [args]` | Executes a script on the VM against a no-op host |
| `interface <id>` | Runs one interface's load scripts and reports what changed |
| `interfaces` | Runs every interface's load scripts |
| `declarations [dir]` | Writes just the ambient TypeScript declarations, `StringParam` included |
| `export <dir> [id]` | Decompiles one script into a folder |
| `compile <dir> <id>` | Compiles an edited script from disk and compares it to the cache |
| `sources <dir>` | Compiles every source file in a folder |
| `watch [dir] [secs]` | Hot-reloads scripts as they are saved |
| `apply-names` / `sync-names` / `check-names` | Reconcile a folder's names with `gamevals/*.json` |

## Pipeline

```
cache bytes ──Cs2Codec──▶ instructions ──Cs2Lifter──▶ expressions
                                              │
                                     Cs2Structurer (if/else, while, switch)
                                              │
                                       Cs2Cleanup (dead code, declarations)
                                              │
                                        Cs2Types (what constants mean)
                                              │
                                        Cs2Emitter ──▶ TypeScript
                                                            │
                                    Cs2Parser ──▶ IR ──▶ Cs2CodeGen
                                                            │
                                                    Cs2Codec ──▶ cache bytes
```

`Cs2Analyzer` sits alongside it, resolving what the bytecode does not state outright: every
script's return signature (by fixpoint across calls, with a search for the recursive ones),
which param ids yield strings and what their type characters are (decoded from the cache it was
handed, not from `Cache.get()`), confirmation that the opcode table's stack effects balance, and
the cached corpus-wide `Cs2Types` analysis. `Cs2SourceContext` is the same interface answered from
a decompiled folder instead - see *Compiling from server code*.

`Cs2Structurer` recovers a condition from the graph of test blocks between it and its body:
the run of pure tests and lone jumps with exactly two ways out - into the body, or past it -
reads back as one `&&`/`||` expression, which is what leaves the corpus with no `goto` at all.

## The opcode table

`Cs2Op.kt` is the table: one enum entry per opcode with its id, TypeScript name, operand size,
stack effect, hook trigger flag and argument types. It is edited by hand, and a new or corrected
opcode is a change to its entry.

The names are Jagex's 727 names, checked against zwyz's list
<https://raw.githubusercontent.com/zwyz/rs3-cache/refs/heads/master/data/opcodes-727-309.txt>;
the client enum's names are not trusted, and a good number of them sit on the wrong opcode. An
opcode the list does not cover is named from what its handler does. The stack effects come from
reading the opcode's handler in the client. The TypeScript name is the camelCase split of the name,
with `Op` appended where it would be a TypeScript keyword or a clientscript's own name
(`userflowflagsOp`).

A rename changes every call site in a cache source tree's `clientscripts/`: rewrite the calls over
the token stream, applying the whole old-to-new map at once so names that trade places swap rather
than collapse, and prove the tree compiles to the same bytes before and after.

## What the decompiler changes, and what it never changes

The bytecode a script re-compiles to no longer has to be the bytes the cache holds. What may
differ is layout and dead code:

- **The trailing implicit return is dropped** when nothing can reach it; the code generator
  writes a fresh one, the zero of each declared return type.
- **Def-initialiser stores are folded into declarations.** `int1 = 0; ... [int1, ...] = f()`
  becomes `let [int1, ...] = f()` where nothing reads the zero and the later store can carry
  the declaration.
- **Local slots are handed out in declaration order**, so a local declared where it is first
  written can end up in a different slot than the cache gave it.
- **Branches and switches are laid out canonically**: a comparison enters its body over a
  single unconditional branch, and `default:` is reached by a jump and written last.
- **Unreachable code is removed** from the source and reported instead.

What never changes is what the script does. `cs2 roundtrip` proves that for every script
(see *Fidelity*); 5501 of the 6567 still compile to the cache's exact bytes anyway.

## What the emitted TypeScript means

Some of the output is shaped by what has to compile back, not by taste:

- **`let x: T = v;`** compiles to a push and a store like any assignment - it is a
  declaration only in the sense that it introduces the name. **`let x: T;`** reserves the
  slot and produces no instructions; that is where every local the body does not introduce
  itself is declared. An older dump whose header reads `let intN: number = 0;` therefore now
  compiles to an extra store per local - re-run `decompile-all` to refresh it.
- **`let [a, b] = f();`** and **`[a, b] = f();`** put a call's results straight into locals or
  variables; the code generator stores them back to front, which is the order the stacks pop
  in. `const [tmpInt0, ...] = f();` is what is left when the results are not consumed that way.
- **`f(a, ...g(), b)`** is a call that happens between two of another call's arguments. Named
  temporaries cannot say that: the statement binding them would move the call ahead of the
  arguments pushed before it.
- **`[a, b] = [x, y]`** is a parallel assignment: CS2 computes both values before storing
  either, which is how it compiles assignments that read each other.
- **Hook callbacks carry their type spec**: `hook(script44, "IIissoioioioi", [args], [triggers])`.
  Those characters are the compiler's own types - `I` component, `o` obj, `s` string - and the
  client only inspects `s`, so reconstructing them would lose information.
- **An opcode's own operand rides as a type argument**: `ccCreate<1>(...)`. Several component
  opcodes use it to pick which active-component register they act on.
- **`while (true)`** is a loop whose test sits part way through the body, written out as
  `if (!c) break;`. No script in this revision has one; the form exists for hand-written
  sources.
- **`goto(...)`** appears where control flow has no structured form. No script in the 727
  cache needs one any more.

## Types

CS2 records no types: an obj id, a colour and a component hash are all plain integers.
`cs2 types` works out what they are from how they are used, across all 6567 scripts at once,
and the decompiler writes them out.

- A symbol - a script parameter, a return slot, a variable, a local, an opcode result or
  argument slot - takes a kind only when everything flowing through it agrees. Two kinds mean
  it is used for two things, and it drops back to a plain number.
- The evidence is the opcode table's own argument types, hook format characters, the type
  codes `enumOp` and its relatives are given, the cache's param definitions, and values
  passed between scripts. Nothing is guessed from a constant's value.
- Opcode results and untyped argument slots are *learned* from the corpus: three or more
  agreeing uses from at least two scripts, at least one of them a value that something typed
  where it stands. `cs2 types <OP:slot>` prints that evidence.
- The type characters are checked against the corpus before they are used, and one that is
  ever contradicted is dropped. `i` (plain int) claims nothing, so it is ignored.
- A value compared for order against a real threshold is a quantity, never an id; see below.

Constants are then written as what they are. An id goes through its gameval constant and
nothing else: `Obj.staff_of_air`, `Component.wornitems.head`, `Graphic.verdana_11pt_regular`,
`Param.wear_op1`, and a clientscript referred to by id reads as its own function name. Ids
with no proven name yet use their catalog placeholder - `Struct.struct_1234`,
`Component.interface_978.component_978_36` - exactly as scripts (`cs2_1620`) and variables
(`varc_157`) already do: a placeholder is a name an editor can rename and `cs2 sync-names`
can write back to the catalog, and a bare number is not.

Only two kinds are still written through a call, having no name to carry them:
`colour(0xFF0000)` and `coord(x, y, level)`. Everything else is bare - `true` / `false` for a
flag, `type_obj` for a type code, `event_com` for a value the client substitutes, and a plain
number for anything no catalog covers: `-1`, `Int.MAX_VALUE`, a threshold like `100000`. A
wrapper never goes round a number that is not an id.

The names are Jagex's, which is what the catalogs are named after: `obj` (not "item"),
`coord` (whose third field is the level, not a plane), `stat`, `colour`, `boolean`. Signatures
and declarations use them as type aliases - `intArg0: obj`, `let int3: component = ...`,
`function f(): [coord, number]`. `enum` and `interface` are TypeScript keywords and cannot be
type names, so those two aliases are `Enum` and `Interface`; `boolean` is TypeScript's own
type and is used as it stands. Every alias is `number`.

`gamevals.d.ts` declares all of them - 326 329 lines, 18 MB - with each id in the doc comment
above its constant. It costs nothing to check: `tsc` reads the whole dump, 6570 files and
569 000 lines, in under two seconds.

### Event values

A hook is bound long before it runs, so the values that only exist when it fires are written
into its arguments as magic numbers and substituted on dispatch. They read as the client's own
names, and `cs2.d.ts` declares them with the value in the doc comment:

`event_mousex`, `event_mousey`, `event_com` (the source component), `event_opindex`,
`event_comsubid` (the slot within it), `event_com2` (the target component), `event_comsubid2`,
`event_keycode`, `event_keychar` - and the string `"event_opbase"`, which is already literal.

### Ids are never ordered

Nothing asks whether one obj is less than another. A value compared for order (`<`, `>`, `<=`,
`>=`) against a real threshold - anything but 0 or -1, which is how everything is tested for
being set at all - is a count, a level or a timer, and can never be an id. That marks the
carrier a quantity, which conflicts with every kind it might otherwise have picked up, and is
what keeps `if (int20 > 100000)` from claiming that `100000` is an obj.

## Dead code

`decompile-all` writes `dead-code.md` into the folder (the repository's own copy of it is
`docs/detected-cs2-dead-code.md`): everything in the bytecode that cannot affect what a script
does, grouped by script, with the code itself.

| Kind | What it means |
|---|---|
| `unreachable` | Nothing can reach it. Dropped from the source, reported here. |
| `dead store` | The value is overwritten before anything reads it. Kept in the source. |
| `unused result` | A call runs for its effect and its results are never read. |
| `constant condition` | Both sides of a test are constants. |
| `empty branch` | An `if` with nothing in it, or with only an else. |

A constant written to a local before anything else writes it is the declaration the source
had, and is not reported. Neither is the default `return` every script ends with, nor an
unreachable block that only jumps somewhere.

## Fidelity

`cs2 roundtrip` is the contract. For every script it runs
`bytes0 -> src1 -> bytes1 -> src2 -> bytes2` and requires all of:

- **`bytes2 == bytes1`** - compiling the decompiled source is a fixed point;
- **`src2 == src1`** - decompiling the rebuilt bytecode gives the same source back;
- **the rebuild simulates** - `Cs2Analyzer.validate` finds no stack fault;
- **the work census matches** - the multiset of reachable instructions, jumps and local slot
  numbers aside, equals the original's minus the declarations that were folded away;
- **both versions behave the same** - each runs on `Cs2Vm` against a host whose answers are
  derived from what it was asked, so the two runs take the same branches or visibly do not.
  Several argument vectors are tried, and calls into other scripts go to the cached originals.

It also reports how many scripts are still byte-identical to the cache. That is
informational: byte identity is no longer required, behaviour is.

Where the structurer cannot reproduce a script's shape, the decompiler falls back to a literal
block-by-block rendering - labels and explicit jumps - which keeps every block and so keeps
the bytes exactly.

The emitted TypeScript also has to type-check, which constrains the format further: scripts
are not `export`ed (clientscripts share one flat namespace), parameter and return order follow
the order values are pushed rather than the grouped stack counts, and opcodes drawing from
more than one stack take widened parameter types because their argument order belongs to each
call site rather than to the opcode.

## The interface editor

`tools interface <id>` opens the editor: the interface renders the way the client draws it,
with a component tree, live property editing, click-to-pick selection, and a CS2 panel that
lists every hook and fires it on demand. F2 and F3 toggle the panels.

Rendering lives in `tools/.../rsinterface/`. `ComponentState` is the mutable per-component
state scripts drive; the fields that affect layout have private setters, so a change cannot
skip the relayout. `InterfaceCs2Host` answers component opcodes against that state, and
`Cs2HookDispatcher` substitutes the client's event placeholders - mouse position, source
component, slot, clicked option, typed key - into a hook's bound arguments before running it.

Both kinds of hook are listed: the ones baked into the component definitions, and the ones
scripts bind at runtime with `IF_SETON*` / `CC_SETON*`.

## Live source mode

The editor can read a **cache source tree** directly, so a change to a `.ts` or a component JSON
shows up in the render without packing a cache. This is the fast loop; a pack is what ships it.

```bash
tools interface 499 --source cache-source
```

`--source` is optional: a `cache-source/` beside the working directory is used automatically, and
`--source none` reads only the packed cache. The stage's status line says which
(`live: cache-source, 12 reloads, last 0.4 s ago`), as does the top of the CS2 Hot Reload panel.
In the GUI, **File > Open cache-source tree...** and **File > Use the packed cache** switch at any
time.

What comes from where:

| asset | source |
|---|---|
| the interface's components | `<tree>/interfaces/<id>/*.json`, through index 3's own tree codec |
| clientscripts | `<tree>/clientscripts/<category>/<name>.ts`, through `Cs2Compiler` |
| graphics, fonts, models, enums, structs, params | the packed cache the viewer opened |

The tree half goes through exactly what a build does - `SourceCodecs.codec(Index.INTERFACES).pack`
then the cache's own `InterfaceDecoder` - so a component reads the way the packed cache would read
it, pristine sidecars included. **Nothing is written to the tree or to the cache.**

Opening an interface compiles from the tree every script the interface can reach: its hooks, and
everything those call, walked over `GOSUB` targets. Without that a fresh session would run the
*cache's* bytecode for anything nobody had touched yet, and an edit made before launch would be
invisible.

### Watching

Both halves are watched. A saved `.ts` recompiles and swaps in ([Cs2HotReload]); a saved component
JSON re-reads the interface and re-runs its load scripts. A compile error never disturbs the
render - the last good version stays live and the failure is listed in the panel's Errors as
`file:line:col: message`, with a button that opens it there. **F7** re-reads everything on demand.

### Driving the interface

Most interfaces draw nothing without game state. The **State** panel supplies it:

- **Variables** by their `vars.d.ts` spelling - `varbit_skill_guide_skill_v2`, `varp_1249`,
  `varcstr_x` - or by id (`varbit_3288`). Writing one fires the transmit hooks, exactly as a
  client varp write would.
- **Stats**: one level per stat id, plus an "every stat" default for the unset ones.
- **Run after every load**: clientscripts by name (`cs2_23`, `cs2_23:1,2`), re-run after every
  load-script pass, since a reload re-seeds every component and would otherwise throw away what
  the driving script did.

The same three from the command line, which with `--capture` is a whole headless render:

```bash
tools interface 499 --source cache-source \
    --var varbit_skill_guide_skill_v2=11 --var varbit_skill_guide_subsection_v2=4 \
    --stat all=99 --run cs2_23 --capture /tmp/guide.png --settle 30
```

`--capture` and `--settle` set `-Ddrynor.capture` / `-Ddrynor.captureAfter`, so this works under
`tools/capture-headless.sh` with no GUI interaction.

## Running scripts

`Cs2Vm` implements the core instruction set — the three operand stacks, locals, the global
arrays, branches, switches and calls — and hands every other opcode to a `Cs2Host`. The host
returns results shaped to the opcode's signature, so the stacks stay correct even for
opcodes it does not implement. That seam is what the interface editor plugs into.

## Compiling from server code

`:cs2` is a headless Gradle module: `implementation(project(":core"))` and nothing else. Nothing
in it imports LWJGL, GLFW, ImGui or JOML, and nothing in it depends on `:tools`. That is what
lets the cache source tree packer compile index 12 on a server with no display.

```kotlin
val compiler = Cs2Compiler.fromSources(File(tree, "clientscripts"))

val id = compiler.scriptIdOf(file)        // the file's // [proc,quicksort] header, through the catalog
val bytes = compiler.compile(file)        // one .ts file -> index 12 bytes
val source = compiler.sourceFileOf(id)    // and back the other way, or null
```

**No cache is involved.** Compiling one file needs facts about the *others* - a call site discards
exactly what its callee leaves on the stacks, and a `*_PARAM` opcode pushes an int or a string
according to the param's declared type - and a decompiled folder states every one of them:

| what a compile has to know | where the folder says it |
|---|---|
| what script *n* returns, in push order | its own `function ...(): [number, string]` |
| how many arguments it takes, on which stacks | its own parameter list |
| whether param *n* yields a string | `cs2.d.ts`'s `StringParam` |
| which id a renamed identifier means | `vars.d.ts`, `gamevals.d.ts`, the script headers |

`Cs2SourceContext.read(folder)` is that reading, and it is the same `Cs2Context` interface
`Cs2Analyzer` implements, so nothing downstream can tell which one it got. It lexes each script's
declaration line and nothing else - parsing a body would be circular, since parsing is what needs
the answers - which is 0.3 s for all 6,569 signatures against about 7 s for the corpus analysis.

`Cs2Compiler(cache, folder)` is the other construction, for a tool that is *also* decompiling out
of a cache: it analyses the corpus instead, which is what recovers those same facts from bytecode
in the first place. Either way the work happens once, lazily, and is shared; each `compile` then
builds its own parser and code generator and keeps no state, so a pack can run the folder's files
in parallel. Compiling one file never requires the rest of the folder to compile; a file that
fails throws `Cs2CompileException`, whose message is `file:line:col: what went wrong`.

Under the facade the pipeline is public and can be driven directly:

| entry point | what it is |
|---|---|
| `Cs2SourceContext.read(folder)` | the folder's own answers: signatures and `cs2.d.ts`'s param types, no cache |
| `Cs2Analyzer(cache).analyse()` | the same answers from bytecode: return signatures by fixpoint, param types, opcode table check |
| `Cs2Corpus(cache)` | the whole corpus decompiled into a folder: `decompile`, `decompileScript`, `projectFiles`, `write` |
| `Cs2SourceProject(context, folder)` | the folder: paths, symbol table, `compileFile`; `(cache, analyzer, folder)` adds `verify` and `exportSource` |
| `Cs2Parser.signature(source)` | one script's declaration - name, arguments, returns - without parsing its body |
| `Cs2Parser` -> `Cs2CodeGen` -> `Cs2Codec.encode` | the compile path itself, one file at a time |
| `Cs2HotReload.sweepFolder(...)` | the whole-folder sweep `cs2 sources` / `cs2 verify-sources` run |
| `verifyCodecRoundTrip(cache)` | decode + re-encode every script, asserting byte identity |

### `StringParam` in `cs2.d.ts`

The param types are the one thing a folder cannot derive from its own scripts, so
`decompile-all` and the tree unpacker write them into `cs2.d.ts` as a type alias:

```ts
type StringParam =
    | 528
    | 529;
```

They come from the cache's param definitions (`type: "s"`) and, for the ids those do not declare,
from the corpus solver - 131 of the 727 cache's 2,583 params, none of them solver-derived. A param
declared by hand belongs in the list; the ids may be in any order, and dropping one compiles its
lookups as ints. A folder written before the declaration existed carries no such line at all, which
is a different thing from carrying an empty one: nothing fails - no id yields a string, which is
what the compiler assumes without an answer, and no script in the 727 corpus compiles differently
either way - but a hand-edited script reading a string param would be compiled against the wrong
stack. `tools cs2 declarations <dir>` (or a re-unpack) fixes such a folder, and
`Cs2SourceContext.declaresStringParams` is how it is told apart.

## The cache source tree

Index 12 of a cache source tree (`docs/cache-source.md`) *is* one of these folders. `clientscripts/`
holds exactly what `decompile-all` writes - `<category>/<name>.ts` per script plus `cs2.d.ts`,
`gamevals.d.ts`, `vars.d.ts`, `tsconfig.json` and `dead-code.md` - so editing a clientscript means
editing the TypeScript in the tree, and the server compiles it back when it rebuilds the cache.
Open `<tree>/clientscripts` in an editor and every command here works on it as it stands.

`org.drynor.cs2.source.Cs2SourceCodec` is the two directions. It claims index 12 by being on the
classpath (a `SourceCodecProvider` in `META-INF/services/`), so nothing has to register it.

- **Unpacking** decompiles each script and writes its `.ts`, and nothing else - no sidecar, no
  guard hash in `index.json`.
- **Packing** compiles every script from its source, always. 5,504 of the 6,569 come out as the
  cache's own bytes; the other 1,065 are the ones the decompiler normalises and come out
  equivalent but different, which is why the codec declares `SourceCodec.functional` and
  `tools cache verify` counts them as *recompiled* rather than different.

`Cs2Corpus` is the single implementation of the folder layout, shared by `decompile-all` and the
codec: two implementations of it would show up as a tree that churns depending on which tool wrote
it last. Note that a script has to be decompiled from the **analyzed** script rather than from a
fresh decode of the same bytes - argument order is recovered from what a script's callers push, not
from its bytecode, and 118 scripts render their parameters differently without it.

The two directions do not need the same things, and that asymmetry is the point:

- **Packing reads nothing but the tree.** `packIndex` builds a `Cs2Compiler.fromSources`, so a
  server with `CACHE_SOURCE_PATH` set can build a tree whose clientscripts changed even though the
  cache a corpus analysis would read is the one that build is producing.
- **Unpacking needs the cache it is unpacking**, because decompiling is the direction where the
  bytes are all there is. `SourceCodec` hands a codec one archive at a time and no cache, so
  `Cs2SourceCodec.cache` is the seam; it defaults to `Cache.get()`, which is right for every tool
  and every test, since unpacking a cache means having one.

The five project files come out of `unpackIndex` rather than out of whichever archive happened to
be unpacked first - none of them is a function of one archive - and `owner` claims them as
`Owner.Index`, so editing one repacks nothing and is handed to `packIndex` instead.
