/* Browser pad for lemoGamePad ultimate. No external requests, no analytics. */
(() => {
  "use strict";

  const BITS = {
    select: 0,
    l3: 1,
    r3: 2,
    start: 3,
    up: 4,
    right: 5,
    down: 6,
    left: 7,
    l2: 8,
    r2: 9,
    l1: 10,
    r1: 11,
    triangle: 12,
    circle: 13,
    cross: 14,
    square: 15,
    ps: 16,
  };

  const K_DPAD = 0;
  const K_STICK_L = 1;
  const K_STICK_R = 2;
  const K_FACE = 3;
  const K_PILL = 4;
  const K_PS = 5;
  const K_SHOULDER = 6;

  const STATUS_IDLE = 0;
  const STATUS_OK = 1;
  const STATUS_WARN = 2;
  const FACE_SPREAD = 0.115;
  const PRESS_STIFFNESS = 0.3;
  const PRESS_DAMPING = 0.7;
  const MAX_PLAYERS = 8;
  const RTT_HISTORY = 60;
  const BRAND_MARK = "LEMOGAMEPAD ULTIMATE \u00b7 ALAM SHOWKAT";

  const $ = (selector) => document.querySelector(selector);
  const app = $("#app");
  const canvas = $("#pad-canvas");
  const ctx = canvas.getContext("2d");

  // ------------------------------------------------------------------ themes
  const THEMES = [
    {
      id: "neon", name: "Neon", dark: true,
      bgTop: "#0f1a14", bgMid: "#070c09", bgBottom: "#030504",
      surface: "#0e1712", surfaceAlt: "#16251d",
      text: "#e9fbf1", textDim: "#b9cfc2", muted: "#6f8579",
      accent: "#3ef08c", accent2: "#2ed9c3", ok: "#3ef08c", warn: "#ffc46b",
      cross: "#52f2a2", circle: "#5fe3ff", square: "#bcff6e", triangle: "#8cffd6",
    },
    {
      id: "midnight", name: "Midnight", dark: true,
      bgTop: "#101a2b", bgMid: "#0a1120", bgBottom: "#05080f",
      surface: "#101a29", surfaceAlt: "#172741",
      text: "#e8eef9", textDim: "#c3cedf", muted: "#7e8ca3",
      accent: "#4ea8ff", accent2: "#7c6cff", ok: "#45dfa5", warn: "#ffc46b",
      cross: "#5b9bff", circle: "#ff5c72", square: "#ff7bc4", triangle: "#45dfa5",
    },
    {
      id: "violet", name: "Violet", dark: true,
      bgTop: "#150f22", bgMid: "#0c0814", bgBottom: "#05040a",
      surface: "#171126", surfaceAlt: "#241a3a",
      text: "#f0eaff", textDim: "#cfc4ea", muted: "#8b7fa8",
      accent: "#b18cff", accent2: "#ff7bd5", ok: "#6ef0c8", warn: "#ffcf7a",
      cross: "#9e7bff", circle: "#ff6fae", square: "#6ee7ff", triangle: "#7cffc4",
    },
    {
      id: "crimson", name: "Crimson", dark: true,
      bgTop: "#1a0b0e", bgMid: "#100608", bgBottom: "#060203",
      surface: "#1b1013", surfaceAlt: "#2c181d",
      text: "#ffedef", textDim: "#e3bfc4", muted: "#a3808a",
      accent: "#ff4d67", accent2: "#ff9b4d", ok: "#57e3a0", warn: "#ffc46b",
      cross: "#ff6b7f", circle: "#ff9b4d", square: "#ff7bc4", triangle: "#ffd166",
    },
    {
      id: "solar", name: "Solar", dark: true,
      bgTop: "#1c1207", bgMid: "#100a04", bgBottom: "#070302",
      surface: "#1b1209", surfaceAlt: "#2a1c0f",
      text: "#fff3e4", textDim: "#ead4b8", muted: "#a38b70",
      accent: "#ffb43e", accent2: "#ff7a3d", ok: "#6fe3b0", warn: "#ffd166",
      cross: "#ffc24b", circle: "#ff7a55", square: "#ffd07a", triangle: "#8be28f",
    },
    {
      id: "retro", name: "Retro", dark: true,
      bgTop: "#20242a", bgMid: "#16191e", bgBottom: "#0b0d10",
      surface: "#2a2f36", surfaceAlt: "#363c45",
      text: "#e9edf2", textDim: "#c0c8d2", muted: "#8a94a0",
      accent: "#c7d2e3", accent2: "#6f8fbf", ok: "#45dfa5", warn: "#ffc46b",
      cross: "#5b9bff", circle: "#ff5c72", square: "#ff7bc4", triangle: "#45dfa5",
    },
    {
      id: "arctic", name: "Arctic", dark: false,
      bgTop: "#f2f7fc", bgMid: "#e2eaf3", bgBottom: "#cbd8e5",
      surface: "#ffffff", surfaceAlt: "#eaf1f8",
      text: "#16202b", textDim: "#3e4c5c", muted: "#6c7c8c",
      accent: "#0e9f63", accent2: "#2c7be5", ok: "#0e9f63", warn: "#c77a12",
      cross: "#1e7fe0", circle: "#d93f55", square: "#b85ac9", triangle: "#0e9f63",
    },
    {
      id: "mono", name: "Mono", dark: true,
      bgTop: "#141414", bgMid: "#0b0b0b", bgBottom: "#030303",
      surface: "#121212", surfaceAlt: "#212121",
      text: "#f5f5f5", textDim: "#d0d0d0", muted: "#8a8a8a",
      accent: "#ededed", accent2: "#9e9e9e", ok: "#8be28f", warn: "#ffc46b",
      cross: "#ededed", circle: "#bfbfbf", square: "#9e9e9e", triangle: "#dcdcdc",
    },
  ];
  const THEME_KEYS = [
    "bgTop", "bgMid", "bgBottom", "surface", "surfaceAlt",
    "text", "textDim", "muted", "accent", "accent2", "ok", "warn",
    "cross", "circle", "square", "triangle",
  ];
  const NEON = THEMES[0];

  function themeById(id) {
    return THEMES.find((theme) => theme.id === id) || NEON;
  }

  // ------------------------------------------------------------------ colour
  function rgbOf(hex) {
    const clean = hex.replace("#", "");
    return [
      parseInt(clean.slice(0, 2), 16),
      parseInt(clean.slice(2, 4), 16),
      parseInt(clean.slice(4, 6), 16),
    ];
  }

  function hexOf(rgb) {
    const part = (value) =>
      Math.max(0, Math.min(255, Math.round(value))).toString(16).padStart(2, "0");
    return "#" + part(rgb[0]) + part(rgb[1]) + part(rgb[2]);
  }

  function blendHex(from, to, t) {
    const k = Math.max(0, Math.min(1, t));
    const a = rgbOf(from);
    const b = rgbOf(to);
    return hexOf([
      a[0] + (b[0] - a[0]) * k,
      a[1] + (b[1] - a[1]) * k,
      a[2] + (b[2] - a[2]) * k,
    ]);
  }

  function alpha(hex, value) {
    const rgb = rgbOf(hex);
    return `rgba(${rgb[0]},${rgb[1]},${rgb[2]},${Math.max(0, Math.min(1, value))})`;
  }

  function onColor(hex) {
    const [r, g, b] = rgbOf(hex);
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.62 ? "#08110c" : "#f7fffb";
  }

  function blendThemes(a, b, t) {
    const out = { id: t < 0.5 ? a.id : b.id, name: t < 0.5 ? a.name : b.name, dark: t < 0.5 ? a.dark : b.dark };
    for (const key of THEME_KEYS) out[key] = blendHex(a[key], b[key], t);
    return out;
  }

  // ------------------------------------------------------------------- prefs
  const PREF = {
    theme: "lmgb.theme",
    faces: "lmgb.faces",
    deadzone: "lmgb.deadzone",
    curve: "lmgb.curve",
    haptics: "lmgb.haptics",
    strength: "lmgb.strength",
    rate: "lmgb.rate",
    slot: "lmgb.slot",
    layout: "lmgb.layout",
    layouts: "lmgb.layouts",
    layoutActive: "lmgb.layoutActive",
    screen: "lmgb.screen",
    screenSmall: "lmgb.screenSmall",
    screenQuality: "lmgb.screenQuality",
    screenX: "lmgb.screenX",
    screenY: "lmgb.screenY",
  };

  function readPref(key, fallback) {
    try {
      const value = localStorage.getItem(key);
      return value === null ? fallback : value;
    } catch (_error) {
      return fallback;
    }
  }

  function writePref(key, value) {
    try {
      localStorage.setItem(key, String(value));
    } catch (_error) {
      /* private mode: keep the session value only */
    }
  }

  function readNumber(key, fallback) {
    const value = Number(readPref(key, ""));
    return Number.isFinite(value) ? value : fallback;
  }

  function readBool(key, fallback) {
    const value = readPref(key, "");
    if (value === "1" || value === "true") return true;
    if (value === "0" || value === "false") return false;
    return fallback;
  }

  const RATE_INFO = {
    eco: { hint: "Only send when something moves \u2014 best battery" },
    standard: { hint: "Wake instantly on change \u2014 recommended" },
    turbo: { hint: "Continuous ~60 Hz \u2014 lowest browser latency, more battery" },
  };

  const prefs = {
    theme: themeById(readPref(PREF.theme, NEON.id)).id,
    faces: readPref(PREF.faces, "ps") === "xbox" ? "xbox" : "ps",
    deadzone: Math.max(0, Math.min(40, readNumber(PREF.deadzone, 12))),
    curve: Math.max(0, Math.min(100, readNumber(PREF.curve, 0))),
    haptics: readBool(PREF.haptics, true),
    strength: Math.max(0, Math.min(100, readNumber(PREF.strength, 100))),
    rate: ["eco", "standard", "turbo"].includes(readPref(PREF.rate, ""))
      ? readPref(PREF.rate, "standard")
      : "standard",
    slot: Math.max(0, Math.min(MAX_PLAYERS, readNumber(PREF.slot, 0))),
    screen: readBool(PREF.screen, false),
    screenSmall: readBool(PREF.screenSmall, true),
    screenQuality: Math.max(0, Math.min(2, readNumber(PREF.screenQuality, 1))),
    screenX: readNumber(PREF.screenX, 0.5),
    screenY: readNumber(PREF.screenY, 0.3),
  };

  function savePref(key, value) {
    writePref(key, typeof value === "boolean" ? (value ? "1" : "0") : value);
  }

  // ------------------------------------------------------------------- state
  let W = 0;
  let H = 0;
  let DPR = 1;
  let themeFrom = themeById(prefs.theme);
  let themeTarget = themeFrom;
  let themeNow = themeFrom;
  let themeT = 1;
  let statusText = "CONNECTING\u2026";
  let statusKind = STATUS_WARN;
  let statusPulse = 0;
  let enterT = 0;
  let entered = false;
  let dirty = true;
  let frameHandle = 0;
  let lastFrameAt = 0;
  let editMode = false;

  function setStatus(text, kind) {
    if (text === statusText && kind === statusKind) return;
    const stateChanged = kind !== statusKind;
    statusText = text;
    statusKind = kind;
    if (stateChanged) {
      statusPulse = 1;
      if (editMode) hideEditBar();
    }
    dirty = true;
    requestFrame();
  }

  // ------------------------------------------------------------ theme to CSS
  function applyCssTheme(theme) {
    const root = document.documentElement;
    const sheet = theme.dark ? alpha(theme.surface, 0.98) : alpha(theme.surface, 0.99);
    const card = theme.dark ? alpha(theme.surfaceAlt, 0.78) : alpha(theme.surfaceAlt, 0.92);
    const line = theme.dark ? alpha(theme.text, 0.14) : alpha(theme.text, 0.16);
    const lineStrong = theme.dark ? alpha(theme.text, 0.24) : alpha(theme.text, 0.28);
    const vars = {
      "--bg-bottom": theme.bgBottom,
      "--surface": theme.surface,
      "--surface-alt": theme.surfaceAlt,
      "--text": theme.text,
      "--text-dim": theme.textDim,
      "--muted": theme.muted,
      "--accent": theme.accent,
      "--accent-2": theme.accent2,
      "--ok": theme.ok,
      "--warn": theme.warn,
      "--on-accent": onColor(theme.accent),
      "--surface-sheet": sheet,
      "--card-bg": card,
      "--card-line": alpha(theme.accent, theme.dark ? 0.16 : 0.24),
      "--line": line,
      "--line-strong": lineStrong,
      "--text-soft": alpha(theme.text, 0.14),
      "--accent-soft": alpha(theme.accent, 0.18),
      "--accent-line": alpha(theme.accent, 0.55),
      "--scrim": theme.dark ? "rgba(0,0,0,0.66)" : "rgba(0,0,0,0.42)",
    };
    for (const [name, value] of Object.entries(vars)) {
      root.style.setProperty(name, value);
    }
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", theme.bgTop);
  }

  function setTheme(id) {
    const next = themeById(id);
    if (next.id === themeTarget.id) return;
    prefs.theme = next.id;
    savePref(PREF.theme, next.id);
    themeFrom = themeNow;
    themeTarget = next;
    themeT = 0;
    requestFrame();
  }

  // ------------------------------------------------------------------ frames
  const nodes = [];
  const faceNodes = [];
  const playOrder = [];
  let dpadNode = null;
  let leftStick = null;
  let rightStick = null;
  const hud = { x: 0, y: 0, w: 0, h: 0 };
  let hudPressed = false;

  // ------------------------------------------------------------------ toast
  const toastEl = $("#toast");
  let toastTimer = 0;

  function toast(message) {
    toastEl.textContent = message;
    toastEl.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove("show"), 2400);
  }

  // ------------------------------------------------------------------ nodes
  function makeNode(key, kind, bit, label, fx, fy, faceIdx = -1, homeDx = 0, homeDy = 0) {
    return {
      key, kind, bit, label, fx, fy, faceIdx, homeDx, homeDy,
      defFx: fx, defFy: fy, scale: 1, hidden: false, customized: false,
      cx: 0, cy: 0, hw: 0, hh: 0, r: 0,
      pressed: false, pointer: null, up: false, down: false, left: false, right: false,
      kx: 0, ky: 0, pressT: 0, pressVel: 0, bw: 0, bh: 0,
    };
  }

  function buildNodes() {
    dpadNode = makeNode("dpad", K_DPAD, -1, "D-PAD", 0.115, 0.585);
    leftStick = makeNode("ls", K_STICK_L, -1, "LEFT STICK", 0.265, 0.75);
    rightStick = makeNode("rs", K_STICK_R, -1, "RIGHT STICK", 0.705, 0.75);

    const tri = makeNode("tri", K_FACE, BITS.triangle, "TRIANGLE", 0.855, 0.470, 0, 0, -FACE_SPREAD);
    const cir = makeNode("cir", K_FACE, BITS.circle, "CIRCLE", 0.913, 0.585, 1, FACE_SPREAD, 0);
    const cro = makeNode("cro", K_FACE, BITS.cross, "CROSS", 0.855, 0.700, 2, 0, FACE_SPREAD);
    const squ = makeNode("squ", K_FACE, BITS.square, "SQUARE", 0.797, 0.585, 3, -FACE_SPREAD, 0);

    const l2 = makeNode("l2", K_SHOULDER, BITS.l2, "L2", 0.115, 0.105);
    const l1 = makeNode("l1", K_SHOULDER, BITS.l1, "L1", 0.115, 0.2475);
    const r2 = makeNode("r2", K_SHOULDER, BITS.r2, "R2", 0.885, 0.105);
    const r1 = makeNode("r1", K_SHOULDER, BITS.r1, "R1", 0.885, 0.2475);
    const select = makeNode("select", K_PILL, BITS.select, "SELECT", 0.435, 0.935);
    select.bw = 0.078; select.bh = 0.042;
    const start = makeNode("start", K_PILL, BITS.start, "START", 0.565, 0.935);
    start.bw = 0.078; start.bh = 0.042;
    const ps = makeNode("ps", K_PS, BITS.ps, "PS", 0.5, 0.80);
    ps.bw = 0.05; ps.bh = 0.05;
    const l3 = makeNode("l3", K_PILL, BITS.l3, "L3", 0.265, 0.955);
    l3.bw = 0.055; l3.bh = 0.033;
    const r3 = makeNode("r3", K_PILL, BITS.r3, "R3", 0.705, 0.955);
    r3.bw = 0.055; r3.bh = 0.033;

    nodes.length = 0;
    faceNodes.length = 0;
    nodes.push(dpadNode, leftStick, rightStick, tri, cir, cro, squ);
    faceNodes.push(tri, cir, cro, squ);
    nodes.push(l2, l1, r2, r1, select, start, ps, l3, r3);
    rebuildPlayOrder();
  }

  function rebuildPlayOrder() {
    playOrder.length = 0;
    playOrder.push(dpadNode);
    for (const node of nodes) {
      if (node.kind === K_SHOULDER || node.kind === K_PILL || node.kind === K_PS) {
        playOrder.push(node);
      }
    }
    for (const node of faceNodes) playOrder.push(node);
    playOrder.push(leftStick, rightStick);
  }

  function extW(node) {
    return node.kind === K_PILL || node.kind === K_PS || node.kind === K_SHOULDER
      ? node.hw : node.r;
  }

  function extH(node) {
    return node.kind === K_PILL || node.kind === K_PS || node.kind === K_SHOULDER
      ? node.hh : node.r;
  }

  function aspectScale() {
    return Math.min(1, (W / H) / 1.75);
  }

  function px(value) {
    return value * Math.max(0.75, Math.min(2.4, H / 800));
  }

  function layoutControls() {
    if (W <= 0 || H <= 0) return;
    const a = aspectScale();
    const baseX = Math.min(W * 0.855, W - H * 0.20);
    const baseY = H * 0.585;

    for (const node of nodes) {
      switch (node.kind) {
        case K_DPAD:
          node.r = H * 0.145 * a * node.scale;
          break;
        case K_STICK_L:
        case K_STICK_R:
          node.r = H * 0.14 * a * node.scale;
          break;
        case K_FACE:
          node.r = H * 0.058 * a * node.scale;
          if (!node.customized) {
            node.fx = (baseX + node.homeDx * H * a) / W;
            node.fy = (baseY + node.homeDy * H * a) / H;
            node.defFx = node.fx;
            node.defFy = node.fy;
          }
          break;
        case K_PILL:
          node.hw = H * node.bw * node.scale;
          node.hh = H * node.bh * node.scale;
          break;
        case K_PS:
          node.r = H * node.bw * node.scale;
          node.hw = node.r;
          node.hh = node.r;
          break;
        case K_SHOULDER:
          node.hw = W * 0.0975 * node.scale;
          node.hh = H * 0.0625 * node.scale;
          break;
      }

      const pad = H * 0.012;
      const ex = extW(node) + pad;
      const ey = extH(node) + pad;
      node.cx = Math.max(ex, Math.min(node.fx * W, W - ex));
      node.cy = Math.max(ey, Math.min(node.fy * H, H - ey));
    }
  }

  // ---------------------------------------------------------------- drawing
  function roundRectPath(x, y, w, h, r) {
    const radius = Math.max(0, Math.min(r, Math.min(Math.abs(w), Math.abs(h)) / 2));
    ctx.beginPath();
    ctx.moveTo(x + radius, y);
    ctx.lineTo(x + w - radius, y);
    ctx.arcTo(x + w, y, x + w, y + radius, radius);
    ctx.lineTo(x + w, y + h - radius);
    ctx.arcTo(x + w, y + h, x + w - radius, y + h, radius);
    ctx.lineTo(x + radius, y + h);
    ctx.arcTo(x, y + h, x, y + h - radius, radius);
    ctx.lineTo(x, y + radius);
    ctx.arcTo(x, y, x + radius, y, radius);
    ctx.closePath();
  }

  function fontFor(size, bold) {
    const weight = bold ? 700 : 500;
    return `${weight} ${Math.max(1, size)}px system-ui, -apple-system, "Segoe UI", Roboto, Ubuntu, sans-serif`;
  }

  function trackedWidth(text, size, tracking, bold) {
    ctx.font = fontFor(size, bold);
    let width = 0;
    for (let i = 0; i < text.length; i++) {
      width += ctx.measureText(text[i]).width;
      if (i < text.length - 1) width += tracking;
    }
    return width;
  }

  function fitTrackedSize(text, maxWidth, maxHeight) {
    if (!text) return maxHeight;
    const width = trackedWidth(text, maxHeight, maxHeight * 0.09, true);
    if (width <= maxWidth || width <= 0) return maxHeight;
    return Math.max(1, (maxHeight * maxWidth) / width);
  }

  function drawTracked(text, cx, cy, size, color, tracking, bold) {
    if (!text || size <= 0) return;
    ctx.font = fontFor(size, bold);
    ctx.fillStyle = color;
    ctx.textAlign = "left";
    ctx.textBaseline = "alphabetic";
    const metrics = ctx.measureText(text);
    const ascent = metrics.actualBoundingBoxAscent || size * 0.72;
    const descent = metrics.actualBoundingBoxDescent || size * 0.22;
    const baseline = cy + (ascent - descent) / 2;
    let x = cx - trackedWidth(text, size, tracking, bold) / 2;
    for (let i = 0; i < text.length; i++) {
      const char = text[i];
      ctx.fillText(char, x, baseline);
      x += ctx.measureText(char).width + tracking;
    }
  }

  function drawWatermark() {
    const size = H * 0.0132;
    const tracking = size * 0.18;
    const width = trackedWidth(BRAND_MARK, size, tracking, true);
    drawTracked(BRAND_MARK, W * 0.018 + width / 2, H - size * 1.7, size, alpha(themeNow.muted, 0.59), tracking, true);
  }

  function drawPlaceholder(node) {
    const ex = extW(node) + px(7);
    const ey = extH(node) + px(7);
    ctx.fillStyle = alpha(themeNow.surfaceAlt, 0.22);
    roundRectPath(node.cx - ex, node.cy - ey, ex * 2, ey * 2, px(12));
    ctx.fill();
    ctx.strokeStyle = alpha(themeNow.muted, 0.63);
    ctx.lineWidth = px(1.2);
    ctx.setLineDash([px(7), px(5)]);
    roundRectPath(node.cx - ex, node.cy - ey, ex * 2, ey * 2, px(12));
    ctx.stroke();
    ctx.setLineDash([]);
    drawTracked(displayLabel(node), node.cx, node.cy, Math.min(H * 0.019, ey * 0.6), alpha(themeNow.muted, 0.86), H * 0.0035, true);
  }

  function drawDpad(node) {
    const r = node.r;
    const accent = themeNow.accent;

    ctx.fillStyle = themeNow.surface;
    ctx.beginPath();
    ctx.arc(node.cx, node.cy, r, 0, Math.PI * 2);
    ctx.fill();

    ctx.strokeStyle = alpha(themeNow.text, 0.12);
    ctx.lineWidth = px(1.2);
    ctx.beginPath();
    ctx.arc(node.cx, node.cy, r - px(0.6), 0, Math.PI * 2);
    ctx.stroke();

    ctx.strokeStyle = alpha(accent, 0.31);
    ctx.lineWidth = px(2);
    ctx.beginPath();
    ctx.arc(node.cx, node.cy, r - px(2), (205 * Math.PI) / 180, (335 * Math.PI) / 180);
    ctx.stroke();

    const armT = r * 0.30;
    const arm = (cx, cy, hw, hh, pressed, dx, dy) => {
      if (pressed) {
        ctx.fillStyle = alpha(accent, 0.22);
        roundRectPath(cx - hw - px(4), cy - hh - px(4), (hw + px(4)) * 2, (hh + px(4)) * 2, r * 0.2);
        ctx.fill();
      }
      ctx.fillStyle = pressed ? accent : themeNow.surfaceAlt;
      roundRectPath(cx - hw, cy - hh, hw * 2, hh * 2, r * 0.16);
      ctx.fill();
      ctx.strokeStyle = pressed ? alpha(accent, 0.86) : alpha(themeNow.text, 0.10);
      ctx.lineWidth = px(1);
      roundRectPath(cx - hw, cy - hh, hw * 2, hh * 2, r * 0.16);
      ctx.stroke();
      drawArrow(cx, cy, r, dx, dy, pressed ? themeNow.bgBottom : alpha(themeNow.text, 0.59));
    };

    arm(node.cx, node.cy - r * 0.55, armT, r * 0.36, node.up, 0, -1);
    arm(node.cx, node.cy + r * 0.55, armT, r * 0.36, node.down, 0, 1);
    arm(node.cx - r * 0.55, node.cy, r * 0.36, armT, node.left, -1, 0);
    arm(node.cx + r * 0.55, node.cy, r * 0.36, armT, node.right, 1, 0);

    ctx.fillStyle = themeNow.surfaceAlt;
    ctx.beginPath();
    ctx.arc(node.cx, node.cy, r * 0.19, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = node.pointer !== null ? accent : alpha(accent, 0.51);
    ctx.beginPath();
    ctx.arc(node.cx, node.cy, r * 0.055, 0, Math.PI * 2);
    ctx.fill();
  }

  function drawArrow(cx, cy, r, dx, dy, color) {
    const ax = cx + dx * r * 0.1;
    const ay = cy + dy * r * 0.1;
    const s = r * 0.11;
    ctx.beginPath();
    if (dy !== 0) {
      const dir = dy < 0 ? -1 : 1;
      ctx.moveTo(ax, ay + s * dir * 1.2);
      ctx.lineTo(ax - s, ay - s * dir * 0.5);
      ctx.lineTo(ax + s, ay - s * dir * 0.5);
    } else {
      const dir = dx < 0 ? -1 : 1;
      ctx.moveTo(ax + s * dir * 1.2, ay);
      ctx.lineTo(ax - s * dir * 0.5, ay - s);
      ctx.lineTo(ax - s * dir * 0.5, ay + s);
    }
    ctx.closePath();
    ctx.fillStyle = color;
    ctx.fill();
  }

  function drawStick(node) {
    const active = node.pointer !== null;
    const accent = node.kind === K_STICK_L ? themeNow.accent : themeNow.accent2;
    const r = node.r;

    ctx.fillStyle = themeNow.surface;
    ctx.beginPath();
    ctx.arc(node.cx, node.cy, r, 0, Math.PI * 2);
    ctx.fill();

    if (active) {
      ctx.strokeStyle = alpha(accent, 0.16);
      ctx.lineWidth = px(7);
      ctx.beginPath();
      ctx.arc(node.cx, node.cy, r - px(3), 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.strokeStyle = alpha(accent, active ? 0.90 : 0.27);
    ctx.lineWidth = active ? px(2.4) : px(1.3);
    ctx.beginPath();
    ctx.arc(node.cx, node.cy, r - px(1), 0, Math.PI * 2);
    ctx.stroke();

    ctx.strokeStyle = alpha(themeNow.text, 0.10);
    ctx.lineWidth = px(1);
    for (let i = 0; i < 4; i++) {
      const angle = ((45 + i * 90) * Math.PI) / 180;
      const c = Math.cos(angle);
      const s = Math.sin(angle);
      ctx.beginPath();
      ctx.moveTo(node.cx + c * r * 0.82, node.cy + s * r * 0.82);
      ctx.lineTo(node.cx + c * r * 0.93, node.cy + s * r * 0.93);
      ctx.stroke();
    }

    ctx.strokeStyle = alpha(themeNow.text, 0.07);
    ctx.lineWidth = px(1);
    ctx.beginPath();
    ctx.moveTo(node.cx - r * 0.92, node.cy);
    ctx.lineTo(node.cx + r * 0.92, node.cy);
    ctx.moveTo(node.cx, node.cy - r * 0.92);
    ctx.lineTo(node.cx, node.cy + r * 0.92);
    ctx.stroke();

    const kx = node.cx + node.kx * r;
    const ky = node.cy + node.ky * r;
    const kr = r * 0.52;

    if (active) {
      ctx.fillStyle = alpha(accent, 0.20);
      ctx.beginPath();
      ctx.arc(kx, ky, kr * 1.3, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.fillStyle = active ? blendHex(themeNow.surfaceAlt, accent, 0.2) : themeNow.surfaceAlt;
    ctx.beginPath();
    ctx.arc(kx, ky, kr, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = active ? alpha(accent, 0.94) : alpha(themeNow.text, 0.22);
    ctx.lineWidth = active ? px(2.2) : px(1.3);
    ctx.beginPath();
    ctx.arc(kx, ky, kr, 0, Math.PI * 2);
    ctx.stroke();

    ctx.fillStyle = active ? accent : alpha(accent, 0.67);
    ctx.beginPath();
    ctx.arc(kx, ky, kr * 0.16, 0, Math.PI * 2);
    ctx.fill();

    if (!active) {
      drawTracked(
        node.kind === K_STICK_L ? "L STICK" : "R STICK",
        node.cx, node.cy + r * 0.72, r * 0.105,
        alpha(themeNow.text, 0.37), r * 0.028, false,
      );
    }
  }

  function faceColor(index) {
    if (index === 0) return themeNow.triangle;
    if (index === 1) return themeNow.circle;
    if (index === 2) return themeNow.cross;
    return themeNow.square;
  }

  function drawFace(node) {
    const accent = faceColor(node.faceIdx);
    const p = node.pressT;
    const r = node.r * (1 + 0.055 * p);
    const cx = node.cx;
    const cy = node.cy;

    if (p > 0.01) {
      ctx.fillStyle = alpha(accent, (70 * p) / 255);
      ctx.beginPath();
      ctx.arc(cx, cy, node.r * (1.10 + 0.22 * p), 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.fillStyle = blendHex(themeNow.surface, accent, 0.30 * p);
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
    ctx.fill();

    ctx.strokeStyle = alpha(accent, (150 + 105 * p) / 255);
    ctx.lineWidth = px(1.6 + 1.2 * p);
    ctx.beginPath();
    ctx.arc(cx, cy, r - px(0.7), 0, Math.PI * 2);
    ctx.stroke();

    ctx.strokeStyle = alpha(themeNow.text, (18 + 24 * p) / 255);
    ctx.lineWidth = px(1);
    ctx.beginPath();
    ctx.arc(cx, cy, r * 0.80, 0, Math.PI * 2);
    ctx.stroke();

    const ink = p > 0.55 ? themeNow.bgBottom : accent;
    if (prefs.faces === "ps") {
      drawGlyph(node.faceIdx, cx, cy, r, ink);
    } else {
      const letter = node.faceIdx === 0 ? "Y" : node.faceIdx === 1 ? "B" : node.faceIdx === 2 ? "A" : "X";
      drawTracked(letter, cx, cy, r * 0.95, ink, 0, true);
    }
  }

  function drawGlyph(index, cx, cy, r, color) {
    ctx.strokeStyle = color;
    ctx.lineWidth = r * 0.20;
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    const g = r * 0.40;
    if (index === 0) {
      ctx.beginPath();
      ctx.moveTo(cx, cy - g * 1.12);
      ctx.lineTo(cx + g * 1.02, cy + g * 0.72);
      ctx.lineTo(cx - g * 1.02, cy + g * 0.72);
      ctx.closePath();
      ctx.stroke();
    } else if (index === 1) {
      ctx.beginPath();
      ctx.arc(cx, cy, g, 0, Math.PI * 2);
      ctx.stroke();
    } else if (index === 2) {
      ctx.beginPath();
      ctx.moveTo(cx - g, cy - g);
      ctx.lineTo(cx + g, cy + g);
      ctx.moveTo(cx + g, cy - g);
      ctx.lineTo(cx - g, cy + g);
      ctx.stroke();
    } else {
      ctx.beginPath();
      ctx.rect(cx - g, cy - g, g * 2, g * 2);
      ctx.stroke();
    }
  }

  function displayLabel(node) {
    if (prefs.faces === "xbox") {
      if (node.key === "l1") return "LB";
      if (node.key === "l2") return "LT";
      if (node.key === "r1") return "RB";
      if (node.key === "r2") return "RT";
    }
    return node.label;
  }

  function drawPill(node) {
    const accent = themeNow.accent;
    const pressed = node.pressed;

    if (node.kind === K_PS) {
      if (pressed) {
        ctx.fillStyle = alpha(accent, 0.27);
        ctx.beginPath();
        ctx.arc(node.cx, node.cy, node.r * 1.22, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.fillStyle = pressed ? blendHex(themeNow.surface, accent, 0.45) : themeNow.surface;
      ctx.beginPath();
      ctx.arc(node.cx, node.cy, node.r, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = pressed ? accent : alpha(themeNow.text, 0.19);
      ctx.lineWidth = pressed ? px(2.2) : px(1.3);
      ctx.beginPath();
      ctx.arc(node.cx, node.cy, node.r - px(0.6), 0, Math.PI * 2);
      ctx.stroke();
      drawTracked(node.label, node.cx, node.cy, node.r * 0.60, pressed ? themeNow.bgBottom : themeNow.textDim, node.r * 0.08, true);
      return;
    }

    const radius = node.hh > node.hw ? Math.min(node.hw, node.hh) * 0.55 : node.hh * 0.55;
    if (pressed) {
      ctx.fillStyle = alpha(accent, 0.22);
      roundRectPath(node.cx - node.hw - px(3), node.cy - node.hh - px(3), (node.hw + px(3)) * 2, (node.hh + px(3)) * 2, radius + px(3));
      ctx.fill();
    }
    ctx.fillStyle = pressed ? blendHex(themeNow.surface, accent, 0.42) : themeNow.surface;
    roundRectPath(node.cx - node.hw, node.cy - node.hh, node.hw * 2, node.hh * 2, radius);
    ctx.fill();
    ctx.strokeStyle = pressed ? accent : alpha(themeNow.text, 0.16);
    ctx.lineWidth = pressed ? px(2) : px(1.2);
    roundRectPath(node.cx - node.hw, node.cy - node.hh, node.hw * 2, node.hh * 2, radius);
    ctx.stroke();

    const label = displayLabel(node);
    const size = fitTrackedSize(label, node.hw * 1.6, Math.min(node.hh * 1.05, node.hw * 0.60));
    drawTracked(label, node.cx, node.cy, size, pressed ? themeNow.bgBottom : themeNow.textDim, size * 0.09, true);
  }

  function statusColor() {
    if (statusKind === STATUS_OK) return themeNow.ok;
    if (statusKind === STATUS_WARN) return themeNow.warn;
    return themeNow.muted;
  }

  function drawGear(cx, cy, r, color) {
    ctx.strokeStyle = color;
    ctx.lineWidth = px(1.2);
    ctx.beginPath();
    ctx.arc(cx, cy, r * 0.55, 0, Math.PI * 2);
    ctx.stroke();
    for (let i = 0; i < 6; i++) {
      const angle = (Math.PI / 3) * i;
      const c = Math.cos(angle);
      const s = Math.sin(angle);
      ctx.beginPath();
      ctx.moveTo(cx + c * r * 0.75, cy + s * r * 0.75);
      ctx.lineTo(cx + c * r * 1.05, cy + s * r * 1.05);
      ctx.stroke();
    }
  }

  function drawHud() {
    const h = H * 0.066;
    let textSize = H * 0.026;
    let tracking = textSize * 0.10;
    let tw = trackedWidth(statusText, textSize, tracking, true);

    const maxText = W * 0.82 - h * 1.42;
    if (tw > maxText && tw > 0) {
      textSize *= maxText / tw;
      tracking = textSize * 0.10;
      tw = trackedWidth(statusText, textSize, tracking, true);
    }

    const w = h * 0.62 + tw + h * 0.80;
    const x = W / 2 - w / 2;
    const y = H * 0.030;
    hud.x = x;
    hud.y = y;
    hud.w = w;
    hud.h = h;

    if (hudPressed) {
      ctx.fillStyle = alpha(themeNow.accent, 0.20);
      roundRectPath(x - px(4), y - px(4), w + px(8), h + px(8), h / 2);
      ctx.fill();
    }
    ctx.fillStyle = alpha(themeNow.surface, 0.91);
    roundRectPath(x, y, w, h, h / 2);
    ctx.fill();
    ctx.strokeStyle = hudPressed ? alpha(themeNow.accent, 0.86) : alpha(themeNow.text, 0.13);
    ctx.lineWidth = px(1.2);
    roundRectPath(x, y, w, h, h / 2);
    ctx.stroke();

    const dotX = x + h * 0.38;
    const dotR = h * 0.10;
    const color = statusColor();
    ctx.fillStyle = alpha(color, 0.27);
    ctx.beginPath();
    ctx.arc(dotX, y + h / 2, dotR * 1.9, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(dotX, y + h / 2, dotR, 0, Math.PI * 2);
    ctx.fill();

    if (statusPulse > 0) {
      ctx.strokeStyle = alpha(color, (170 * statusPulse) / 255);
      ctx.lineWidth = px(1.2);
      ctx.beginPath();
      ctx.arc(dotX, y + h / 2, dotR * (1.3 + 3.0 * (1 - statusPulse)), 0, Math.PI * 2);
      ctx.stroke();
    }

    drawTracked(statusText, x + h * 0.62 + tw / 2, y + h / 2, textSize, themeNow.text, tracking, true);
    drawGear(x + w - h * 0.40, y + h / 2, h * 0.19, alpha(themeNow.text, 0.59));
  }

  function drawEditOverlay() {
    for (const node of nodes) {
      if (node.hidden) continue;
      const ex = extW(node) + px(7);
      const ey = extH(node) + px(7);
      const selected = node === editNode;
      if (selected) {
        ctx.fillStyle = alpha(themeNow.accent, 0.09);
        roundRectPath(node.cx - ex, node.cy - ey, ex * 2, ey * 2, px(12));
        ctx.fill();
      }
      ctx.strokeStyle = alpha(selected ? themeNow.accent : themeNow.muted, selected ? 0.90 : 0.41);
      ctx.lineWidth = selected ? px(1.8) : px(1.1);
      ctx.setLineDash([px(7), px(5)]);
      roundRectPath(node.cx - ex, node.cy - ey, ex * 2, ey * 2, px(12));
      ctx.stroke();
      ctx.setLineDash([]);

      const label = `${node.label}  ${Math.round(node.scale * 100)}%`;
      const size = H * 0.017;
      const tracking = size * 0.09;
      const textWidth = trackedWidth(label, size, tracking, true);
      const lx = Math.max(textWidth / 2 + px(4), Math.min(node.cx, W - textWidth / 2 - px(4)));
      const ly = node.cy - ey > H * 0.10 ? node.cy - ey - px(10) : node.cy + ey + px(14);
      drawTracked(label, lx, ly, size, alpha(themeNow.text, 0.76), tracking, true);
    }

    drawTracked(
      "DRAG TO MOVE   \u00b7   PINCH TO RESIZE   \u00b7   HOLD TO HIDE",
      W / 2, H * 0.44, H * 0.019, alpha(themeNow.muted, 0.88), H * 0.0048, true,
    );
  }

  function render() {
    if (W <= 0 || H <= 0) return;
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    ctx.globalAlpha = 1;

    const bg = ctx.createLinearGradient(0, 0, 0, H);
    bg.addColorStop(0, themeNow.bgTop);
    bg.addColorStop(0.58, themeNow.bgMid);
    bg.addColorStop(1, themeNow.bgBottom);
    ctx.fillStyle = bg;
    ctx.fillRect(0, 0, W, H);

    const glow = ctx.createRadialGradient(W * 0.5, -H * 0.04, 0, W * 0.5, -H * 0.04, H * 1.05);
    glow.addColorStop(0, alpha(themeNow.accent, (themeNow.dark ? 40 : 20) / 255));
    glow.addColorStop(1, alpha(themeNow.accent2, 0));
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, W, H);

    ctx.save();
    if (enterT < 1) {
      const k = 1 - Math.pow(1 - enterT, 3);
      ctx.globalAlpha = k;
      ctx.translate(W / 2, H / 2);
      const scale = 0.982 + 0.018 * k;
      ctx.scale(scale, scale);
      ctx.translate(-W / 2, -H / 2);
    }

    for (const node of nodes) {
      if (node.hidden) {
        if (editMode) drawPlaceholder(node);
        continue;
      }
      if (node.kind === K_DPAD) drawDpad(node);
      else if (node.kind === K_STICK_L || node.kind === K_STICK_R) drawStick(node);
      else if (node.kind === K_FACE) drawFace(node);
      else drawPill(node);
    }
    ctx.restore();
    ctx.globalAlpha = 1;

    if (editMode) {
      drawEditOverlay();
    } else {
      drawHud();
      drawWatermark();
    }
  }

  // --------------------------------------------------------------- animation
  function requestFrame() {
    if (frameHandle) return;
    frameHandle = requestAnimationFrame(frame);
  }

  function stepAnimations(dt) {
    const step = Math.max(0.25, Math.min(3, dt / 16.667));
    let active = false;

    for (const node of nodes) {
      const target = node.pressed || node.pointer !== null ? 1 : 0;
      const delta = target - node.pressT;
      if (Math.abs(delta) < 0.003 && Math.abs(node.pressVel) < 0.003) {
        node.pressT = target;
        node.pressVel = 0;
        continue;
      }
      node.pressVel += (delta * PRESS_STIFFNESS - node.pressVel * PRESS_DAMPING) * step;
      node.pressT = Math.max(-0.02, Math.min(1.15, node.pressT + node.pressVel * step));
      active = true;
    }

    if (themeT < 1) {
      themeT = Math.min(1, themeT + dt / 300);
      const e = themeT * themeT * themeT * (themeT * (themeT * 6 - 15) + 10);
      themeNow = themeT >= 1 ? themeTarget : blendThemes(themeFrom, themeTarget, e);
      applyCssTheme(themeNow);
      active = true;
    }

    if (enterT < 1) {
      enterT = Math.min(1, enterT + dt / 460);
      active = true;
    }

    if (statusPulse > 0) {
      statusPulse = Math.max(0, statusPulse - dt / 620);
      active = true;
    }
    return active;
  }

  function frame(now) {
    frameHandle = 0;
    const dt = lastFrameAt === 0 ? 16 : Math.max(1, Math.min(48, now - lastFrameAt));
    lastFrameAt = now;
    const active = stepAnimations(dt);
    if (dirty || active) {
      render();
      dirty = false;
    }
    if (active) requestFrame();
    else lastFrameAt = 0;
  }

  function resize() {
    const rect = canvas.getBoundingClientRect();
    W = Math.max(1, rect.width);
    H = Math.max(1, rect.height);
    DPR = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
    canvas.width = Math.round(W * DPR);
    canvas.height = Math.round(H * DPR);
    layoutControls();
    dirty = true;
    requestFrame();
    if (!entered) {
      entered = true;
      enterT = 0;
    }
  }

  buildNodes();
  applyCssTheme(themeNow);
  window.addEventListener("resize", resize);
  if (window.visualViewport) window.visualViewport.addEventListener("resize", resize);

  // ------------------------------------------------------------------- input
  const holds = new Map(); // node key -> Set of source ids ("p1", "kKeyQ")
  const keys = new Set();
  const keyDpad = { up: false, down: false, left: false, right: false };
  let hudPointer = null;
  let lastRumbleAt = 0;

  let editNode = null;
  let editPointer = null;
  let pinchPointer = null;
  const editPoints = new Map(); // pointer id -> { x, y }
  let dragOffX = 0;
  let dragOffY = 0;
  let downX = 0;
  let downY = 0;
  let moved = false;
  let pinchStartDist = 1;
  let pinchStartScale = 1;
  let longPressTimer = 0;

  function nodeByKey(key) {
    return nodes.find((node) => node.key === key) || null;
  }

  function localPoint(event) {
    const rect = canvas.getBoundingClientRect();
    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  }

  function triggerHaptic(ms) {
    if (!prefs.haptics || prefs.strength <= 0) return;
    if (!("vibrate" in navigator)) return;
    try {
      navigator.vibrate(Math.max(1, Math.round((ms * prefs.strength) / 100)));
    } catch (_error) {
      /* some browsers block vibration without a gesture */
    }
  }

  function sourceSet(node) {
    let set = holds.get(node.key);
    if (!set) {
      set = new Set();
      holds.set(node.key, set);
    }
    return set;
  }

  function hold(node, source) {
    if (!node) return;
    sourceSet(node).add(source);
    node.pressed = true;
    dirty = true;
    requestFrame();
  }

  function unhook(node, source) {
    if (!node) return;
    const set = holds.get(node.key);
    if (set) {
      set.delete(source);
      if (set.size === 0) holds.delete(node.key);
    }
    node.pressed = holds.has(node.key);
    dirty = true;
    requestFrame();
  }

  function hitNode(node, x, y) {
    if (node.kind === K_PILL || node.kind === K_PS || node.kind === K_SHOULDER) {
      return x >= node.cx - node.hw * 1.1 && x <= node.cx + node.hw * 1.1
        && y >= node.cy - node.hh * 1.15 && y <= node.cy + node.hh * 1.15;
    }
    if (node.kind === K_FACE) {
      return Math.hypot(x - node.cx, y - node.cy) <= node.r * 1.4;
    }
    return Math.hypot(x - node.cx, y - node.cy) <= node.r * 1.5;
  }

  function nearestFace(x, y, except) {
    let best = null;
    let bestDistance = Infinity;
    for (const node of faceNodes) {
      if (node.hidden) continue;
      if (node.pointer !== null && node !== except) continue;
      const distance = Math.hypot(x - node.cx, y - node.cy);
      if (distance <= node.r * 1.55 && distance < bestDistance) {
        bestDistance = distance;
        best = node;
      }
    }
    return best;
  }

  function moveStick(node, x, y) {
    let dx = x - node.cx;
    let dy = y - node.cy;
    const distance = Math.hypot(dx, dy);
    if (distance > node.r) {
      dx = (dx / distance) * node.r;
      dy = (dy / distance) * node.r;
    }
    node.kx = dx / node.r;
    node.ky = dy / node.r;
    dirty = true;
    requestFrame();
  }

  function moveDpad(node, x, y) {
    const dx = x - node.cx;
    const dy = y - node.cy;
    const threshold = node.r * 0.33;
    const up = dy < -threshold;
    const down = dy > threshold;
    const left = dx < -threshold;
    const right = dx > threshold;
    if (up !== node.up || down !== node.down || left !== node.left || right !== node.right) {
      node.up = up;
      node.down = down;
      node.left = left;
      node.right = right;
      dirty = true;
      requestFrame();
    }
  }

  function padDown(event) {
    const { x, y } = localPoint(event);
    const id = event.pointerId;
    const source = "p" + id;

    if (hudPointer === null
      && x >= hud.x && x <= hud.x + hud.w
      && y >= hud.y && y <= hud.y + hud.h) {
      hudPointer = id;
      hudPressed = true;
      triggerHaptic(8);
      dirty = true;
      requestFrame();
      return;
    }

    const face = nearestFace(x, y, null);
    if (face && face.pointer === null) {
      face.pointer = id;
      hold(face, source);
      triggerHaptic(12);
      return;
    }

    for (const node of playOrder) {
      if (node.kind === K_FACE) continue;
      if (node.hidden || node.pointer !== null || !hitNode(node, x, y)) continue;
      node.pointer = id;
      if (node.kind === K_DPAD) {
        moveDpad(node, x, y);
        triggerHaptic(10);
      } else if (node.kind === K_STICK_L || node.kind === K_STICK_R) {
        moveStick(node, x, y);
        triggerHaptic(10);
      } else {
        hold(node, source);
        triggerHaptic(12);
      }
      return;
    }
  }

  function padMove(event) {
    const { x, y } = localPoint(event);
    const id = event.pointerId;

    if (hudPointer === id) {
      hudPressed = x >= hud.x && x <= hud.x + hud.w && y >= hud.y && y <= hud.y + hud.h;
      dirty = true;
      requestFrame();
      return;
    }

    for (const node of playOrder) {
      if (node.pointer !== id) continue;
      if (node.kind === K_DPAD) moveDpad(node, x, y);
      else if (node.kind === K_STICK_L || node.kind === K_STICK_R) moveStick(node, x, y);
      else if (node.kind === K_FACE) rollFace(node, id, x, y);
      else {
        const inside = hitNode(node, x, y);
        if (inside) hold(node, "p" + id);
        else unhook(node, "p" + id);
      }
      return;
    }
  }

  function rollFace(node, id, x, y) {
    const target = nearestFace(x, y, node);
    if (target === node) {
      hold(node, "p" + id);
      return;
    }
    if (target) {
      unhook(node, "p" + id);
      node.pointer = null;
      target.pointer = id;
      hold(target, "p" + id);
      triggerHaptic(12);
    } else {
      unhook(node, "p" + id);
    }
  }

  function padUp(event) {
    const { x, y } = localPoint(event);
    const id = event.pointerId;

    if (hudPointer === id) {
      hudPointer = null;
      hudPressed = false;
      dirty = true;
      requestFrame();
      if (x >= hud.x && x <= hud.x + hud.w && y >= hud.y && y <= hud.y + hud.h) {
        openSettings();
      }
      return;
    }

    for (const node of playOrder) {
      if (node.pointer !== id) continue;
      node.pointer = null;
      if (node.kind === K_STICK_L || node.kind === K_STICK_R) {
        node.kx = 0;
        node.ky = 0;
      } else if (node.kind === K_DPAD) {
        node.up = false;
        node.down = false;
        node.left = false;
        node.right = false;
      }
      unhook(node, "p" + id);
      return;
    }
  }

  function resetInputs() {
    holds.clear();
    keys.clear();
    keyDpad.up = false;
    keyDpad.down = false;
    keyDpad.left = false;
    keyDpad.right = false;
    for (const node of nodes) {
      node.pressed = false;
      node.pointer = null;
      node.up = false;
      node.down = false;
      node.left = false;
      node.right = false;
      node.kx = 0;
      node.ky = 0;
    }
    hudPointer = null;
    hudPressed = false;
    dirty = true;
    requestFrame();
  }

  function keyboardAxis(side) {
    let x = 0;
    let y = 0;
    if (side === "l") {
      if (keys.has("KeyA")) x -= 1;
      if (keys.has("KeyD")) x += 1;
      if (keys.has("KeyW")) y -= 1;
      if (keys.has("KeyS")) y += 1;
    } else {
      if (keys.has("KeyJ")) x -= 1;
      if (keys.has("KeyL")) x += 1;
      if (keys.has("KeyI")) y -= 1;
      if (keys.has("KeyK")) y += 1;
    }
    const length = Math.hypot(x, y);
    if (length > 1) {
      x /= length;
      y /= length;
    }
    return { x, y };
  }

  function axes(node) {
    let kx = node.kx;
    let ky = node.ky;
    if (node.pointer === null) {
      const keyboard = keyboardAxis(node.kind === K_STICK_L ? "l" : "r");
      kx = keyboard.x;
      ky = keyboard.y;
    }
    const magnitude = Math.hypot(kx, ky);
    const deadzone = prefs.deadzone / 100;
    if (magnitude <= deadzone) return [0, 0];
    const scaled = Math.min(1, (magnitude - deadzone) / Math.max(0.0001, 1 - deadzone));
    const curve = prefs.curve / 100;
    const shaped = curve <= 0.001 ? scaled : scaled * (1 - curve + curve * scaled * scaled);
    const ux = (kx / magnitude) * shaped;
    const uy = (ky / magnitude) * shaped;
    return [Math.trunc(ux * 32767), Math.trunc(-uy * 32767)];
  }

  function snapshot() {
    let buttons = 0;
    for (const node of nodes) {
      if (node.kind === K_DPAD) {
        if (node.up || keyDpad.up) buttons |= 1 << BITS.up;
        if (node.down || keyDpad.down) buttons |= 1 << BITS.down;
        if (node.left || keyDpad.left) buttons |= 1 << BITS.left;
        if (node.right || keyDpad.right) buttons |= 1 << BITS.right;
      } else if (node.pressed) {
        buttons |= 1 << node.bit;
      }
    }
    const [lx, ly] = axes(leftStick);
    const [rx, ry] = axes(rightStick);
    return {
      b: buttons,
      lx, ly, rx, ry,
      l2: buttons & (1 << BITS.l2) ? 255 : 0,
      r2: buttons & (1 << BITS.r2) ? 255 : 0,
      w: prefs.slot,
    };
  }

  // --------------------------------------------------------------- edit mode
  function nodeAt(x, y) {
    for (let i = nodes.length - 1; i >= 0; i--) {
      const node = nodes[i];
      const ex = extW(node) + px(10);
      const ey = extH(node) + px(10);
      if (x >= node.cx - ex && x <= node.cx + ex && y >= node.cy - ey && y <= node.cy + ey) {
        return node;
      }
    }
    return null;
  }

  function editDown(event) {
    const { x, y } = localPoint(event);
    const id = event.pointerId;
    editPoints.set(id, { x, y });
    if (editNode === null) {
      const node = nodeAt(x, y);
      if (!node) return;
      editNode = node;
      editPointer = id;
      if (node.hidden) {
        node.hidden = false;
        toast(`${node.label} restored`);
      }
      dragOffX = node.cx - x;
      dragOffY = node.cy - y;
      downX = x;
      downY = y;
      moved = false;
      clearTimeout(longPressTimer);
      longPressTimer = setTimeout(() => {
        const target = editNode;
        if (!target || moved) return;
        target.hidden = true;
        triggerHaptic(20);
        toast(`${target.label} hidden \u00b7 SHOW ALL restores it`);
        dirty = true;
        requestFrame();
      }, 520);
      triggerHaptic(8);
      dirty = true;
      requestFrame();
      return;
    }

    if (pinchPointer === null) {
      pinchPointer = id;
      const primary = editPoints.get(editPointer) || { x: editNode.cx - dragOffX, y: editNode.cy - dragOffY };
      pinchStartDist = Math.max(1, Math.hypot(x - primary.x, y - primary.y));
      pinchStartScale = editNode.scale;
      moved = true;
      clearTimeout(longPressTimer);
    }
  }

  function editMove(event) {
    const node = editNode;
    if (!node) return;
    const id = event.pointerId;
    const { x, y } = localPoint(event);
    if (editPoints.has(id)) editPoints.set(id, { x, y });

    if (pinchPointer !== null) {
      if (id !== pinchPointer && id !== editPointer) return;
      const primary = editPoints.get(editPointer);
      const second = editPoints.get(pinchPointer);
      if (!primary || !second) return;
      const distance = Math.max(1, Math.hypot(second.x - primary.x, second.y - primary.y));
      node.scale = Math.max(0.55, Math.min(1.9, pinchStartScale * (distance / pinchStartDist)));
      if (node.kind === K_FACE) node.customized = true;
      layoutControls();
      dirty = true;
      requestFrame();
      return;
    }

    if (id !== editPointer) return;
    if (!moved && Math.hypot(x - downX, y - downY) > px(10)) {
      moved = true;
      clearTimeout(longPressTimer);
    }
    if (!moved) return;
    node.fx = Math.max(0, Math.min(1, (x + dragOffX) / W));
    node.fy = Math.max(0, Math.min(1, (y + dragOffY) / H));
    if (node.kind === K_FACE) node.customized = true;
    layoutControls();
    dirty = true;
    requestFrame();
  }

  function editUp(event) {
    const id = event.pointerId;
    editPoints.delete(id);
    if (id === pinchPointer) {
      pinchPointer = null;
      return;
    }
    if (id === editPointer) finishEdit();
  }

  function finishEdit() {
    clearTimeout(longPressTimer);
    editNode = null;
    editPointer = null;
    pinchPointer = null;
    editPoints.clear();
    moved = false;
    layoutControls();
    persistLayout();
    dirty = true;
    requestFrame();
  }

  function enterEditMode() {
    if (editMode) return;
    editMode = true;
    resetInputs();
    clearTimeout(longPressTimer);
    editNode = null;
    editPointer = null;
    pinchPointer = null;
    editPoints.clear();
    hudPressed = false;
    hudPointer = null;
    if (pipVisible()) setPipVisible(false);
    showEditBar();
    toast("Drag to move \u00b7 pinch to resize \u00b7 hold to hide");
    dirty = true;
    requestFrame();
  }

  function exitEditMode(save) {
    if (!editMode) return;
    if (save) persistLayout();
    editMode = false;
    clearTimeout(longPressTimer);
    editNode = null;
    hideEditBar();
    if (prefs.screen) setPipVisible(true);
    dirty = true;
    requestFrame();
  }

  function resetLayout() {
    for (const node of nodes) {
      node.scale = 1;
      node.hidden = false;
      node.customized = false;
      node.fx = node.defFx;
      node.fy = node.defFy;
    }
    layoutControls();
    dirty = true;
    requestFrame();
  }

  function showAll() {
    for (const node of nodes) node.hidden = false;
    dirty = true;
    requestFrame();
  }

  // ------------------------------------------------------------------ events
  canvas.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    try {
      canvas.setPointerCapture(event.pointerId);
    } catch (_error) {
      /* older browsers */
    }
    if (editMode) editDown(event);
    else padDown(event);
  });

  canvas.addEventListener("pointermove", (event) => {
    event.preventDefault();
    if (editMode) editMove(event);
    else padMove(event);
  });

  const finishPointer = (event) => {
    if (editMode) editUp(event);
    else padUp(event);
  };
  canvas.addEventListener("pointerup", finishPointer);
  canvas.addEventListener("pointercancel", finishPointer);
  canvas.addEventListener("lostpointercapture", finishPointer);
  canvas.addEventListener("contextmenu", (event) => event.preventDefault());

  // ----------------------------------------------------------------- keyboard
  const keyButtons = {
    ArrowUp: "@dpad-up",
    ArrowRight: "@dpad-right",
    ArrowDown: "@dpad-down",
    ArrowLeft: "@dpad-left",
    KeyQ: "l1",
    KeyE: "r1",
    Digit1: "l2",
    Digit2: "r2",
    Numpad1: "l2",
    Numpad2: "cro",
    Numpad4: "squ",
    Numpad6: "cir",
    Numpad8: "tri",
    Enter: "start",
    NumpadEnter: "start",
    ShiftLeft: "select",
    ShiftRight: "select",
    Backquote: "ps",
    KeyZ: "l3",
    KeyC: "r3",
  };
  const axisKeys = new Set(["KeyW", "KeyA", "KeyS", "KeyD", "KeyI", "KeyJ", "KeyK", "KeyL"]);

  function typingTarget(target) {
    const tag = (target && target.tagName ? target.tagName : "").toLowerCase();
    return tag === "input" || tag === "textarea" || tag === "select";
  }

  window.addEventListener("keydown", (event) => {
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    if (typingTarget(event.target)) return;
    if (!dialogScrim.hidden || sheetOpen) return;
    if (editMode) {
      if (event.code === "Escape") {
        event.preventDefault();
        exitEditMode(true);
      }
      return;
    }
    const target = keyButtons[event.code];
    if (target) {
      event.preventDefault();
      if (target.startsWith("@dpad-")) {
        keyDpad[target.slice(6)] = true;
        dirty = true;
        requestFrame();
      } else if (!event.repeat) {
        hold(nodeByKey(target), "k" + event.code);
      }
      return;
    }
    if (axisKeys.has(event.code)) {
      event.preventDefault();
      keys.add(event.code);
      dirty = true;
      requestFrame();
    }
  });

  window.addEventListener("keyup", (event) => {
    const target = keyButtons[event.code];
    if (target) {
      event.preventDefault();
      if (target.startsWith("@dpad-")) {
        keyDpad[target.slice(6)] = false;
        dirty = true;
        requestFrame();
      } else {
        unhook(nodeByKey(target), "k" + event.code);
      }
      return;
    }
    if (axisKeys.has(event.code)) {
      event.preventDefault();
      keys.delete(event.code);
      dirty = true;
      requestFrame();
    }
  });

  window.addEventListener("blur", resetInputs);
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) resetInputs();
  });

  // ------------------------------------------------------------------ network
  const VERSION = "1.6.4";
  let socket = null;
  let connected = false;
  let manualOffline = false;
  let retry = 0;
  let reconnectTimer = 0;
  let serverName = "lemoGamePad";
  let maxPlayers = MAX_PLAYERS;
  let screenAvailable = false;
  let assignedSlot = 0;
  let lastSnapshot = "";
  let lastSentAt = 0;
  let lastPingAt = 0;
  let lastRtt = -1;
  let rttHistory = [];

  function socketUrl(path) {
    const scheme = location.protocol === "https:" ? "wss:" : "ws:";
    return `${scheme}//${location.host}${path}${location.search}`;
  }

  function updateStatus() {
    if (!connected) {
      setStatus(manualOffline ? "OFFLINE" : "CONNECTING\u2026", manualOffline ? STATUS_IDLE : STATUS_WARN);
      return;
    }
    if (assignedSlot <= 0) {
      setStatus("WAITING FOR A PAD", STATUS_WARN);
      return;
    }
    const name = serverName || location.hostname;
    const rtt = lastRtt >= 0 ? `  \u00b7  ${lastRtt} ms` : "";
    setStatus(`${name}  \u00b7  P${assignedSlot}${rtt}`, STATUS_OK);
  }

  function openSocket() {
    clearTimeout(reconnectTimer);
    reconnectTimer = 0;
    if (manualOffline) return;
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return;

    updateStatus();
    let ws;
    try {
      ws = new WebSocket(socketUrl("/ws"));
    } catch (_error) {
      scheduleReconnect();
      return;
    }
    socket = ws;

    ws.addEventListener("open", () => {
      if (ws !== socket) return;
      connected = true;
      retry = 0;
      lastSnapshot = "";
      lastSentAt = 0;
      lastPingAt = 0;
      updateStatus();
      syncSheet();
    });

    ws.addEventListener("message", (event) => {
      if (ws !== socket) return;
      if (typeof event.data !== "string") return;
      let message;
      try {
        message = JSON.parse(event.data);
      } catch (_error) {
        return;
      }
      if (!message || typeof message !== "object") return;
      if (message.t === "h") {
        serverName = String(message.n || "lemoGamePad").slice(0, 32);
        maxPlayers = Math.max(1, Math.min(MAX_PLAYERS, Number(message.max) || MAX_PLAYERS));
        screenAvailable = Boolean(message.screen);
        updateStatus();
        syncSheet();
      } else if (message.t === "s") {
        assignedSlot = Math.max(0, Number(message.n) || 0);
        updateStatus();
        refreshSlotChips();
        syncStatus();
      } else if (message.t === "q") {
        if (typeof message.c === "number") {
          const sample = Math.max(0, Date.now() - message.c);
          if (sample < 60000) {
            lastRtt = sample;
            rttHistory.push(sample);
            if (rttHistory.length > RTT_HISTORY) rttHistory.shift();
          }
        }
        if (message.n) {
          const slot = Math.max(0, Number(message.n) || 0);
          if (slot !== assignedSlot) {
            assignedSlot = slot;
            refreshSlotChips();
            syncStatus();
          }
        }
        updateStatus();
        syncStatus();
      } else if (message.t === "r") {
        vibrateRumble(Number(message.s) || 0, Number(message.l) || 0);
      }
    });

    ws.addEventListener("close", () => {
      if (ws !== socket) return;
      socket = null;
      connected = false;
      assignedSlot = 0;
      lastSnapshot = "";
      updateStatus();
      refreshSlotChips();
      syncSheet();
      if (!manualOffline) scheduleReconnect();
    });

    ws.addEventListener("error", () => {
      /* the close event carries the retry */
    });
  }

  function dropSocket() {
    const ws = socket;
    socket = null;
    connected = false;
    assignedSlot = 0;
    lastSnapshot = "";
    if (ws) {
      ws.onclose = null;
      try {
        ws.close(1000, "client disconnect");
      } catch (_error) {
        /* already closed */
      }
    }
    updateStatus();
    refreshSlotChips();
    syncSheet();
  }

  function scheduleReconnect() {
    if (manualOffline || reconnectTimer) return;
    const delay = Math.min(5000, 300 * Math.pow(1.6, retry));
    retry = Math.min(retry + 1, 8);
    reconnectTimer = setTimeout(() => {
      reconnectTimer = 0;
      openSocket();
    }, delay);
  }

  function vibrateRumble(small, large) {
    if (!prefs.haptics || prefs.strength <= 0) return;
    if (!("vibrate" in navigator)) return;
    const strength = Math.max(Number(small) || 0, Number(large) || 0);
    if (strength <= 0) {
      try {
        navigator.vibrate(0);
      } catch (_error) {
        /* ignore */
      }
      return;
    }
    const now = Date.now();
    if (now - lastRumbleAt < 80) return;
    lastRumbleAt = now;
    const scaled = (strength / 255) * (prefs.strength / 100);
    try {
      navigator.vibrate(Math.max(30, Math.round(scaled * 180)));
    } catch (_error) {
      /* ignore */
    }
  }

  function inputTick() {
    if (!socket || socket.readyState !== WebSocket.OPEN) return;
    const now = performance.now();
    const state = snapshot();
    const payload = JSON.stringify({ t: "i", ...state });
    const interval = prefs.rate === "turbo" ? 16 : prefs.rate === "standard" ? 250 : 1000;
    if (payload !== lastSnapshot || now - lastSentAt >= interval) {
      lastSnapshot = payload;
      lastSentAt = now;
      try {
        socket.send(payload);
      } catch (_error) {
        /* the close handler reconnects */
      }
    }
    if (now - lastPingAt >= 1000) {
      lastPingAt = now;
      try {
        socket.send(JSON.stringify({ t: "p", c: Date.now() }));
      } catch (_error) {
        /* the close handler reconnects */
      }
    }
  }

  setInterval(inputTick, 16);

  // -------------------------------------------------------------------- sheet
  const scrim = $("#settings-scrim");
  const sheetDot = $("#sheet-dot");
  const sheetTitle = $("#sheet-status-title");
  const sheetSub = $("#sheet-status-sub");
  const sheetDetail = $("#sheet-detail");
  const latencyGraph = $("#latency-graph");
  const latencyStats = $("#latency-stats");
  const connectBtn = $("#connect-btn");
  const reconnectBtn = $("#reconnect-btn");
  const disconnectBtn = $("#disconnect-btn");
  const serverLine = $("#server-line");
  const slotRow = $("#slot-row");
  const slotLine = $("#slot-line");
  const themeGrid = $("#theme-grid");
  const layoutList = $("#layout-list");
  const layoutSummary = $("#layout-summary");
  const screenToggle = $("#screen-toggle");
  const screenHint = $("#screen-hint");
  const rateHint = $("#rate-hint");
  const pip = $("#screen-pip");
  const pipHint = $("#pip-hint");
  const pipBadge = $("#pip-badge");
  const pipCanvas = $("#screen-canvas");

  let sheetOpen = false;
  let sheetCloseTimer = 0;

  function openSettings() {
    clearTimeout(sheetCloseTimer);
    sheetOpen = true;
    syncSheet();
    scrim.hidden = false;
    requestAnimationFrame(() => scrim.classList.add("show"));
  }

  function closeSettings() {
    if (!sheetOpen) return;
    sheetOpen = false;
    scrim.classList.remove("show");
    clearTimeout(sheetCloseTimer);
    sheetCloseTimer = setTimeout(() => {
      if (!sheetOpen) scrim.hidden = true;
    }, 230);
  }

  function syncStatus() {
    if (!sheetOpen) return;
    const ok = connected;
    sheetDot.classList.toggle("ok", ok);
    sheetDot.classList.toggle("warn", !ok && !manualOffline);
    sheetTitle.textContent = connected ? "CONNECTED" : manualOffline ? "OFFLINE" : "LINKING\u2026";
    const parts = ["WEB"];
    if (connected && assignedSlot > 0) parts.push(`P${assignedSlot}`);
    if (connected && lastRtt >= 0) parts.push(`${lastRtt} ms`);
    parts.push(location.hostname);
    sheetSub.textContent = parts.join("  \u00b7  ");
    sheetDetail.textContent = `${serverName}\n${location.host}  \u00b7  browser pad  \u00b7  ${maxPlayers} pads`;

    const showStats = connected && rttHistory.length > 0;
    latencyGraph.hidden = !showStats;
    latencyStats.hidden = !showStats;
    if (showStats) {
      drawLatencyGraph();
      const avg = rttHistory.reduce((sum, value) => sum + value, 0) / rttHistory.length;
      const min = Math.min(...rttHistory);
      const max = Math.max(...rttHistory);
      let jitter = 0;
      for (let i = 1; i < rttHistory.length; i++) {
        jitter += Math.abs(rttHistory[i] - rttHistory[i - 1]);
      }
      jitter = rttHistory.length > 1 ? Math.round(jitter / (rttHistory.length - 1)) : 0;
      latencyStats.textContent = `avg ${Math.round(avg)} ms  \u00b7  min ${min}  \u00b7  max ${max}`
        + (jitter > 0 ? `  \u00b7  \u00b1${jitter} jitter` : "");
    }
  }

  function drawLatencyGraph() {
    const rect = latencyGraph.getBoundingClientRect();
    const cssWidth = Math.max(40, rect.width || 260);
    const cssHeight = 44;
    const dpr = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
    if (latencyGraph.width !== Math.round(cssWidth * dpr)) {
      latencyGraph.width = Math.round(cssWidth * dpr);
      latencyGraph.height = Math.round(cssHeight * dpr);
    }
    const g = latencyGraph.getContext("2d");
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, cssWidth, cssHeight);
    const samples = rttHistory;
    if (samples.length < 1) return;

    const min = Math.min(...samples);
    const max = Math.max(...samples);
    const span = Math.max(1, max - min);
    const left = 3;
    const right = cssWidth - 3;
    const top = 6;
    const bottom = cssHeight - 6;
    g.strokeStyle = alpha(themeNow.text, 0.10);
    g.lineWidth = 1;
    g.beginPath();
    g.moveTo(left, bottom);
    g.lineTo(right, bottom);
    g.stroke();

    const points = samples.map((value, index) => {
      const x = samples.length === 1 ? right : left + ((right - left) * index) / (samples.length - 1);
      const y = bottom - ((bottom - top) * (value - min)) / span;
      return [x, y];
    });
    g.strokeStyle = themeNow.accent;
    g.lineWidth = 1.5;
    g.lineJoin = "round";
    g.beginPath();
    g.moveTo(points[0][0], points[0][1]);
    for (let i = 1; i < points.length; i++) g.lineTo(points[i][0], points[i][1]);
    g.stroke();
    const last = points[points.length - 1];
    g.fillStyle = themeNow.accent;
    g.beginPath();
    g.arc(last[0], last[1], 2.4, 0, Math.PI * 2);
    g.fill();
  }

  function syncSheet() {
    if (!sheetOpen) return;
    syncStatus();
    serverLine.textContent = `${serverName}  \u00b7  ${location.host}  \u00b7  up to ${maxPlayers} pads`;
    connectBtn.disabled = connected;
    reconnectBtn.disabled = false;
    disconnectBtn.disabled = manualOffline && !connected;
    syncSlotChips();
    syncThemeGrid();
    syncFaces();
    syncControls();
    syncLayouts();
    syncScreen();
  }

  // --------------------------------------------------------------- slot chips
  function buildSlotRow() {
    slotRow.textContent = "";
    for (let slot = 0; slot <= MAX_PLAYERS; slot++) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "chip";
      button.dataset.slot = String(slot);
      button.textContent = slot === 0 ? "AUTO" : `P${slot}`;
      button.addEventListener("click", () => chooseSlot(slot));
      slotRow.appendChild(button);
    }
    refreshSlotChips();
  }

  function refreshSlotChips() {
    for (const button of slotRow.querySelectorAll("[data-slot]")) {
      const slot = Number(button.dataset.slot);
      const selected = slot === prefs.slot;
      const isAssigned = slot !== 0 && slot === assignedSlot;
      button.classList.toggle("on", selected);
      button.classList.toggle("assigned", isAssigned && !selected);
      button.setAttribute("aria-pressed", selected ? "true" : "false");
    }
  }

  function syncSlotChips() {
    refreshSlotChips();
    if (!connected) {
      slotLine.textContent = "Waiting for the PC\u2026";
    } else if (assignedSlot <= 0) {
      slotLine.textContent = "Waiting for the PC to assign a pad\u2026";
    } else if (prefs.slot === 0) {
      slotLine.textContent = `Using P${assignedSlot} (AUTO).`;
    } else if (prefs.slot === assignedSlot) {
      slotLine.textContent = `Using P${assignedSlot}.`;
    } else {
      slotLine.textContent = `P${prefs.slot} is busy \u2014 using P${assignedSlot} until it frees up.`;
    }
  }

  function chooseSlot(slot) {
    prefs.slot = Math.max(0, Math.min(MAX_PLAYERS, slot));
    savePref(PREF.slot, prefs.slot);
    lastSnapshot = "";
    syncSlotChips();
    toast(prefs.slot === 0 ? "Player slot: automatic" : `Requesting P${prefs.slot}`);
  }

  // ------------------------------------------------------------------ themes
  function buildThemeGrid() {
    themeGrid.textContent = "";
    for (const theme of THEMES) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "swatch";
      button.dataset.theme = theme.id;
      button.setAttribute("aria-pressed", "false");

      const preview = document.createElement("span");
      preview.className = "swatch-preview";
      const bg = document.createElement("span");
      bg.className = "swatch-bg";
      bg.style.background = `linear-gradient(180deg, ${theme.bgTop}, ${theme.bgBottom})`;
      bg.style.border = `1px solid ${alpha(theme.text, 0.18)}`;
      const bar = document.createElement("span");
      bar.className = "swatch-bar";
      bar.style.background = theme.accent;
      preview.append(bg, bar);

      const name = document.createElement("span");
      name.className = "swatch-name";
      name.textContent = theme.name;
      button.append(preview, name);
      button.addEventListener("click", () => chooseTheme(theme.id));
      themeGrid.appendChild(button);
    }
    syncThemeGrid();
  }

  function chooseTheme(id) {
    setTheme(id);
    syncThemeGrid();
    dirty = true;
    requestFrame();
  }

  function syncThemeGrid() {
    for (const button of themeGrid.querySelectorAll("[data-theme]")) {
      const selected = button.dataset.theme === themeTarget.id;
      button.classList.toggle("on", selected);
      button.setAttribute("aria-pressed", selected ? "true" : "false");
    }
  }

  function syncFaces() {
    for (const button of document.querySelectorAll("[data-faces]")) {
      const selected = button.dataset.faces === prefs.faces;
      button.classList.toggle("on", selected);
      button.setAttribute("aria-pressed", selected ? "true" : "false");
    }
  }

  // ---------------------------------------------------------------- controls
  const deadzoneEl = $("#deadzone");
  const deadzoneLabel = $("#deadzone-label");
  const curveEl = $("#curve");
  const curveLabel = $("#curve-label");
  const hapticsToggle = $("#haptics-toggle");
  const strengthEl = $("#strength");
  const strengthLabel = $("#strength-label");

  function syncControls() {
    deadzoneEl.value = String(Math.round(prefs.deadzone));
    deadzoneLabel.textContent = `STICK DEADZONE  \u00b7  ${Math.round(prefs.deadzone)}%`;
    curveEl.value = String(Math.round(prefs.curve));
    curveLabel.textContent = `STICK CURVE  \u00b7  ${Math.round(prefs.curve)}%`;
    hapticsToggle.textContent = prefs.haptics ? "ON" : "OFF";
    hapticsToggle.classList.toggle("on", prefs.haptics);
    hapticsToggle.setAttribute("aria-pressed", prefs.haptics ? "true" : "false");
    strengthEl.value = String(Math.round(prefs.strength));
    strengthLabel.textContent = `VIBRATION STRENGTH  \u00b7  ${Math.round(prefs.strength)}%`;
    for (const button of document.querySelectorAll("[data-rate]")) {
      const selected = button.dataset.rate === prefs.rate;
      button.classList.toggle("on", selected);
      button.setAttribute("aria-pressed", selected ? "true" : "false");
    }
    rateHint.textContent = RATE_INFO[prefs.rate].hint;
  }

  function bindControls() {
    deadzoneEl.addEventListener("input", () => {
      prefs.deadzone = Number(deadzoneEl.value) || 0;
      deadzoneLabel.textContent = `STICK DEADZONE  \u00b7  ${Math.round(prefs.deadzone)}%`;
      dirty = true;
      requestFrame();
    });
    deadzoneEl.addEventListener("change", () => savePref(PREF.deadzone, Math.round(prefs.deadzone)));

    curveEl.addEventListener("input", () => {
      prefs.curve = Number(curveEl.value) || 0;
      curveLabel.textContent = `STICK CURVE  \u00b7  ${Math.round(prefs.curve)}%`;
      dirty = true;
      requestFrame();
    });
    curveEl.addEventListener("change", () => savePref(PREF.curve, Math.round(prefs.curve)));

    hapticsToggle.addEventListener("click", () => {
      prefs.haptics = !prefs.haptics;
      savePref(PREF.haptics, prefs.haptics);
      syncControls();
    });

    strengthEl.addEventListener("input", () => {
      prefs.strength = Number(strengthEl.value) || 0;
      strengthLabel.textContent = `VIBRATION STRENGTH  \u00b7  ${Math.round(prefs.strength)}%`;
    });
    strengthEl.addEventListener("change", () => savePref(PREF.strength, Math.round(prefs.strength)));

    for (const button of document.querySelectorAll("[data-rate]")) {
      button.addEventListener("click", () => {
        prefs.rate = button.dataset.rate;
        savePref(PREF.rate, prefs.rate);
        syncControls();
      });
    }

    for (const button of document.querySelectorAll("[data-faces]")) {
      button.addEventListener("click", () => {
        prefs.faces = button.dataset.faces;
        savePref(PREF.faces, prefs.faces);
        syncFaces();
        dirty = true;
        requestFrame();
      });
    }
  }

  // ------------------------------------------------------------------ layouts
  let layouts = [];
  let activeLayout = "";

  function loadLayoutStore() {
    layouts = [];
    try {
      const parsed = JSON.parse(readPref(PREF.layouts, ""));
      if (Array.isArray(parsed)) {
        for (const entry of parsed) {
          if (!entry || typeof entry !== "object") continue;
          const name = String(entry.name || "").trim();
          const data = String(entry.data || "");
          if (name && data.trim()) layouts.push({ name, data });
        }
      }
    } catch (_error) {
      layouts = [];
    }
    activeLayout = readPref(PREF.layoutActive, "").trim();
    if (activeLayout && !layouts.some((entry) => entry.name === activeLayout)) {
      activeLayout = "";
    }
  }

  function saveLayoutStore() {
    writePref(PREF.layouts, JSON.stringify(layouts));
    writePref(PREF.layoutActive, activeLayout);
  }

  function layoutSnapshot() {
    return nodes.map((node) => {
      const fx = Math.trunc(node.fx * 10000);
      const fy = Math.trunc(node.fy * 10000);
      const scale = Math.trunc(node.scale * 1000);
      return `${node.key}:${fx},${fy},${scale},${node.hidden ? 1 : 0}`;
    }).join(";");
  }

  function applyLayout(data) {
    if (!data || !data.trim()) return;
    const parsed = new Map();
    for (const chunk of data.split(";")) {
      const pair = chunk.split(":", 2);
      if (pair.length !== 2) continue;
      const values = pair[1].split(",");
      if (values.length < 3) continue;
      parsed.set(pair[0], values);
    }
    for (const [key, values] of parsed) {
      if (key === "face") continue;
      const node = nodeByKey(key);
      if (!node) continue;
      const fx = Number(values[0]);
      const fy = Number(values[1]);
      const scale = Number(values[2]);
      node.fx = Math.max(0, Math.min(1, Number.isFinite(fx) ? fx / 10000 : node.fx));
      node.fy = Math.max(0, Math.min(1, Number.isFinite(fy) ? fy / 10000 : node.fy));
      node.scale = Math.max(0.55, Math.min(1.9, Number.isFinite(scale) ? scale / 1000 : 1));
      node.hidden = values[3] === "1";
      node.customized = true;
    }
    layoutControls();
    dirty = true;
    requestFrame();
  }

  function persistLayout() {
    const data = layoutSnapshot();
    writePref(PREF.layout, data);
    if (!activeLayout) return;
    const entry = layouts.find((item) => item.name === activeLayout);
    if (!entry) return;
    entry.data = data;
    saveLayoutStore();
  }

  function syncLayouts() {
    layoutList.textContent = "";

    const addRow = (name, label, deletable) => {
      const row = document.createElement("div");
      row.className = "layout-row";

      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = "chip";
      chip.textContent = label;
      if (name !== null && name === activeLayout) chip.classList.add("on");
      chip.addEventListener("click", () => {
        if (name === null) {
          resetLayout();
          activeLayout = "";
          saveLayoutStore();
          persistLayout();
          syncLayouts();
          toast("Default layout restored");
          return;
        }
        const entry = layouts.find((item) => item.name === name);
        if (!entry) {
          syncLayouts();
          toast("That layout no longer exists");
          return;
        }
        applyLayout(entry.data);
        writePref(PREF.layout, entry.data);
        activeLayout = entry.name;
        saveLayoutStore();
        syncLayouts();
        toast(`Layout: ${entry.name}`);
      });
      row.appendChild(chip);

      if (deletable) {
        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "delete";
        remove.textContent = "DELETE";
        remove.setAttribute("aria-label", `Delete layout ${name}`);
        remove.addEventListener("click", () => {
          confirmDialog(
            "DELETE LAYOUT",
            `Delete "${name}"? The current pad keeps its positions but is no longer saved under that name.`,
            "DELETE",
            () => {
              layouts = layouts.filter((item) => item.name !== name);
              if (activeLayout === name) activeLayout = "";
              saveLayoutStore();
              syncLayouts();
              toast("Layout deleted");
            },
          );
        });
        row.appendChild(remove);
      }
      layoutList.appendChild(row);
    };

    addRow(null, "DEFAULT", false);
    for (const entry of layouts) addRow(entry.name, entry.name, true);
    layoutSummary.textContent = activeLayout
      ? `Current: ${activeLayout}`
      : "Current: unsaved arrangement";
  }

  function saveLayoutAs() {
    showDialog({
      eyebrow: "LAYOUT",
      title: "SAVE LAYOUT",
      message: "Name this arrangement. An existing name is replaced.",
      input: true,
      value: "",
      ok: "SAVE",
      onOk: (raw) => {
        const name = String(raw || "").trim().slice(0, 28);
        if (!name) return;
        const index = layouts.findIndex((entry) => entry.name.toLowerCase() === name.toLowerCase());
        const replaced = index >= 0;
        const entry = { name: replaced ? layouts[index].name : name, data: layoutSnapshot() };
        if (replaced) layouts[index] = entry;
        else layouts.push(entry);
        activeLayout = entry.name;
        saveLayoutStore();
        writePref(PREF.layout, entry.data);
        syncLayouts();
        toast(replaced ? `Layout "${entry.name}" updated` : `Layout "${entry.name}" saved`);
      },
    });
  }

  function bindLayouts() {
    $("#edit-layout").addEventListener("click", () => {
      closeSettings();
      enterEditMode();
    });
    $("#save-layout").addEventListener("click", saveLayoutAs);

    for (const button of document.querySelectorAll("[data-edit]")) {
      button.addEventListener("click", () => {
        const action = button.dataset.edit;
        if (action === "done") exitEditMode(true);
        else if (action === "save") saveLayoutAs();
        else if (action === "reset") {
          resetLayout();
          persistLayout();
        } else if (action === "show") showAll();
      });
    }
  }

  function showEditBar() {
    $("#edit-bar").hidden = false;
  }

  function hideEditBar() {
    $("#edit-bar").hidden = true;
  }

  // ------------------------------------------------------------------- screen
  let screenSocket = null;
  let screenDecoding = false;
  let pendingScreenBlob = null;
  let screenRetryTimer = 0;
  let screenEnded = false;

  function qualityShort(value) {
    return value === 0 ? "LOW" : value === 2 ? "HIGH" : "BAL";
  }

  function pipVisible() {
    return !pip.hidden;
  }

  function clearPipCanvas() {
    const g = pipCanvas.getContext("2d");
    g.clearRect(0, 0, pipCanvas.width, pipCanvas.height);
  }

  function setPipVisible(show) {
    if (show) {
      screenEnded = false;
      pip.hidden = false;
      pip.classList.toggle("small", prefs.screenSmall);
      pipBadge.textContent = qualityShort(prefs.screenQuality);
      requestAnimationFrame(() => {
        placePip();
        connectScreen();
      });
    } else {
      pip.hidden = true;
      stopScreen();
      clearPipCanvas();
    }
  }

  function placePip() {
    if (pip.hidden || W <= 0 || H <= 0) return;
    const width = pip.offsetWidth;
    const height = pip.offsetHeight;
    if (!width || !height) {
      requestAnimationFrame(placePip);
      return;
    }
    const fx = Number.isFinite(prefs.screenX) ? prefs.screenX : 0.5;
    const fy = Number.isFinite(prefs.screenY) ? prefs.screenY : 0.3;
    const left = Math.max(4, Math.min(W - width - 4, fx * W - width / 2));
    const top = Math.max(4, Math.min(H - height - 4, fy * H - height / 2));
    pip.style.left = `${Math.round(left)}px`;
    pip.style.top = `${Math.round(top)}px`;
  }

  function stopScreen() {
    clearTimeout(screenRetryTimer);
    screenRetryTimer = 0;
    pendingScreenBlob = null;
    const ws = screenSocket;
    screenSocket = null;
    if (ws) {
      ws.onclose = null;
      ws.onmessage = null;
      try {
        ws.close(1000, "preview off");
      } catch (_error) {
        /* already closed */
      }
    }
    pip.classList.remove("live");
    pipHint.style.display = "flex";
    pipHint.textContent = "PC SCREEN";
    pipBadge.textContent = qualityShort(prefs.screenQuality);
  }

  function scheduleScreenRetry() {
    if (screenRetryTimer || pip.hidden || !prefs.screen) return;
    screenRetryTimer = setTimeout(() => {
      screenRetryTimer = 0;
      if (!pip.hidden && prefs.screen && !screenSocket) connectScreen();
    }, 1600);
  }

  function connectScreen() {
    stopScreen();
    if (!prefs.screen || pip.hidden) return;
    const separator = location.search ? "&" : "?";
    let ws;
    try {
      ws = new WebSocket(`${socketUrl("/screen")}${separator}q=${prefs.screenQuality}`);
    } catch (_error) {
      pipHint.textContent = "RETRYING";
      scheduleScreenRetry();
      return;
    }
    screenSocket = ws;
    ws.binaryType = "blob";
    pipHint.style.display = "flex";
    pipHint.textContent = "CONNECTING";
    pipBadge.textContent = qualityShort(prefs.screenQuality);

    ws.addEventListener("message", (event) => {
      if (ws !== screenSocket) return;
      if (typeof event.data === "string") {
        let message;
        try {
          message = JSON.parse(event.data);
        } catch (_error) {
          return;
        }
        if (!message || typeof message !== "object") return;
        if (message.t === "v") {
          const state = message.s || "";
          if (state === "live") {
            pip.classList.add("live");
            pipHint.style.display = "none";
            pipBadge.textContent = qualityShort(Number(message.q));
            const details = [];
            if (message.w) details.push(`${message.w}px`);
            if (message.fps) details.push(`${message.fps} fps`);
            pip.title = details.join(" \u00b7 ");
          } else {
            pip.classList.remove("live");
            pipHint.style.display = "flex";
            pipHint.textContent = state === "retry" ? "RETRYING" : "CONNECTING";
          }
        } else if (message.t === "e") {
          pip.classList.remove("live");
          pipHint.style.display = "flex";
          pipHint.textContent = "SCREEN OFF";
          pipBadge.textContent = "OFF";
          toast(String(message.m || "PC screen preview is not available"));
        }
      } else {
        queueScreenFrame(event.data);
      }
    });

    ws.addEventListener("close", () => {
      if (ws !== screenSocket) return;
      screenSocket = null;
      pip.classList.remove("live");
      if (!screenEnded && prefs.screen && !pip.hidden) {
        pipHint.style.display = "flex";
        pipHint.textContent = "RETRYING";
        scheduleScreenRetry();
      }
    });

    ws.addEventListener("error", () => {
      /* the close event schedules the retry */
    });
  }

  function queueScreenFrame(blob) {
    pendingScreenBlob = blob;
    if (screenDecoding) return;
    screenDecoding = true;
    (async () => {
      while (pendingScreenBlob) {
        const current = pendingScreenBlob;
        pendingScreenBlob = null;
        try {
          const image = await decodeBlob(current);
          if (!pip.hidden) drawScreenFrame(image);
          if (image.close) image.close();
        } catch (_error) {
          /* skip a corrupt frame */
        }
      }
      screenDecoding = false;
    })();
  }

  function decodeBlob(blob) {
    if (typeof createImageBitmap === "function") return createImageBitmap(blob);
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(blob);
      const image = new Image();
      image.onload = () => {
        URL.revokeObjectURL(url);
        resolve(image);
      };
      image.onerror = () => {
        URL.revokeObjectURL(url);
        reject(new Error("frame decode failed"));
      };
      image.src = url;
    });
  }

  function drawScreenFrame(image) {
    const width = image.width || image.naturalWidth || 0;
    const height = image.height || image.naturalHeight || 0;
    if (!width || !height) return;
    if (pipCanvas.width !== width || pipCanvas.height !== height) {
      pipCanvas.width = width;
      pipCanvas.height = height;
    }
    const g = pipCanvas.getContext("2d");
    g.drawImage(image, 0, 0, width, height);
    pip.classList.add("live");
    pipHint.style.display = "none";
  }

  function syncScreen() {
    const enabled = prefs.screen;
    screenToggle.textContent = enabled ? "ON" : "OFF";
    screenToggle.classList.toggle("on", enabled);
    screenToggle.setAttribute("aria-pressed", enabled ? "true" : "false");
    pipBadge.textContent = qualityShort(prefs.screenQuality);

    for (const button of document.querySelectorAll("[data-size]")) {
      const selected = (button.dataset.size === "small") === prefs.screenSmall;
      button.classList.toggle("on", selected);
      button.disabled = !enabled;
    }
    for (const button of document.querySelectorAll("[data-quality]")) {
      const selected = Number(button.dataset.quality) === prefs.screenQuality;
      button.classList.toggle("on", selected);
      button.disabled = !enabled;
    }
    if (!screenAvailable) {
      screenHint.textContent = "The PC screen stream is off or still starting. Start the server without --no-video to use the preview.";
    } else if (!enabled) {
      screenHint.textContent = "Turn the preview on to show the PC screen in the corner.";
    } else {
      screenHint.textContent = "Drag to move \u00b7 tap to resize \u00b7 \u00d7 closes the preview.";
    }
  }

  function toggleScreenSize() {
    prefs.screenSmall = !prefs.screenSmall;
    savePref(PREF.screenSmall, prefs.screenSmall);
    pip.classList.toggle("small", prefs.screenSmall);
    placePip();
    setTimeout(placePip, 280);
    syncScreen();
  }

  function selectQuality(value) {
    if (!prefs.screen) return;
    prefs.screenQuality = Math.max(0, Math.min(2, value));
    savePref(PREF.screenQuality, prefs.screenQuality);
    pipBadge.textContent = qualityShort(prefs.screenQuality);
    if (screenSocket && screenSocket.readyState === WebSocket.OPEN) {
      try {
        screenSocket.send(JSON.stringify({ t: "q", q: prefs.screenQuality }));
      } catch (_error) {
        /* the close handler retries */
      }
    }
    syncScreen();
  }

  let pipDrag = null;

  function bindScreen() {
    screenToggle.addEventListener("click", () => {
      prefs.screen = !prefs.screen;
      savePref(PREF.screen, prefs.screen);
      if (prefs.screen) setPipVisible(true);
      else setPipVisible(false);
      syncScreen();
    });

    for (const button of document.querySelectorAll("[data-size]")) {
      button.addEventListener("click", () => {
        if (!prefs.screen) return;
        prefs.screenSmall = button.dataset.size === "small";
        savePref(PREF.screenSmall, prefs.screenSmall);
        pip.classList.toggle("small", prefs.screenSmall);
        placePip();
        setTimeout(placePip, 280);
        syncScreen();
      });
    }

    for (const button of document.querySelectorAll("[data-quality]")) {
      button.addEventListener("click", () => selectQuality(Number(button.dataset.quality)));
    }

    $("#pip-close").addEventListener("pointerdown", (event) => event.stopPropagation());
    $("#pip-close").addEventListener("click", (event) => {
      event.stopPropagation();
      prefs.screen = false;
      savePref(PREF.screen, false);
      screenEnded = true;
      setPipVisible(false);
      syncScreen();
      toast("Screen preview off \u2014 re-enable it in settings");
    });

    pip.addEventListener("pointerdown", (event) => {
      if (event.target.closest("#pip-close")) return;
      event.preventDefault();
      event.stopPropagation();
      const rect = pip.getBoundingClientRect();
      pipDrag = {
        id: event.pointerId,
        dx: event.clientX - rect.left,
        dy: event.clientY - rect.top,
        moved: false,
      };
      try {
        pip.setPointerCapture(event.pointerId);
      } catch (_error) {
        /* older browsers */
      }
    });

    pip.addEventListener("pointermove", (event) => {
      if (!pipDrag || event.pointerId !== pipDrag.id) return;
      event.preventDefault();
      const width = pip.offsetWidth;
      const height = pip.offsetHeight;
      const left = Math.max(4, Math.min(W - width - 4, event.clientX - pipDrag.dx));
      const top = Math.max(4, Math.min(H - height - 4, event.clientY - pipDrag.dy));
      if (!pipDrag.moved && Math.hypot(left - parseFloat(pip.style.left || "0"), top - parseFloat(pip.style.top || "0")) > 6) {
        pipDrag.moved = true;
      }
      pip.style.left = `${Math.round(left)}px`;
      pip.style.top = `${Math.round(top)}px`;
    });

    const pipUp = (event) => {
      if (!pipDrag || event.pointerId !== pipDrag.id) return;
      const wasMoved = pipDrag.moved;
      pipDrag = null;
      const width = pip.offsetWidth;
      const height = pip.offsetHeight;
      prefs.screenX = (parseFloat(pip.style.left || "0") + width / 2) / Math.max(1, W);
      prefs.screenY = (parseFloat(pip.style.top || "0") + height / 2) / Math.max(1, H);
      savePref(PREF.screenX, prefs.screenX);
      savePref(PREF.screenY, prefs.screenY);
      if (!wasMoved) toggleScreenSize();
    };
    pip.addEventListener("pointerup", pipUp);
    pip.addEventListener("pointercancel", pipUp);

    window.addEventListener("resize", () => {
      if (!pip.hidden) placePip();
    });
  }

  // ------------------------------------------------------------------ dialogs
  const dialogScrim = $("#dialog-scrim");
  const dialogEyebrow = $("#dialog-eyebrow");
  const dialogTitle = $("#dialog-title");
  const dialogMessage = $("#dialog-message");
  const dialogInput = $("#dialog-input");
  const dialogOk = $("#dialog-ok");
  const dialogCancel = $("#dialog-cancel");
  let dialogOnOk = null;

  function showDialog(options) {
    dialogEyebrow.textContent = options.eyebrow || "LAYOUT";
    dialogTitle.textContent = options.title || "";
    dialogMessage.textContent = options.message || "";
    dialogInput.hidden = !options.input;
    dialogInput.value = options.value || "";
    dialogOk.textContent = options.ok || "OK";
    dialogCancel.hidden = options.hideCancel === true;
    dialogOnOk = options.onOk || null;
    dialogScrim.hidden = false;
    requestAnimationFrame(() => dialogScrim.classList.add("show"));
    if (options.input) {
      setTimeout(() => {
        dialogInput.focus();
        dialogInput.select();
      }, 60);
    } else {
      setTimeout(() => dialogOk.focus(), 60);
    }
  }

  function closeDialog() {
    dialogScrim.classList.remove("show");
    dialogOnOk = null;
    setTimeout(() => {
      if (!dialogScrim.classList.contains("show")) dialogScrim.hidden = true;
    }, 170);
  }

  function confirmDialog(title, message, ok, onOk) {
    showDialog({ eyebrow: "LAYOUT", title, message, ok, onOk });
  }

  function bindDialogs() {
    $("#settings-close").addEventListener("click", closeSettings);
    scrim.addEventListener("click", (event) => {
      if (event.target === scrim) closeSettings();
    });

    connectBtn.addEventListener("click", () => {
      manualOffline = false;
      openSocket();
      syncSheet();
    });

    reconnectBtn.addEventListener("click", () => {
      manualOffline = false;
      clearTimeout(reconnectTimer);
      reconnectTimer = 0;
      dropSocket();
      openSocket();
      toast("Reconnecting\u2026");
    });

    disconnectBtn.addEventListener("click", () => {
      manualOffline = true;
      clearTimeout(reconnectTimer);
      reconnectTimer = 0;
      dropSocket();
      setStatus("OFFLINE", STATUS_IDLE);
      toast("Disconnected. Tap CONNECT to rejoin.");
      syncSheet();
    });

    $("#copy-link").addEventListener("click", async () => {
      const ok = await copyText(location.href);
      toast(ok ? "Page link copied" : "Could not copy automatically \u2014 select the address bar");
    });

    $("#key-help").addEventListener("click", () => {
      showDialog({
        eyebrow: "KEYBOARD",
        title: "KEYBOARD MAP",
        message: [
          "Arrows \u2014 D-pad",
          "W A S D \u2014 left stick",
          "I J K L \u2014 right stick",
          "Q / E \u2014 L1 / R1",
          "1 / 2 \u2014 L2 / R2",
          "Z / C \u2014 L3 / R3",
          "Numpad 8 / 6 / 2 / 4 \u2014 triangle / circle / cross / square",
          "Enter \u2014 start",
          "Shift \u2014 select",
          "\u0060 \u2014 PS",
        ].join("\n"),
        ok: "CLOSE",
        hideCancel: true,
      });
    });

    dialogOk.addEventListener("click", () => {
      if (!dialogInput.hidden) {
        const value = dialogInput.value.trim();
        if (!value) {
          dialogInput.focus();
          toast("Enter a name first");
          return;
        }
        const callback = dialogOnOk;
        closeDialog();
        if (callback) callback(value);
        return;
      }
      const callback = dialogOnOk;
      closeDialog();
      if (callback) callback("");
    });

    dialogCancel.addEventListener("click", closeDialog);
    dialogScrim.addEventListener("click", (event) => {
      if (event.target === dialogScrim) closeDialog();
    });
    dialogInput.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        dialogOk.click();
      }
    });

    window.addEventListener("keydown", (event) => {
      if (event.key === "Escape") {
        if (!dialogScrim.hidden) {
          event.stopImmediatePropagation();
          event.preventDefault();
          closeDialog();
        } else if (sheetOpen) {
          event.preventDefault();
          closeSettings();
        }
      }
    }, true);
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (_error) {
      /* fall through to the selection trick */
    }
    const area = document.createElement("textarea");
    area.value = text;
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    let ok = false;
    try {
      ok = document.execCommand("copy");
    } catch (_error) {
      ok = false;
    }
    area.remove();
    return ok;
  }

  // --------------------------------------------------------------------- boot
  function boot() {
    $("#app-version").textContent = `v${VERSION}`;
    $("#footer-version").textContent = `v${VERSION}`;
    loadLayoutStore();
    const storedLayout = readPref(PREF.layout, "");
    if (storedLayout) applyLayout(storedLayout);

    buildThemeGrid();
    buildSlotRow();
    bindControls();
    bindLayouts();
    bindScreen();
    bindDialogs();
    applyCssTheme(themeNow);
    resize();
    syncScreen();

    if (prefs.screen) setPipVisible(true);
    else pip.hidden = true;

    updateStatus();
    openSocket();

    window.addEventListener("pagehide", () => {
      manualOffline = true;
      dropSocket();
      stopScreen();
    });
  }

  boot();
})();
