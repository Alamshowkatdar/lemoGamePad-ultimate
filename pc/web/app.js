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

  const $ = (selector) => document.querySelector(selector);
  const statusEl = $("#status");
  const dotEl = $("#dot");
  const badgeEl = $("#pad");
  const pingEl = $("#ping");
  const helpEl = $("#help");
  const helpToggle = $("#help-toggle");
  const slotSelect = $("#slot-select");

  const pressed = new Set();
  const holds = new Map(); // button name -> Set of source ids
  const pointerButtons = new Map(); // pointer id -> button name
  const keys = new Set();
  const touchAxis = {
    l: { x: 0, y: 0, active: false },
    r: { x: 0, y: 0, active: false },
  };

  let socket = null;
  let connected = false;
  let retry = 0;
  let reconnectTimer = null;
  let loopTimer = null;
  let dirty = true;
  let lastSent = 0;
  let lastPing = 0;
  let lastRumble = 0;
  let assignedPad = 0;

  // ------------------------------------------------------------------ status
  function setStatus(text, state) {
    statusEl.textContent = text;
    dotEl.classList.toggle("on", state === "on");
    dotEl.classList.toggle("off", state === "off");
    dotEl.classList.toggle("warn", state === "warn");
  }

  function setPad(number) {
    assignedPad = number || 0;
    if (assignedPad > 0) {
      badgeEl.hidden = false;
      badgeEl.textContent = "P" + assignedPad;
    } else {
      badgeEl.hidden = true;
    }
  }

  // ------------------------------------------------------------------ socket
  function connect() {
    const scheme = location.protocol === "https:" ? "wss:" : "ws:";
    const url = `${scheme}//${location.host}/ws${location.search}`;
    try {
      socket = new WebSocket(url);
    } catch (_error) {
      scheduleReconnect();
      return;
    }

    socket.addEventListener("open", () => {
      connected = true;
      retry = 0;
      lastPing = 0;
      dirty = true;
      setStatus("Connected", "on");
      startLoop();
    });

    socket.addEventListener("message", onMessage);

    socket.addEventListener("close", () => {
      connected = false;
      setPad(0);
      setStatus("Reconnecting…", "off");
      stopLoop();
      scheduleReconnect();
    });

    socket.addEventListener("error", () => {
      /* the close event carries the retry */
    });
  }

  function scheduleReconnect() {
    if (reconnectTimer !== null) return;
    const delay = Math.min(5000, 300 * Math.pow(1.6, retry));
    retry = Math.min(retry + 1, 8);
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, delay);
  }

  function onMessage(event) {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch (_error) {
      return;
    }
    if (message.t === "s") {
      setPad(Number(message.n) || 0);
      if (!assignedPad) setStatus("Waiting for a free pad…", "warn");
    } else if (message.t === "q") {
      if (typeof message.c === "number") {
        const rtt = Math.max(0, Date.now() - message.c);
        pingEl.textContent = rtt + " ms";
      }
      if (message.n) setPad(Number(message.n));
    } else if (message.t === "r") {
      vibrate(message.s, message.l);
    }
  }

  function vibrate(small, large) {
    if (!("vibrate" in navigator)) return;
    const strength = Math.max(Number(small) || 0, Number(large) || 0);
    if (strength <= 0) {
      navigator.vibrate(0);
      return;
    }
    const now = Date.now();
    if (now - lastRumble < 80) return;
    lastRumble = now;
    navigator.vibrate(Math.max(30, Math.round((strength / 255) * 180)));
  }

  // ------------------------------------------------------------------- loops
  function startLoop() {
    if (loopTimer !== null) return;
    loopTimer = setInterval(tick, 16);
  }

  function stopLoop() {
    if (loopTimer === null) return;
    clearInterval(loopTimer);
    loopTimer = null;
  }

  function tick() {
    if (!connected || !socket || socket.readyState !== WebSocket.OPEN) return;
    const now = performance.now();
    if (dirty || now - lastSent > 100) {
      sendState();
      dirty = false;
      lastSent = now;
    }
    if (now - lastPing > 1000) {
      lastPing = now;
      try {
        socket.send(JSON.stringify({ t: "p", c: Date.now() }));
      } catch (_error) {
        /* close handler reconnects */
      }
    }
  }

  function axisToInt(value) {
    const clamped = Math.max(-1, Math.min(1, value));
    return Math.round(clamped * 32767);
  }

  function keyboardAxis(side) {
    let x = 0;
    let y = 0;
    if (side === "l") {
      if (keys.has("KeyA")) x -= 1;
      if (keys.has("KeyD")) x += 1;
      if (keys.has("KeyS")) y -= 1;
      if (keys.has("KeyW")) y += 1;
    } else {
      if (keys.has("KeyJ")) x -= 1;
      if (keys.has("KeyL")) x += 1;
      if (keys.has("KeyK")) y -= 1;
      if (keys.has("KeyI")) y += 1;
    }
    const length = Math.hypot(x, y);
    if (length > 1) {
      x /= length;
      y /= length;
    }
    return { x, y };
  }

  function snapshot() {
    let buttons = 0;
    for (const name of pressed) {
      if (name in BITS) buttons |= 1 << BITS[name];
    }
    const left = touchAxis.l.active ? touchAxis.l : keyboardAxis("l");
    const right = touchAxis.r.active ? touchAxis.r : keyboardAxis("r");
    return {
      b: buttons,
      lx: axisToInt(left.x),
      ly: axisToInt(left.y),
      rx: axisToInt(right.x),
      ry: axisToInt(right.y),
      l2: pressed.has("l2") ? 255 : 0,
      r2: pressed.has("r2") ? 255 : 0,
      w: Number(slotSelect.value) || 0,
    };
  }

  function sendState() {
    if (!socket || socket.readyState !== WebSocket.OPEN) return;
    const state = snapshot();
    try {
      socket.send(JSON.stringify({ t: "i", ...state }));
    } catch (_error) {
      /* close handler reconnects */
    }
  }

  // --------------------------------------------------------------- hold logic
  function hold(name, source) {
    let sources = holds.get(name);
    if (!sources) {
      sources = new Set();
      holds.set(name, sources);
    }
    sources.add(source);
    pressed.add(name);
    setButtonVisual(name, true);
    dirty = true;
  }

  function release(name, source) {
    const sources = holds.get(name);
    if (!sources) return;
    sources.delete(source);
    if (sources.size > 0) return;
    holds.delete(name);
    pressed.delete(name);
    setButtonVisual(name, false);
    dirty = true;
  }

  function setButtonVisual(name, on) {
    const button = document.querySelector(`[data-btn="${name}"]`);
    if (!button) return;
    button.classList.toggle("on", on);
    button.setAttribute("aria-pressed", on ? "true" : "false");
  }

  function releaseAll() {
    holds.clear();
    pressed.clear();
    keys.clear();
    pointerButtons.clear();
    for (const side of ["l", "r"]) {
      touchAxis[side] = { x: 0, y: 0, active: false };
    }
    for (const button of document.querySelectorAll(".btn.on")) {
      button.classList.remove("on");
      button.setAttribute("aria-pressed", "false");
    }
    for (const stick of document.querySelectorAll(".stick.active")) {
      stick.classList.remove("active");
      const knob = stick.querySelector(".knob");
      if (knob) knob.style.transform = "";
    }
    dirty = true;
    if (connected) sendState();
  }

  // ------------------------------------------------------------------ buttons
  document.querySelectorAll("[data-btn]").forEach((button) => {
    const name = button.dataset.btn;
    button.setAttribute("aria-pressed", "false");

    button.addEventListener("pointerdown", (event) => {
      event.preventDefault();
      if (pointerButtons.has(event.pointerId)) return;
      pointerButtons.set(event.pointerId, name);
      try {
        button.setPointerCapture(event.pointerId);
      } catch (_error) {
        /* older browsers */
      }
      hold(name, "p" + event.pointerId);
    });

    const up = (event) => {
      if (pointerButtons.get(event.pointerId) !== name) return;
      pointerButtons.delete(event.pointerId);
      release(name, "p" + event.pointerId);
    };
    button.addEventListener("pointerup", up);
    button.addEventListener("pointercancel", up);
    button.addEventListener("lostpointercapture", up);
    button.addEventListener("click", (event) => event.preventDefault());
    button.addEventListener("contextmenu", (event) => event.preventDefault());
  });

  // -------------------------------------------------------------------- sticks
  function setupStick(element, side) {
    const knob = element.querySelector(".knob");
    let pointerId = null;

    function apply(event) {
      const rect = element.getBoundingClientRect();
      const centerX = rect.left + rect.width / 2;
      const centerY = rect.top + rect.height / 2;
      let dx = event.clientX - centerX;
      let dy = event.clientY - centerY;
      const travel = rect.width * 0.34;
      const length = Math.hypot(dx, dy);
      if (length > travel) {
        dx = (dx / length) * travel;
        dy = (dy / length) * travel;
      }
      knob.style.transform = `translate(${dx}px, ${dy}px)`;

      let x = dx / travel;
      let y = -dy / travel;
      const dead = 0.12;
      const magnitude = Math.hypot(x, y);
      if (magnitude <= dead) {
        x = 0;
        y = 0;
      } else {
        const scale = (magnitude - dead) / (1 - dead);
        x = (x / magnitude) * scale;
        y = (y / magnitude) * scale;
      }
      touchAxis[side] = { x, y, active: true };
      dirty = true;
    }

    function reset() {
      if (pointerId === null) return;
      pointerId = null;
      element.classList.remove("active");
      knob.style.transform = "";
      touchAxis[side] = { x: 0, y: 0, active: false };
      dirty = true;
    }

    element.addEventListener("pointerdown", (event) => {
      if (pointerId !== null) return;
      event.preventDefault();
      pointerId = event.pointerId;
      element.classList.add("active");
      try {
        element.setPointerCapture(event.pointerId);
      } catch (_error) {
        /* older browsers */
      }
      apply(event);
    });

    element.addEventListener("pointermove", (event) => {
      if (event.pointerId !== pointerId) return;
      event.preventDefault();
      apply(event);
    });

    for (const name of ["pointerup", "pointercancel", "lostpointercapture"]) {
      element.addEventListener(name, (event) => {
        if (event.pointerId === pointerId) reset();
      });
    }
  }

  setupStick($("#stick-l"), "l");
  setupStick($("#stick-r"), "r");

  // ------------------------------------------------------------------ keyboard
  const keyButtons = {
    ArrowUp: "up",
    ArrowRight: "right",
    ArrowDown: "down",
    ArrowLeft: "left",
    KeyQ: "l1",
    KeyE: "r1",
    Digit1: "l2",
    Digit2: "r2",
    Numpad1: "l2",
    Numpad2: "cross",
    Numpad4: "square",
    Numpad6: "circle",
    Numpad8: "triangle",
    Enter: "start",
    NumpadEnter: "start",
    ShiftLeft: "select",
    ShiftRight: "select",
    Backquote: "ps",
  };
  const axisKeys = new Set([
    "KeyW",
    "KeyA",
    "KeyS",
    "KeyD",
    "KeyI",
    "KeyJ",
    "KeyK",
    "KeyL",
  ]);

  function typingTarget(target) {
    const tag = (target && target.tagName ? target.tagName : "").toLowerCase();
    return tag === "input" || tag === "textarea" || tag === "select";
  }

  window.addEventListener("keydown", (event) => {
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    if (typingTarget(event.target)) return;
    const name = keyButtons[event.code];
    if (name) {
      event.preventDefault();
      if (!event.repeat) hold(name, "k" + event.code);
      return;
    }
    if (axisKeys.has(event.code)) {
      event.preventDefault();
      keys.add(event.code);
      dirty = true;
    }
  });

  window.addEventListener("keyup", (event) => {
    const name = keyButtons[event.code];
    if (name) {
      event.preventDefault();
      release(name, "k" + event.code);
      return;
    }
    if (axisKeys.has(event.code)) {
      event.preventDefault();
      keys.delete(event.code);
      dirty = true;
    }
  });

  window.addEventListener("blur", releaseAll);
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) releaseAll();
  });
  window.addEventListener("pagehide", () => {
    try {
      socket?.close(1000, "page hidden");
    } catch (_error) {
      /* already closed */
    }
  });

  // ---------------------------------------------------------------------- help
  helpToggle.addEventListener("click", () => {
    const open = helpEl.hidden;
    helpEl.hidden = !open;
    helpToggle.setAttribute("aria-expanded", open ? "true" : "false");
  });

  slotSelect.addEventListener("change", () => {
    dirty = true;
    if (connected) sendState();
  });

  // --------------------------------------------------------------------- boot
  setPad(0);
  setStatus("Connecting…", "warn");
  connect();
})();
