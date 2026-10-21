# Proposal: Platform-agnostic Rendering, GUI and Terminal UI

A rendering library with an abstract **Rendering Hardware Interface (RHI)**, platform-owned
window and terminal integration, and separate graphical and curses-style UI frontends.
Applications choose a frontend and presentation target; neither frontend contains operating
system calls or calls a graphics driver directly.

This work is not implemented or assigned a release. All rules below are **[Proposed]** unless
marked **[Open]**; they do not expand R0, v1.0 or bootstrap requirements. Conventions:
[architecture.md](architecture.md) section 0. The [GPU proposal](gpu.md) supplies the proposed
device/resource/submission contracts; this proposal adds rendering organization, presentation
and UI frontends, not another GPU ownership model.

## 1. Goals and boundaries

- **RND-01 [Proposed]** Keep three concerns independent:
  1. **Platform integration:** native windows, event dispatch, terminal sessions and surfaces.
  2. **Rendering:** validated commands, resources, frame scheduling and backend execution.
  3. **UI semantics:** layout, widgets, focus, interaction and accessibility.

The public API must not expose Win32, Wayland, X11, AppKit, Vulkan, Direct3D or Metal types.
Platform agnosticism means stable contracts with replaceable providers, not identical
capabilities or a promise that every backend is available on every operating system.

Goals:

- One shared RHI layer for GUI and terminal-style rendering.
- Separate pixel-oriented GUI and cell-oriented terminal UI libraries, usable independently.
- A real terminal target that needs neither a window server nor a GPU.
- A terminal UI rendered in a native graphical window through the same raster RHI as the GUI.
- Off-screen rendering and deterministic recording/reference backends for testing.
- Explicit capability negotiation, resource ownership, errors and optional dependencies.

Non-goals for the initial design are a game engine, scene graph, web browser, desktop theme
emulation, native controls, a mandatory render thread, or automatic conversion of GUI widgets
into terminal widgets. "Curses-style" describes a cell-grid programming model, not a required
dependency on curses/ncurses.

## 2. Architecture and module boundaries

- **RND-02 [Proposed]** Organize the library into optional modules with one-way dependencies.
  Names are illustrative, not additions to the current standard library.

```text
Application
  |-- reflect.ui.gui ------> reflect.render.2d ----+
  |-- reflect.ui.terminal -> cell frame compiler -+--> reflect.render.rhi
  |-- reflect.platform (event loop and targets)   |
                                                +--> selected RHI backend
                                                       |
                                     platform surface/session services
                                                       |
                                  native window/graphics/terminal layer
```

| Module or implementation | Owns | Must not own |
|---|---|---|
| `reflect.platform` | Portable events, window/terminal sessions, target descriptions, event-loop integration | Widgets, draw batching or graphics resource allocation |
| `reflect.render.rhi` | Capability profiles, resource/frame/command contracts, validation and completion | Native event pumps, widget layout or terminal escape encoding |
| `reflect.render.2d` | Raster display lists, clipping, composition, glyph/image batching | Window creation, focus or a terminal cell model |
| `reflect.ui.gui` | Pixel-oriented layout, controls, hit testing, focus and accessibility semantics | GPU commands or terminal escape sequences |
| `reflect.ui.terminal` | Cell layout, panes, styles, focus, cell hit testing and terminal widgets | Native terminal I/O or graphics driver calls |
| Raster/cell compilers | Lower frontend output to the chosen RHI profile | Application state or event-loop ownership |
| Platform providers | Native window/event/session operations and opaque surface leases | UI policy |
| RHI backends | Execute negotiated commands and present through platform leases | Widget behavior |

The terminal cell compiler can live with `reflect.ui.terminal`; it does not require the GUI
module. Shared geometry, colors, input identities and semantic action types may be small common
contracts. Share mechanisms only where semantics genuinely match: do not force cells and pixels,
GUI text selection and terminal cursor movement, or both widget trees into one abstraction.

`reflect.render.rhi` defines a narrow provider interface. Platform implementations and RHI
backends depend on that interface; the RHI core does not import concrete platform providers.
An application or composition module selects and connects them, preventing a dependency cycle.
Nothing here makes `reflect.core`, the compiler or the baseline VM depend on rendering.

## 3. One RHI, explicit rendering profiles

- **RND-03 [Proposed]** Use a shared RHI with a small common lifecycle and two typed command
  profiles. A terminal is a rendering device in this abstraction, not a pretend GPU.

| Contract | Common RHI | Raster profile | Cell profile |
|---|---|---|---|
| Discovery and negotiation | Adapter/target identity, capabilities, limits, required/preferred features | Formats, blending, texture sizes, presentation modes | Grid limits, color modes, styles, cursor and supported terminal operations |
| Ownership | Device identity, checked handles, explicit close and in-flight retention | Buffers, textures, samplers, pipelines | Immutable cell/glyph data and frame snapshots |
| Frame lifecycle | Acquire, encode, submit, completion, invalidation | Render-target image and pixel extent | Cell-grid extent and terminal-session epoch |
| Commands | Profile-checked encoding and validation | Passes, draws, uploads, scissors and dependencies | Cell runs, rectangular updates, clearing and cursor state |
| Presentation | Opaque platform lease and structured status | Swapchain or software image presentation | Serialized terminal output |

Profiles are separate interfaces/encoders, not an untyped bag of commands or dozens of methods
that throw on half the backends. A raster encoder cannot target a cell device; negotiation
returns a typed binding to the accepted profile. Common submission machinery can dispatch to
different executors without assuming that terminal output has GPU barriers or shaders.

For raster backends, reuse GPU-03 through GPU-07: a GPU adapter wraps the same underlying device,
resource handles and completion objects as `reflect.gpu`. It must not copy ownership into an
independent device abstraction or permit mixing resources from unrelated devices. Terminal-only
builds depend on the RHI contracts, not on `reflect.gpu`, shader tools or a GPU runtime. A software
raster backend implements the raster profile without claiming hardware acceleration.

| Frontend | Native GPU window | Software/off-screen raster target | Native terminal |
|---|---|---|---|
| GUI | GUI display list -> raster RHI | Same raster contract, subject to negotiated limits | Unsupported; an application supplies a separate terminal UI |
| Terminal UI | Cell grid -> glyph/rectangle batches -> raster RHI | Same cell-to-raster compiler | Cell grid -> cell RHI -> terminal output backend |

This is the shared rendering layer: both frontends submit through RHI ownership, validation,
frames and completion. It is deliberately not a common pixel command stream subsequently
guessed back into text, nor a cell-only API that limits graphical rendering.

## 4. Platform integration and presentation

- **RND-04 [Proposed]** A platform session owns native integration. It creates a `WindowTarget`,
  `TerminalTarget` or headless target, pumps native events and grants opaque, scoped presentation
  leases. The compatible RHI backend uses a lease to create its presenter; it does not create a
  hidden window or take over the application's event loop.

An illustrative provider matrix, not a selected backend or support commitment:

| Platform | Window/event provider | Raster presentation candidates | Terminal provider |
|---|---|---|---|
| Linux | Wayland and/or X11 | Vulkan surface/swapchain; provider-supported software image presentation | TTY/PTY session, termios, negotiated terminal control capabilities |
| Windows | Win32 | DXGI/Direct3D, Vulkan, or software presentation | Console/PTY handle with explicitly enabled and verified VT support |
| macOS | AppKit | Metal with a native layer, or software presentation | TTY/PTY session and terminal control capabilities |
| Headless | No window provider | Off-screen GPU image or software image | In-memory cell target; not a real terminal |

Backend selection uses an explicit request and reports the selected configuration. A caller may
provide an ordered fallback policy, such as hardware raster followed by software raster. Missing
required features fail; preferred-feature omissions and fallback selection are reported. Never
silently open a GUI for a terminal request or treat redirected output as an interactive terminal.
An explicitly selected plain-text mode may serve redirected output, outside interactive frames.

The platform boundary exposes:

- Logical window size, physical framebuffer size and scale as distinct values.
- Terminal dimensions in cells, independently of any available pixel-size information.
- Event delivery, focus, resize, visibility, close requests and display/scale changes.
- Negotiated clipboard, cursor, text-input/IME and accessibility services where available.
- Wait integration for platform events, timers and RHI readiness, including terminal writable
  readiness. This permits a single-threaded application without busy-waiting.

Targets have identity and a generation/epoch. Resize or native target replacement invalidates
old frame acquisitions; applications relayout using the new metrics before acquiring again.
Frame cancellation releases an unsubmitted acquisition. Zero-sized/minimized windows suspend
presentation rather than allocate invalid images. A target cannot close its native surface
while an active presenter still uses it; shutdown drains or explicitly tears down its leases.
Toolkit-owned main-thread requirements remain observable even with a platform-neutral API.

### 4.1 Reflect platform and dependency gate

- **RND-05 [Open]** Resolve the platform/dependency policy before implementing native backends.
  ARC-02, LIB-07 and UNS-04 currently require Reflect-owned implementations without maintained
  C/C++ or pre-v1.0 C-library calls. The existing baseline is Linux/x86-64, not a multi-platform
  runtime. This proposal does not silently authorize driver libraries, platform frameworks,
  SDL/GLFW, ncurses, font engines or new trusted natives.

As with GPU-02, an implementation needs an approved route for each native ABI, dynamic loader,
driver/framework dependency and supported CPU/OS runtime. Platform-agnostic interfaces can be
designed first; native providers arrive only after those gates are satisfied. Keep any approved
unsafe bindings narrowly scoped, and keep CPU-only/bootstrap builds independent of them.
Shared GPU backend work belongs to the GPU access track, not a parallel rendering driver stack.

## 5. Shared frame and resource lifecycle

- **RND-06 [Proposed]** Use an explicit state machine:

```text
negotiate device + target -> acquire frame -> encode -> validate/submit -> completion
                                  |                         |
                                  +-> cancel                +-> present status
```

The concrete ordering is backend-specific: raster submission signals image readiness before
presentation; a terminal submission serializes cell updates to its stream. The common contract
must not require one extra copy or an artificial GPU-style present call for terminal output.
Acquisition reports ready, temporarily unavailable or invalidated separately from fatal errors.

Frames carry target identity, epoch, extent and profile. Reject stale acquisitions, invalid
resource usage, cross-device handles and double submission before execution where possible.
Submission snapshots or retains command data; callers may not leave a borrowed host span alive
for an asynchronous upload/write. Closing a resource rejects new work and defers physical release
until its last use completes. These are the same ownership obligations as GPU-04 and GPU-05.

Keep submission/completion separate from visibility:

- Raster completion reports command execution and resource visibility under GPU-06; it is not
  proof that a monitor has displayed a frame. Presentation reports its own status.
- Terminal completion means all frame bytes were accepted by the output transport, not that a
  terminal emulator has painted them or that a remote user has received them.
- Timeouts do not release in-flight resources. Device loss, terminal disconnect and target
  closure fail the affected work explicitly; retries require an application recovery decision.

Start with bounded queues and one outstanding native-terminal frame. Expose backpressure without
blocking native event dispatch. Coalescing may replace pending, unsubmitted visual frames, never
partially written frames, input events or commands whose resource effects are externally visible.
No render thread or `async`/`await` is required; later concurrency follows the [threading
proposal](threading.md) and backend thread-affinity rules.

## 6. GUI frontend and raster renderer

- **RND-07 [Proposed]** The GUI owns a retained logical widget tree and emits an immutable 2D
  display list. The renderer consumes drawing primitives, not widget instances.

The initial raster vocabulary covers filled rectangles, borders, images, clipped glyph runs
and opacity composition. Declare coordinate origin, logical-to-physical scaling, pixel rounding,
clipping and painter order once. Batch only where it preserves those semantics. Keep transforms,
paths, advanced effects and 3D extensions optional rather than prerequisites for a basic UI.

Use an explicit color-space contract: declare texture input encodings, blend in linear light
with premultiplied alpha, and negotiate output encoding. Do not rely on backend-dependent
default blending or promise identical physical colors across unrelated terminals and displays.

Text layout is a distinct service: Unicode segmentation, bidirectional ordering, shaping, font
fallback and glyph rasterization produce positioned glyph runs. The renderer owns glyph-atlas
resources and upload dependencies. Native-terminal text does not use that atlas. Select a
versioned text/font dependency policy before claiming general international-text support; a
restricted prototype must advertise and diagnose its supported subset.

GUI hit testing, tab order, text editing and IME composition use logical coordinates. The GUI
exports a semantic accessibility tree through the platform provider rather than assuming that
drawing text makes custom widgets accessible. Native controls may be a later separate adapter,
not a backend that changes the meaning of raster commands.

## 7. Terminal frontend and cell rendering

- **RND-08 [Proposed]** The terminal UI owns a logical cell surface with panes, clipping, styles,
  scrolling, focus and widgets. A frame is a complete desired grid plus cursor state; the cell
  backend may encode only the changes from its last committed grid.

Each leading cell identifies a grapheme cluster, foreground/background colors and style flags.
Wide clusters have explicit continuation cells; combining marks stay with their cluster.
Layout, clipping, cursor movement and diffing must never split a wide cluster. Changing either
half clears/updates the whole occupied region, including when a new grid clips a former wide
glyph at its right edge. Reject invalid cell structures before producing output.

Choose and expose a versioned width policy, including ambiguous-width behavior. Native terminal
emulators can disagree about Unicode versions and emoji widths; provide explicit overrides and
document that perfect cross-emulator agreement cannot be guaranteed. Raster terminal rendering
uses the selected cell metrics and fits shaped glyphs into those cells; it does not silently
replace cell layout with proportional GUI text layout.

### 7.1 Native terminal backend

The backend owns capability lookup/probing, cursor-addressed output, style/color encoding and
diff generation. ANSI/VT sequences are an implementation strategy, not the portable API.
Capabilities distinguish true color, indexed color, style support, cursor controls, mouse
reporting, bracketed paste and optional synchronized updates. Probes must be bounded and replies
must be demultiplexed from user input.

Color reduction or omitted styles require a negotiated degradation policy. Unsupported required
features fail. Clear/draw operations have a conservative baseline; optimizations such as scroll
regions are used only when their capabilities and effect on the tracked grid are known.

One session arbitrates terminal input and output. The platform terminal lease saves/restores
input modes, alternate-screen state, cursor visibility and enabled protocols on normal exit and
recoverable errors. Catchable termination requests restoration through a safe cleanup path, not
arbitrary I/O inside an unsafe signal handler. Uncatchable termination cannot promise cleanup.

Output is serialized. Short writes and temporary unavailability retain the unwritten suffix;
only a completely written frame commits the diff baseline. Failure after partial output marks
screen state unknown; a recoverable session needs a full redraw before further differential
updates. Resize, resume and uncontrolled external output likewise invalidate the baseline.
Route logs to a separate sink or an explicit suspend/write/resume session operation. Never mix
arbitrary stdout logging into an active screen.

Terminal rendering treats text as data: control characters, escape bytes and unsafe control
strings in labels or pasted content cannot become terminal commands. Only the backend emits
control sequences, using validated values. Bound parser buffers, probe timeouts and paste sizes.

### 7.2 Terminal UI in a graphical window

The same desired cell grid is lowered into background rectangles, positioned glyph runs,
decorations and cursor geometry through `reflect.render.2d` and the raster RHI. It shares
layout/widget behavior with the native-terminal frontend, not escape-sequence generation.
This is a terminal-style UI renderer, not a shell terminal emulator: PTY process management and
VT emulation of another application's output would be a separate feature.

## 8. Input, application integration and errors

- **RND-09 [Proposed]** Normalize platform events without discarding their origin or inventing
  unavailable information. Separate physical/logical key events from text input, paste, pointer,
  wheel, focus and resize events. A terminal escape parser cannot reliably recover all physical
  keys, key-up events or IME composition; those are explicit capabilities, not synthetic events.

GUI adapters map pointers to logical pixels; terminal adapters map them to cells. Share action
bindings and application state where useful, but keep frontend-specific focus, layout and hit
testing. Each UI can be embedded in an application-owned loop or use an optional convenience
runner; constructing a renderer never starts a hidden loop.

An illustrative integration sequence, not committed Reflect syntax:

```text
select platform provider and create window / terminal session / off-screen target
negotiate RHI profile, required features and allowed fallback policy for that target
construct GUI + raster compiler, or terminal UI + selected cell/raster compiler
repeat:
    wait for input, platform changes, timers or submission readiness
    route input to the chosen frontend; update application state
    apply new target metrics and relayout if needed
    if invalidated and a frame is available:
        build frontend output; lower to the negotiated RHI profile
        submit; retain frame resources until completion; observe presentation status
close frontend resources, drain/tear down presenter, then close platform target/session
```

- **RND-10 [Proposed]** Errors carry operation, backend, target/device identity and native detail
  where safe. Distinguish unsupported profile/feature, unavailable provider, permission failure,
  invalid command/resource, target invalidation, device loss, transport failure and disconnect.
  Encoding errors are synchronous; execution/write errors belong to completion. Temporary
  backpressure is a typed scheduling outcome, not a fatal error or successful empty frame.

Diagnostics expose selected provider/profile, degraded features, submitted/presented frames,
draw batches or cell runs, uploads/output bytes, queue stalls and invalidations. Fallbacks and
asynchronous errors must reach the application. Null/recording backends are explicitly selected
test tools, never silent substitutes for a failed real renderer.

## 9. Validation and acceptance criteria

- **RND-11 [Proposed]** Test contracts and actual presentation independently. A recording RHI
  proves frontend command generation, not working native integration.

| Area | Required evidence |
|---|---|
| Layering | GUI and terminal-only sample builds without the other frontend; native-terminal build needs no GPU/window libraries; platform types do not leak into public APIs |
| RHI contracts | Invalid profile, stale/cross-device handles, bounds, double submit, cancel, close while in flight, timeout and loss tests |
| Raster rendering | Reference-image tests for clipping, painter order, alpha/color conversions, scaling and glyph placement; backend-specific tolerances stated |
| Cell rendering | Grid snapshots and diffs for unchanged frames, scrolling, wide/combining clusters, overlap, right-edge clipping, resize and cursor changes |
| Terminal protocol | Captured output interpreted by a test terminal model; short writes, backpressure, partial failure, probe/input overlap and disconnect fault injection |
| Terminal safety | Labels containing escapes cannot emit controls; bounded malformed input; restoration after normal exit, recoverable failure and catchable termination |
| Native platform | Interactive resize/minimize/restore, scale changes, close, input, paste and event-wait integration on each claimed provider |
| Presentation paths | GUI off-screen and native-window rendering; identical logical terminal grid through native-cell and raster targets, allowing declared capability/width differences |
| Recovery | Device loss requires explicit recreation; terminal resynchronization performs a full redraw; no success-shaped fallback |

Measure CPU frame-build/submission time, frame latency, allocation counts, raster batches and
terminal bytes written against documented workloads. An unchanged terminal grid with unchanged
cursor emits zero presentation bytes once capability negotiation is complete. Changing one
ordinary single-width cell without a scroll/resize/style-policy change must not redraw unrelated
rows. Queue memory remains within configured bounds under a stalled presenter. Establish
numerical performance budgets on specified machines before claiming performance targets; do
not label a software/recording test as hardware conformance.

## 10. Delivery plan and open decisions

- **RND-12 [Proposed]** Deliver independently testable layers, without release assignments:
  1. Define profile, platform lease, event, ownership and error contracts; implement recording,
     in-memory-cell and reference software-raster test backends.
  2. Resolve the native dependency/runtime policy. Implement one real terminal provider and
     cell backend with lifecycle restoration, Unicode-width policy and fault-injected I/O.
  3. Build the terminal frontend and demonstrate the same cell UI on native-cell and off-screen
     raster targets, without importing the GUI widget library.
  4. Reuse the GPU access track for one approved graphics backend; integrate one native window
     provider and demonstrate acquire/resize/render/present/loss handling.
  5. Build the GUI frontend over the tested raster renderer, including a declared text/font
     scope, input/IME behavior and accessibility integration.
  6. Add providers/backends only against the same conformance suite; stabilize public APIs after
     exercising both raster and native-terminal paths.

Steps 1 and frontend experiments need no real GPU; steps 2 and 4 require the platform policy gate.
GPU execution of Reflect bytecode is not a prerequisite for any rendering milestone.

**[Open]** Before committing implementation scope:

- First OS/window-system and graphics API, and the approved native binding/loader policy.
- Packaging of platform providers/backends as trusted stdlib modules or optional services under
  the native-module rules; interfaces alone do not grant permission to declare natives.
- Minimum terminal capability set and whether capability tables, bounded probes or both are
  maintained in Reflect.
- Text shaping, font discovery/rasterization, Unicode data and ambiguous-width defaults.
- Initial GUI control/accessibility scope, default queue bounds and measured performance budgets.

The architectural recommendation is fixed independently of those choices: **separate GUI and
terminal semantics, one profile-aware RHI, and platform-owned native integration**.
