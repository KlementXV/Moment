// Deterministic animation helpers. Every frame is a pure function of time:
// window.render(seconds) positions everything, so frames can be rendered in
// any order and in parallel.

const E = {
  linear: (x) => x,
  out: (x) => 1 - Math.pow(1 - x, 3),
  in: (x) => x * x * x,
  inOut: (x) => (x < .5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2),
  outQuint: (x) => 1 - Math.pow(1 - x, 5),
  outExpo: (x) => (x >= 1 ? 1 : 1 - Math.pow(2, -10 * x)),
  outBack: (x) => { const c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2); },
  outSoftBack: (x) => { const c1 = .9, c3 = c1 + 1; return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2); },
};
const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
const lerp = (a, b, p) => a + (b - a) * p;
const P = (t, a, d, e = "out") => E[e](clamp((t - a) / d));
const fmt = (n) => Math.round(n).toLocaleString("en-US");

const cache = new Map();
function el(x) {
  if (typeof x !== "string") return x;
  let e = cache.get(x);
  if (!e) {
    e = document.getElementById(x);
    if (!e) throw new Error(`missing #${x}`);
    cache.set(x, e);
  }
  return e;
}

const BASE = { o: 1, x: 0, y: 0, s: 1, r: 0, sx: 1, sy: 1, blur: 0 };

function apply(e, p) {
  e = el(e);
  const x = p.x ?? 0, y = p.y ?? 0, s = p.s ?? 1, r = p.r ?? 0, sx = (p.sx ?? 1) * s, sy = (p.sy ?? 1) * s;
  e.style.transform = `translate(${x.toFixed(2)}px,${y.toFixed(2)}px) rotate(${r.toFixed(2)}deg) scale(${sx.toFixed(4)},${sy.toFixed(4)})`;
  if (p.o !== undefined) {
    const o = clamp(p.o);
    e.style.opacity = o.toFixed(3);
    e.style.visibility = o <= .001 ? "hidden" : "visible";
  }
  if (p.blur !== undefined) e.style.filter = p.blur > .05 ? `blur(${p.blur.toFixed(2)}px)` : "none";
}

// Animate from `from` to the resting state starting at `a`, then optionally to `to` at `out`.
function show(e, t, a, o = {}) {
  const from = o.from ?? { o: 0, y: 40 };
  const base = o.base ?? {};
  const p = P(t, a, o.d ?? .7, o.ease ?? "out");
  const props = { ...base };
  for (const k in from) props[k] = lerp(from[k], base[k] ?? BASE[k], p);
  if (o.out !== undefined) {
    const to = o.to ?? { o: 0, y: -24 };
    const q = P(t, o.out, o.od ?? .45, o.oease ?? "inOut");
    for (const k in to) props[k] = lerp(props[k] ?? base[k] ?? BASE[k], to[k], q);
  }
  apply(e, props);
  return p;
}

// Masked word-by-word reveal for elements prepared by splitWords().
function words(e, t, a, o = {}) {
  e = el(e);
  const ws = e._wi || (e._wi = [...e.querySelectorAll(".wi")]);
  const st = o.st ?? .055, d = o.d ?? .8;
  ws.forEach((w, i) => {
    const p = P(t, a + i * st, d, o.ease ?? "outQuint");
    w.style.transform = `translateY(${((1 - p) * 108).toFixed(2)}%)`;
    w.style.opacity = Math.min(1, p * 1.8).toFixed(3);
  });
  if (o.out !== undefined) show(e, t, -99, { from: { o: 1 }, out: o.out, od: o.od ?? .45, to: o.to ?? { o: 0, y: -24 } });
  else apply(e, { o: t >= a - .05 ? 1 : 0 });
}

function splitWords(root) {
  const walk = (node, cls) => {
    for (const ch of [...node.childNodes]) {
      if (ch.nodeType === 3) {
        const frag = document.createDocumentFragment();
        for (const part of ch.textContent.split(/(\s+)/)) {
          if (!part) continue;
          if (/^\s+$/.test(part)) { frag.appendChild(document.createTextNode(" ")); continue; }
          const w = document.createElement("span");
          w.className = "w";
          const wi = document.createElement("span");
          wi.className = "wi" + (cls ? " " + cls : "");
          wi.textContent = part;
          w.appendChild(wi);
          frag.appendChild(w);
        }
        node.replaceChild(frag, ch);
      } else if (ch.nodeType === 1 && ch.tagName !== "BR") {
        walk(ch, ch.className);
        while (ch.firstChild) node.insertBefore(ch.firstChild, ch);
        node.removeChild(ch);
      }
    }
  };
  walk(root, "");
}

function splitChars(root) {
  const text = root.textContent;
  root.innerHTML = [...text].map((c) => `<span class="w"><span class="wi">${c}</span></span>`).join("");
}

function dash(e, p) {
  e = el(e);
  if (!e._len) e._len = e.getTotalLength();
  e.style.strokeDasharray = `${e._len} ${e._len}`;
  e.style.strokeDashoffset = (e._len * (1 - clamp(p))).toFixed(2);
  e.style.visibility = p > 0 ? "visible" : "hidden";
}

// Point along a quadratic arc between two points, bulging by `lift` pixels.
function arcPoint(x0, y0, x1, y1, p, lift) {
  return [lerp(x0, x1, p), lerp(y0, y1, p) - Math.sin(Math.PI * p) * lift];
}

// Small deterministic pseudo-random generator for particle layouts.
function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6D2B79F5) >>> 0;
    let r = Math.imul(s ^ (s >>> 15), 1 | s);
    r ^= r + Math.imul(r ^ (r >>> 7), 61 | r);
    return ((r ^ (r >>> 14)) >>> 0) / 4294967296;
  };
}
