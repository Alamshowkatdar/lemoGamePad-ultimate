# Changelog

Newest first. The reasoning behind each release is in [`docs/REVIEW.md`](docs/REVIEW.md).

## 2.1.1

- **Fixed the browser pad zooming itself up on phones.** The safe-area pass had
  left the canvas with `width/height:auto`, so it fell back to its bitmap size
  and fed that back into the resize loop. The canvas is now sized explicitly
  against the viewport minus the notch/home-indicator insets, so Android and
  iOS get a correctly scaled pad again.

## 2.1.0

- **Fixed a rumble stop race and tightened party lock.** A delayed refresh can
  no longer re-send an old motor level after the game has stopped, and a lock
  command can no longer lose a race with a swap request or leave a stale Accept
  prompt on the target's phone. The lock button now waits for the PC's confirmed
  state instead of guessing.
- **Trimmed hot-path allocations.** Reused UDP packet wrappers on Android, a
  growable screen-frame buffer, one QR pixel array, the browser PiP drag size,
  and the WebSocket unmask buffer on the PC. Guest QR tabs no longer save a
  swapped seat as a permanent preference.
- **Game rumble now follows the game instead of a fixed pulse.** Both XInput
  motor levels are combined into one continuous vibration, a zero level stops it
  immediately, and the server refreshes live levels so long effects survive a
  lost packet. The browser does the same with a longer sustained vibration.
  **Vibration from games** is now a separate on/off switch on Android and in the
  browser, so button-click haptics can stay on while game force feedback is
  silenced. Game profiles remember the choice.
- **Party lock keeps the lineup stable.** The first connected controller is the
  host and gets a **LOCK PARTY** / **UNLOCK PARTY** control in the Android LINK
  card and browser CONNECTION card. While locked, other players cannot move to a
  different pad or ask for a swap, but their inputs keep flowing and late guests
  can still claim a free pad. The host can still rearrange seats, and the lock
  is released automatically if the host disconnects.
- **Guest QR join adds players without setup.** The server now prints a second
  `lemoGamePad-guest-qr-*.png` code (plus a browser guest variant with
  `&auto=1`). Scanning it puts that device in AUTO and the PC assigns the next
  free pad on the first input packet. A guest code never rewrites the browser
  tab's stored slot preference.
- **Layout QR sharing moves a pad between devices.** Android's LAYOUTS card now
  has **SHARE QR** and **SCAN QR**; the browser LAYOUTS card has **SHARE CODE**
  and **IMPORT CODE**. Both use one `LMGB-LAYOUT-1` text payload, so a browser
  code scans on a phone and a phone QR pastes into the browser. The browser also
  accepts a `#layout=` link, and Android reuses the bundled ZXing scanner with a
  layout-specific mode. No server endpoint or cloud service was added.
- **Removed the Android sheet-height peek.** The `⤢` header control and its
  dead `sheetExpanded` / `toggleSheetHeight()` path are gone; the header is now
  just the title, **SETUP**, **?** and **✕**.
- **Added a guided first-run setup.** New installs pick Wi-Fi, USB, Bluetooth
  or Web pad and see only the steps for that path. It replaces the old Wi-Fi
  card and first-run doctor, and can be replayed from **SETUP** in the settings
  header.
- **Connection Doctor now runs live checks.** **RUN CHECK** probes the paired
  PC's video (TCP 9877) and web-pad (TCP 9878) ports on the selected network,
  alongside Wi-Fi/USB readiness and interface state. The existing single
  **OPEN WEBSITE** button is unchanged.
- **Game profiles can be copied, exported and imported.** Each profile has a
  **COPY** action; **EXPORT** puts a JSON backup on the clipboard and **IMPORT**
  merges profiles whose names are not already in use.
- **Saved preferences are validated on startup.** Wrong types, unknown ids and
  out-of-range values are repaired without clearing valid settings, tokens,
  layouts or profiles.
- **Added an offline Help centre.** Global **?** buttons in Settings and
  Connection Doctor open local, bundled help for setup, links, Bluetooth, aim,
  profiles, layouts and the preview; every settings card keeps its own **?**
  help.
- **Control Centre now uses collapsible categories.** LINK, PAD, BLUETOOTH,
  STYLE and GAMES are tappable headers; one group opens at a time, and tapping
  the open header collapses it. The chosen section is remembered, so the
  setting you want is no longer buried in one long scroll.
- **Control Centre reads more clearly.** Category rows have a consistent height,
  a live `LINK UP` / `HID` / `READY` badge, and an accent rail on the open group;
  card text and buttons have slightly more room and stronger line spacing.
- **Bluetooth host selection is more accurate.** TVs and monitors now appear
  alongside PCs and phones, while headsets and speakers are still filtered out.
  The last host you actually connected to is remembered for picker order and
  tagged `LAST USED`, but is never connected automatically. Bluetooth-off and
  permission states now disable the right buttons and explain the reason.
- **Added a safe route to stop host audio.** Android can connect `Media audio`
  and `Phone audio` to a bonded HID host by itself; a normal app has no public
  API to disable those shared profile switches. While a host is connected the
  Bluetooth card now offers **HOST AUDIO SETTINGS**, which opens that host's
  Android Bluetooth details page (or the paired-device list on OEM skins) with
  a reminder to switch both audio profiles off once for that device. The app
  still only requests HID and never changes audio profiles itself.
- **iPhone/Safari compatibility pass for the browser pad.** The page now
  declares Home Screen standalone mode, keeps the pad and preview clear of the
  notch and home indicator, avoids iOS focus zoom on inputs, enlarges touch
  targets, suppresses the long-press callout, and no longer opens CONTROL
  CENTRE when iOS cancels a touch. Safari versions without Pointer Events get a
  touch-event fallback for the pad, and the screen preview's blob fallback is
  allowed by the CSP. Gyro/tilt and vibration remain Android-only and are now
  documented as such.
- **Home Screen shortcuts survive a server restart.** The new
  `--web-key-file PATH` reuses the browser pairing key between runs, so an
  iPhone bookmark with its `?k=` URL keeps working. An explicit `--web-key`
  still wins, and a missing or corrupt file falls back to a random key.
- **Rumble has an iPhone fallback.** With **SOUND CUE FALLBACK** on by default,
  browsers without the Vibration API (iPhone Safari) play a quiet tone scaled
  by rumble strength instead. Vibration-capable browsers keep using the motor.
- **The browser pad reconnects after a phone sleeps.** It releases held buttons
  when hidden and forces a fresh socket on wake, page show or network return if
  pings have stopped, instead of waiting for a close event that iOS may never
  send.
- **Control Centre is now a dashboard, not a scrolling list.** The browser pad
  gets a pinned quick dock and category rail with per-group scroll memory. The
  Android sheet uses a compact header plus one six-chip tab row (LINK, PAD, BT,
  STYLE, GAMES, DENSE), with no separate dock or section headers, so settings
  start near the top. Cards use one roomy column on phones and two columns on
  wide browser windows. **DENSE** hides explanatory prose so more controls fit
  on screen. All of these are shortcuts into the existing live controls — no
  setting behaviour or wire format changed.
- **Themes feel alive.** Every palette's press ripple now has a longer, wider
  signature: pulse breathes with an inner ring, waves spin, halos sweep with a
  second colour, shocks kick, bursts radiate and step/ripple/void/line read
  distinctly. The browser sheet also gained a themed gradient, glowing active
  tabs, animated theme swatches and a short card-group entrance. All of it
  follows the existing MOTION setting and OFF removes every flourish.
- **Fixed a Control Centre crash on Android 17.** The removed quick dock and
  landscape grid used invalid `GridLayout` row/span specs; the new layout uses
  weighted `LinearLayout` rows and one readable card column.

## 2.0.3

- **The launcher icon now shows the blue-glass artwork instead of a themed
  silhouette.** Some launchers tint adaptive icons using their monochrome
  layer; the app now ships density PNG icons only, so the cobalt logo is what
  actually appears.

## 2.0.2

- **Fixed the Android launcher icon.** Real PNG mipmaps (mdpi–xxxhdpi) and
  round icons are now bundled, and the legacy vector-only launcher resource is
  gone, so launchers that cannot render a vector icon show the app correctly.
- **Switched to a blue-glass logo.** The website favicon/header and the Android
  launcher icon now use the cobalt glass emblem.

## 2.0.1

- **Added the gold gamepad logo.** The Android launcher and the downloads site
  now share a gold-glass emblem that reproduces the in-app landscape gamepad
  layout. The adaptive icon also has a monochrome layer.
- **Fresh installs now start with motion input off.** Tilt steering and gyro aim
  default to `OFF`, so the first launch is a plain gamepad until a user opts in.
  Existing preferences and named game profiles keep their saved values.

## 2.0.0

- **Added a static download and instructions site.** `docs/index.html` collects
  the APK, Windows and Linux server downloads, quick-start steps, setup guides
  and troubleshooting on one page, with links to the full documentation. It does
  not host the playable browser pad — that still runs from the PC server so the
  local WebSocket stays local.
- **Merged the duplicate status cards.** `STATUS` and `LIVE LINK DASHBOARD` are
  now one `CONNECTION STATUS` card with a single graph and one detailed stats
  block. The separate connection settings card is unchanged.
- **Added a battery-saver switch to the status card.** It pauses the live RTT
  graph and detailed stat refresh, slows panel polling, and leaves connection
  state, scan, connect and disconnect controls working.
- **Connection Doctor now links to the website.** Its first step sends users to
  the download/instructions site for the APK, PC server and full setup guides.
  The in-app download step is gone; one **OPEN WEBSITE** button is opened safely
  with an `ACTION_VIEW` intent.
- **Removed the Racing and FPS modes and layouts.** The app now shows one
  Gamepad layout. Stale `mode` preferences fall back to it, and old per-mode
  layout keys are ignored.
- **Added a swipe touchpad toggle.** When enabled it replaces the right stick
  and R3 click. Swiping drives right-stick aim with relative, BGMI-style input;
  gyro aim can contribute to the same axes simultaneously.
- **Switchable mouse aim for the touchpad.** With **Aim touchpad** on, **Mouse
  aim** changes the pad from BGMI swipes to relative game-mouse aim: finger
  speed drives right-stick velocity, faster movement turns faster, and stopping
  your finger stops the aim. It is not an absolute desktop pointer, so the wire
  format is unchanged. Gyro aim still adds to the same axes.
- **Stronger, sustained mouse aim.** A normal finger sweep now produces a useful
  right-stick deflection and holds it while the finger keeps moving, instead of
  only nudging the camera.
- **Smoother swipe-pad feedback.** The touchpad now draws a fading, thinning
  streak that follows the finger with eased motion and glides home on release
  instead of snapping back. Mouse mode uses a shorter follow/return so its
  cursor marker feels immediate.
- **Cleaner touchpad in every theme.** The crosshair and grid are gone. A soft
  centre point and a time-faded curved swipe ribbon now use each palette's
  accent, border weight and glow strength, so the gesture stays legible on both
  light and dark themes.
- **Per-axis gyro revert.** **Revert left/right aim** and **Revert up/down aim**
  independently flip the gyro's horizontal and vertical aim directions. Turning
  on both reverses both axes; each setting is saved.
- **Unified motion controls.** Tilt steering now lives on the Gamepad layout and
  feeds left X only while off centre. Gyro aim feeds right X/Y with or without
  the swipe pad, and is additive with the physical right stick when the pad is
  off.
- **Simplified settings and layouts.** The mode chips and mode-specific motion
  groups are gone; one **MOTION & AIM** card holds the touchpad, tilt, gyro and
  calibration controls. Named layouts are no longer per mode, and swapping the
  swipe pad preserves the previous right-side control placement.
- **Manual game profiles.** Settings now has a **GAME PROFILES** card: save the
  current aim, motion, haptics, input-rate, face-label and layout setup under a
  name, then tap a name to switch back. Switching is always explicit — nothing
  watches the foreground app — and connection settings are never changed.
- **Aim calibration overlay.** **AIM CALIBRATION** opens a ten-target drill with
  a persistent free reticle: drag to place it, then tap to fire. Touch sensitivity
  and gyro aim (including both revert switches) move that same reticle. All
  UDP/HID output is held neutral while it is open, and the summary suggests
  sensitivity or gyro-revert adjustments. Back or **DONE** closes it.
- **Adaptive stream quality (off by default).** When enabled, sustained stutter
  lowers sharpness and a long smooth window raises it again. Manual
  LOW/BALANCED/HIGH taps pause automatic changes for 45 s. Every profile still
  targets 60 fps; only sharpness changes, and the wire protocol is untouched.
- **Wire formats unchanged.** UDP 9876, video TCP 9877 and the 9-byte HID report
  keep the same layout. A PC server update is optional for new features but fixes
  several reliability issues (see below).

### Reliability fixes

- Android no longer registers HID on launch; a failed explicit host pick no
  longer leaves a phantom input ticker, and the pick survives the Nearby-devices
  permission prompt. The host-picker overlay closes with the settings sheet.
- Motion sensors register only while the app is active, swipe drawing allocates
  no per-frame collections, and a nullable pointer compare was corrected.
- PC keeps fatal video-listener errors separate from transient capture failures,
  locks the pending input queue, handles 16-bit sequence wrap, and routes slot
  changes through the guarded move path.
- PC now unplugs a virtual pad when its player moves away or disconnects.
  Selecting P2 with one phone shows one gamepad instead of an idle P1 plus P2.
- Windows pad updates retry a transient driver failure with correct vgamepad
  cleanup; isolated failures no longer accumulate into a permanent shutdown.
- CLI flags are validated even with `--list-ip`; browser keys accept UTF-8 and
  invalid axes become neutral. Linux firewall setup and PyInstaller builds now
  include the browser-pad port and bundled capture/QR data.

## 1.7.0

- **Three controller modes.** The pad now offers **Gamepad**, **Racing** and
  **FPS** experiences, selectable from the CONTROL CENTRE. Each mode keeps its
  own named layout and its own motion settings.
- **Racing mode.** Tilt/gyro steering with on-screen calibration, sensitivity,
  dead zone and curve controls, plus analog gas and brake pedals, nitro,
  handbrake, gear up/down, camera, reset, pause and view. Steering maps to left
  X; gas/brake map to analog R2/L2.
- **FPS mode.** A customisable right-side camera touchpad with swipe-to-aim,
  optional gyro aim, and separate fire, aim, reload, jump, crouch, sprint and
  swap controls. Aim maps to the right stick; fire and aim use analog R2/L2.
- **Gamepad mode.** Xbox ABXY and PlayStation face symbols, two sticks, d-pad,
  shoulders, analog triggers and Start/Select/Home, with a one-tap face-label
  switch.
- **Fixed iconic face colours in every theme** — Xbox A green, B red, X blue,
  Y yellow; PlayStation Cross blue, Circle red, Square pink, Triangle green.
  The palette tint never overrides them.
- **Ten themes with preview before apply** — Midnight Black, Cyberpunk, Arctic,
  AMOLED, Racing Red, Xbox, PlayStation, Minimal, Retro Arcade and Stealth. Themes
  now drive border weight, glow/shadow, letter spacing and backdrop style.
  Tapping a palette opens a canvas preview with APPLY/CANCEL; nothing is
  committed until APPLY is tapped.
- **Customization Studio.** Drag, pinch-resize and hide controls, save named
  layouts per mode, duplicate or reset them, and import/export a layout as text.
  Live opacity, face-spacing and layout controls update the pad behind the sheet.
- **Wire formats unchanged.** UDP input stays on port 9876 with the same packet,
  the PC screen stream stays on TCP 9877, and Bluetooth HID still uses the same
  9-byte report. No PC server change is required.
- **Bluetooth connection is now explicit.** Opening the app no longer registers
  the HID profile or connects to a paired host. The Bluetooth card has **SELECT
  HOST** and **DISCONNECT** controls; only the bonded computer or phone you pick
  is contacted. Pairing is still a one-time step through **BLUETOOTH SETTINGS**
  or **MAKE DISCOVERABLE**.
- **Fixed Bluetooth HID button mapping on Linux/Android hosts.** The descriptor
  used a generic button 1–16 range, which Linux maps to `BTN_GAMEPAD + N`, so
  Square/Triangle, the shoulders, triggers, Back/Start and L3/R3 arrived on the
  wrong evdev codes. It now declares each button usage explicitly in report-bit
  order, so A/B/X/Y, L1/R1, L2/R2, Select/Start and LS/RS map correctly on
  Linux evdev, Windows DirectInput and Android. The 9-byte report is unchanged.
- **60 fps screen preview, smoothness first.** All three quality choices now
  target the same 60 fps cadence; only sharpness varies. Defaults are LOW
  300 px / JPEG 30, BALANCED 480 px / JPEG 42 and HIGH 600 px / JPEG 52, and
  `--video-fps` is capped at 60. Capture uses a fixed-cadence deadline with a
  short spin before each frame (so Linux does not round 60 fps down to 55),
  BOX downscaling (~40% cheaper than BILINEAR) and a raised capture-thread
  priority. The phone reuses decoded bitmaps instead of allocating one per
  frame, drops complete frames queued behind a slow decode, and clears the FPS
  badge when the link drops. Transient capture failures no longer permanently
  disable the browser `/screen` endpoint. The server-side path measured ~59.9
  fps with 1920x1080 input; on a Wayland PC that shells out to `grim` per frame,
  capture still dominates and the realized rate is much lower (see
  [LIMITATIONS.md](docs/LIMITATIONS.md)).
- `versionCode` is 28.

## 1.6.13

- **Bluetooth is a real, automatic HID gamepad now.** The old Bluetooth system
  is gone: no Personal Area Network, no `bnep*` interface, no "Join a Personal
  Area Network" step, no BLUETOOTH transport chip and no separate pairing card.
  The phone can register as a standard **Lemo GamePad** HID device and reconnect
  to a bonded PC or Android host. Pairing once is the only host-side step.
  Superseded in 1.7.0: registration and connection are now explicit, so opening
  the app does not connect by itself.
- **HID rewritten from scratch** with a new 9-byte report: 16 buttons, an 8-way
  hat switch (so DirectInput games receive a real d-pad instead of four stray
  buttons), four signed 8-bit stick axes, and analog L2/R2 on Z/Rz. The host is
  sent a neutral report on connect and on backgrounding so buttons cannot stick.
- **Works with PC and Android hosts** — Windows/Linux see a DirectInput HID
  controller; Android games see a standard external controller. No host app,
  driver or Accessibility service is involved.
- **Wi-Fi and USB are unchanged** — same server, same UDP protocol, same eight
  players, same screen preview. Only the Bluetooth network transport was
  removed.
- `versionCode` is 27. Bluetooth HID requires Android 9+ on the controller
  phone; the PC HID path has no rumble, screen preview or multi-phone slots, and
  on Windows it is DirectInput rather than XInput.

## 1.6.12

- **The browser pad stops advertising a dead address** — the printed Web pad
  URL, the console QR and the saved `lemoGamePad-web-qr-<ip>.png` images were
  produced once, at startup. A laptop that joined another Wi-Fi network kept
  showing the previous IP, so the old link and its QR code failed on every
  device, PC included, even though the server itself was healthy and still
  listening on every interface. The status thread already re-reads the
  machine's addresses every ten seconds; it now re-announces them when they
  change and rewrites the QR images, so the console carries the working link
  within about ten seconds of a network switch.
- **Wire-compatible** — server-only change; no protocol, packet or app changes.
  `versionCode` is 26.

## 1.6.11

- **AMOLED theme** — a ninth palette with a true `#000000` background and
  near-black surfaces. On OLED/AMOLED phone screens those pixels can switch
  fully off, so the same pad draws less power; LCDs see no battery saving but
  still get the look. The decorative radial background glow is removed for this
  palette, so the background is genuinely black rather than near-black.
- **AMOLED has its own `void` press** — one hairline ring that snaps outward and
  vanishes into the black, mirrored by the `ui-void` animation on browser
  chips, toggles, actions and theme tiles.
- **D-pad press animations are fixed** — each direction now owns its own press
  spring in the browser and on Android, so the arrow eases in *and out* instead
  of freezing at a fixed glow or snapping off. Browser keyboard arrows use the
  same animation and ripple.
- **Wire-compatible** — no protocol or transport changes. `versionCode` is 25.

## 1.6.10

- **Every theme now has its own animated press** — the browser pad and
  `GamepadView` draw a one-shot ripple when a face button, bumper, trigger or
  d-pad direction is pressed. Neon pulses a clean ring, Midnight sends a
  trailing wave, Violet blooms a soft halo, Crimson fires a double shock ring,
  Solar throws eight rays, Retro advances a square in four quantised steps,
  Arctic expands three staggered rings and Mono shoots a precise underline.
- **The rest of the UI joins in** — the same eight motifs drive the press
  feedback on browser chips, toggles, actions and theme tiles, keyed off the
  active palette, while the Android settings buttons keep their themed spring.
- **Animated buttons toggle** — added to **APPEARANCE** in both the browser
  CONTROL CENTRE (`lmgb.motion`) and the Android settings sheet
  (`PrefsKeys.MOTION`). It defaults to on, but starts off when the device
  reports reduced motion. The browser also disables the DOM animation through
  `html.no-motion`.
- **Battery behaviour is unchanged** — ripples are one-shot, advance only while
  a press settles, and never add idle frames. Pills and d-pad arms now use the
  existing `pressT` spring for their inset/glow instead of a boolean snap.
- **Wire-compatible** — no protocol or transport changes. `versionCode` is 24.

## 1.6.9

- **Swapping onto another player's pad now needs their consent** — choosing an
  occupied **PLAYER SLOT** used to be a request the server retried until the
  pad happened to free up, so the other player could simply be displaced.
  The PC now asks that player with a small non-blocking **Accept / Decline**
  card on their phone or browser tab. The swap only happens on **Accept**. A
  **Decline**, a player leaving, or no answer within **10 seconds** leaves both
  players on their current pads. The requester shows a pending chip and a
  waiting line until the answer arrives, then a short toast with the result.
  Choosing AUTO, the current pad, or a different chip withdraws the pending ask
  and closes the target's card (the server also replaces it when a new pad is
  requested).
- **The game never pauses for the popup** — the card has no backdrop, touches
  outside it fall through to the pad, and both players keep sending input while
  it is visible. On Android the card is dismissed when the app is paused.
- **Free pads still move immediately** — an unoccupied target is an ordinary
  move, with an `ACCEPTED` result rather than an ask. If two players request
  each other's pads at the same time, that is already consent and both swap
  immediately. A declined pair is not asked again for 2 seconds, and duplicate
  asks/results are ignored by request id.
- **No stale-slot bounce** — after a server-driven move or accepted swap the
  phone's next input frames still name the old pad. The server remembers the
  slot it moved the player away from (and briefly guards all moves) so those
  frames cannot undo the swap; browser tabs also adopt the result's pad as
  their live slot immediately.
- **The browser pad matches the app** — WebSocket messages
  `{"t":"swap","w":n}`, `{"t":"ask","id":…,"f":from}`,
  `{"t":"vote","id":…,"ok":1|0}` and `{"t":"sr","id":…,"c":code,"n":slot}` drive
  the same card and pending state. `{"t":"swap","w":0}` withdraws a pending ask.
  Rumble is unaffected, and a disconnected tab leaves no pending prompt behind.
- **Wire-compatible** — protocol `VERSION` stays **1**. New UDP types 8–11 are
  ignored by older apps, and a request to an older app simply times out, so
  1.3+ phones and servers still work together. `versionCode` is 23.
- `pc/test_multi.py` gained accept, decline/cooldown, cancel, timeout and
  free-slot swap tests that also assert input keeps flowing and stale input
  cannot move a player back. `pc/test_web.py` gained a browser
  ask/vote/accept/cancel/decline test.

## 1.6.8

- **The player slot now matches the game's controller number** — pads were
  created lazily on first use, so a request for **P5** while P1–P4 had never
  connected created the game's *first* virtual controller, not its fifth.
  Choosing P5 could therefore move the phone to the second or third pad in the
  game. Every pad from P1 through the requested slot is now created in slot
  order before the request is accepted, so the OS enumerates them as P1…P8.
  Device creation is serialised with a pad lock because phone packets and
  browser WebSocket threads can register at the same time.
- **Moving to a slot that had never been used now works** — the move updated
  the server's slot bookkeeping but returned before the target pad existed, and
  the flush path deliberately skips a slot without a device, so input after the
  move was silently dropped. A move now creates the target pad as well.
- **Eight controllers verified** — eight simultaneous browser clients receive
  P1–P8, and a live move from P1 to P8 keeps working. On Linux the server
  creates eight distinct real pads (`/dev/input/event18`–`event25`). Windows
  XInput-only games still see four (a Windows API limit); pads 5–8 are
  DualShock 4/HID devices for DirectInput/Raw Input games and emulators, and
  `--pad ds4` makes all eight DirectInput devices.
- `pc/test_multi.py` gained `test_pad_creation_order` (P5 creates P1–P5 and not
  P6–P8) and now asserts that a moved-to pad exists and receives input.

## 1.6.7

- **The browser CONTROL CENTRE opens on a normal tap** — the sheet is opened
  from the pad's `pointerup` handler, but the browser then dispatches the
  compatibility `click` for that same tap. By that point the backdrop was
  visible while the sheet was still sliding in, so the click hit the backdrop
  and closed the sheet instantly. A long press avoided the click, which made it
  look like the pill had to be held. The backdrop now closes on `pointerdown`,
  which can only belong to a new gesture.
- `pc/test_web.py` checks that the backdrop uses `pointerdown`.

## 1.6.6

- **Browser input no longer waits for the 250 ms UDP timeout** — the packet
  loop only selected on the phone's UDP socket, while browser input was queued
  from the HTTP/WebSocket threads. The server now has a `socketpair()` wake-up
  channel, so a browser frame is flushed to the virtual pad as soon as it
  arrives instead of on the next timeout. Measured delay fell from about
  **245 ms** to **1–2 ms** (worst case under 10 ms). Coalescing is unchanged:
  the newest state is still applied once per wake-up.
- `pc/test_web.py` gained `test_browser_input_wakes_loop`, which fails if a
  browser frame waits more than 180 ms.

## 1.6.5

- **Full-screen toggle in the browser pad** — the CONTROL CENTRE now has a
  **FULL SCREEN** button in APPEARANCE. It enters/exits the browser's
  full-screen mode, updates on `fullscreenchange`, re-lays out the pad and the
  screen preview when the viewport changes, and restores the button label if a
  request is denied. Browsers without the Fullscreen API (notably iPhone
  Safari) show **N/A** with a short explanation instead of a dead button.
- `pc/test_web.py` now checks that the control is served and that the
  full-screen handlers shipped with the page.

## 1.6.4

- **Browser pad rebuilt to match the Android app** — the page now uses the same
  canvas pad geometry and spring-press rendering as `GamepadView`, with all
  eight themes and a cross-fade when switching, PlayStation/Xbox face labels,
  the CONTROL CENTRE settings sheet, player-slot chips (AUTO/P1–P8), status
  pill, latency graph and CONNECT / RECONNECT / DISCONNECT.
- **Layout editing in the browser** — drag controls, pinch to resize and hold
  to hide, then save named arrangements. DEFAULT/RESET/SHOW ALL and in-place
  updates of an active layout behave like the Android **LAYOUTS** card. The
  current layout, saved layouts and every setting persist in `localStorage` on
  that browser only.
- **PC screen preview** — the browser can now show the same JPEG stream as the
  phone. A new authenticated `/screen` WebSocket relays raw TCP frames from the
  local `VideoServer`, negotiates LOW/BALANCED/HIGH through `LGVQ`, coalesces
  to the newest frame, reconnects after a drop or quality change, and supports
  small/large sizes plus drag-to-move. It stays disabled when `--no-video` is
  set or the stream is not running.
- **Input-rate modes for the browser** — ECO is change-only with a 1 s
  heartbeat, STANDARD is change-only with a 250 ms heartbeat, and TURBO sends
  continuously at about 60 Hz. The server still receives changes immediately.
- **Screen relay security** — `/screen` requires the same pairing key as the
  pad and the same `Origin`/`Host` check. Static assets remain public so
  relative `<link>`/`<script>` requests work; the page and both WebSockets stay
  protected.
- `pc/test_web.py` now covers the hello message, the new controls, screen
  authentication and a fake video server that exercises the relay's quality
  switch end to end.

## 1.6.3

- **Fix: browser pad stuck on "Connecting…" with no UI** — the server required
  the pairing key for every file, but a browser requests `style.css` and
  `app.js` relative to the page and does not carry its `?k=` query string.
  Those requests returned `401`, so the page rendered unstyled and the
  WebSocket client never ran. Static assets now load without the key; the page
  and the WebSocket still require it, and control stays protected.
- `pc/test_web.py` now fetches the assets the way a browser does, so this
  cannot regress silently.

## 1.6.2

- **Scannable browser QR** — the console now draws the browser pad URL as a QR
  next to the phone pairing QR, so any camera can open the page without typing
  the IP, port and key. The `lemoGamePad-web-qr-<ip>.png` copy is still saved
  for printing or sharing.

## 1.6.1

- **Browser gamepad** — the PC now hosts a gamepad page on TCP `9878`
  (control port + 2) so any phone, tablet or laptop browser on the same Wi-Fi
  can play. It is a full PS3-style touch pad with multi-touch sticks, D-pad,
  face buttons, shoulders and triggers, plus a keyboard mapping (`WASD`/`IJKL`,
  arrow keys, numpad face buttons). Rumble reaches the browser through the
  Vibration API where supported. The page is served entirely by the PC — no
  internet, CDN, fonts or accounts. Each run gets a random pairing key that the
  page and WebSocket require, WebSocket upgrades are `Origin`-checked, and the
  assets are bundled in the EXE/binary. New flags: `--web-port`, `--web-key`,
  `--no-web`. See [Browser pad](docs/WEB.md). This is a local-network feature:
  never expose the port to the internet.

## 1.6.0

- **Eight phones works out of the box** — `--max-phones` now defaults to `8`
  instead of `4`, so the fifth phone is no longer silently ignored. On Windows
  the first four pads stay Xbox 360/XInput; pads 5–8 automatically use the
  DualShock 4/HID backend, because Windows' XInput API itself exposes only four
  controllers. XInput-only games still see four pads; DirectInput/Raw Input
  games, emulators and Linux can use all eight. The console banner now names
  the mixed backends and reminds you of the XInput limit.
- **Multiple layouts** — the pad can now remember several arrangements. In
  settings → **LAYOUTS**, use **SAVE AS…** (also available as **SAVE AS** in the
  edit toolbar) to name the current control positions, sizes and hidden
  controls; tap a saved name to switch instantly, or **DELETE** to remove one.
  **DEFAULT** restores the built-in arrangement. Editing while a saved layout is
  active updates that layout in place, so the pad and its saved copy stay in
  sync. The existing *Customize layout* flow and the live layout are unchanged
  for anyone who never opens the new card.

## 1.5.0

- **Up to eight phones** — `--max-phones` now accepts `1`…`8` (default stays
  `4`). Windows XInput games still see four pads; slots 5-8 are useful with
  `--pad ds4`, DirectInput/Raw Input titles and on Linux.
- **Choose your player slot** — the Android settings sheet now has
  **PLAYER SLOT**: AUTO (next free pad, unchanged) or P1…P8. The request travels
  with the input packets, so a connected phone moves to the wanted pad without
  reconnecting; a busy pad falls back to the next free one and is retried until
  it frees up. The PC reports the pad it actually assigned, shown in the status
  pill and beside the selector. The wire format stays compatible: the new
  trailing byte is ignored by older servers, and older apps keep working.
- **PC hardening** — a slow viewer can no longer corrupt the video stream with a
  half-written frame; one silent video client no longer stalls other viewers;
  a controller token cannot be moved to another machine's address while its
  real owner is still active; and the log, IP cache and 1 ms timer no longer
  block or linger on the receive path.
- **Safer elevation** — the ViGEm installer is re-hashed immediately before the
  UAC prompt, and the firewall helper runs from System32 through an encoded
  command instead of a writable `.cmd` file under `%LOCALAPPDATA%`.
- **Honest firewall status** — an unreadable `netsh` result is reported as
  unknown instead of "OK".
- **Cleaner ghost pads** — `clean_pads.ps1` now removes only root-enumerated
  ViGEm devices, never a real controller, and asks before acting.
- **Android lifecycle fixes** — the screen preview closes its socket when the
  window is dismissed or the activity pauses; the QR scanner releases the
  camera and decoder thread on destroy and reuses its preview buffer; rumble
  stops when the app is backgrounded; the phone's identity token comes from
  `SecureRandom` and is never adopted from a discovery reply; and a rebind can
  no longer leak sockets.

## 1.4.0

- **Steadier latency** — the PC now collapses a burst of queued packets into one
  update at the newest position instead of replaying every old one, discards
  out-of-order packets, and never lets the console window stall the input path.
  The phone sends the moment the pad changes instead of polling every 7 ms,
  holds a low-latency Wi-Fi lock while connected, and marks its packets
  low-delay.
- **See the link, not just guess at it** — settings show round-trip average,
  min, max, jitter and uplink loss, plus a sparkline of the last ~30 seconds.
  The status pill also shows loss and jitter.
- **Input rate modes** — **ECO**, **STANDARD** (default, wake on change) and
  **TURBO** (continuous 250 Hz) in settings.
- **Stick precision curve** — calm small corrections without reducing full
  deflection.
- **Vibration strength** — scale game rumble from 0 to 100 %.
- Wire-compatible with 1.3 apps and servers.

## 1.3.7.2

- **Linux face buttons fixed** — square/triangle (X/Y) are no longer swapped.
  The Linux pad now uses the right evdev codes for the Xbox 360 identity and,
  with `--pad ds4`, the DualShock 4 identity too. Download this release if you
  installed the original 1.3.7 Linux server.

## 1.3.7

- **Linux receiver** — the PC server now runs on Linux, creating a real
  Xbox 360 (or DualShock 4) evdev gamepad through the kernel's `/dev/uinput`,
  the Linux counterpart of ViGEmBus. Force-feedback rumble works through the
  standard evdev API, and Windows is unchanged.
- **Release binaries** — the release now also ships `lemoGamePad-server` for
  Linux, next to the Windows `.exe` and the signed APK. The Linux binary needs
  the one-time `pc/linux/install-linux.sh` setup (shipped with the release and
  the source repository).

## 1.3.6

- **Two new themes** — **Solar** (warm copper and amber) and **Mono**
  (high-contrast graphite) — in a redesigned two-column picker where every tile
  previews its own colours.
- **Smoother pad** — springy button presses, a smootherstep theme cross-fade,
  and frame-rate-independent animation.
- **Better phone-to-phone gamepad** — standard HID button mapping, an honest
  start (it tells you when Bluetooth is off instead of silently doing nothing),
  a live *broadcasting / connected* line, and reports only sent when something
  changes.
- **Fixes** — the status dot no longer pulses forever, the settings sheet no
  longer rescans every network interface twice a second, and a second thumb can
  no longer steal a held face button.

The full list, with reasoning, is in [`docs/REVIEW.md`](docs/REVIEW.md).

---
