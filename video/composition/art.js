// Illustrations and icons shared by the scenes. The landscape and portrait
// generators mirror the demo illustrations drawn in app/.../ui/Feed.kt.

const ICON = {
  lock: "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zM9 6c0-1.66 1.34-3 3-3s3 1.34 3 3v2H9V6zm9 14H6V10h12v10zm-6-3c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2z",
  check: "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
  close: "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
  wallet: "M21 18v1c0 1.1-.9 2-2 2H5c-1.11 0-2-.9-2-2V5c0-1.1.89-2 2-2h14c1.1 0 2 .9 2 2v1h-9c-1.11 0-2 .9-2 2v8c0 1.1.89 2 2 2h9zm-9-2h10V8H12v8zm4-2.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z",
  camera: "M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4zM9 2 7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9zm3 15c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z",
  shield: "M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z",
  clock: "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z",
  heart: "M16.5 3c-1.74 0-3.41.81-4.5 2.09C10.91 3.81 9.24 3 7.5 3 4.42 3 2 5.42 2 8.5c0 3.78 3.4 6.86 8.55 11.54L12 21.35l1.45-1.32C18.6 15.36 22 12.28 22 8.5 22 5.42 19.58 3 16.5 3zm-4.4 15.55-.1.1-.1-.1C7.14 14.24 4 11.39 4 8.5 4 6.5 5.5 5 7.5 5c1.54 0 3.04.99 3.57 2.36h1.87C13.46 5.99 14.96 5 16.5 5c2 0 3.5 1.5 3.5 3.5 0 2.89-3.14 5.74-7.9 10.05z",
  heartFill: "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z",
  sync: "M12 6v3l4-4-4-4v3c-4.42 0-8 3.58-8 8 0 1.57.46 3.03 1.24 4.26L6.7 14.8c-.45-.83-.7-1.79-.7-2.8 0-3.31 2.69-6 6-6zm6.76 1.74L17.3 9.2c.44.84.7 1.79.7 2.8 0 3.31-2.69 6-6 6v-3l-4 4 4 4v-3c4.42 0 8-3.58 8-8 0-1.57-.46-3.03-1.24-4.26z",
  person: "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
  key: "M12.65 10C11.83 7.67 9.61 6 7 6c-3.31 0-6 2.69-6 6s2.69 6 6 6c2.61 0 4.83-1.67 5.65-4H17v4h4v-4h2v-4H12.65zM7 14c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z",
  cloud: "M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z",
  phone: "M17 1.01 7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z",
  server: "M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z",
  db: "M12 3C7.58 3 4 4.79 4 7v10c0 2.21 3.59 4 8 4s8-1.79 8-4V7c0-2.21-3.58-4-8-4zm6 14c0 .5-2.13 2-6 2s-6-1.5-6-2v-2.23c1.61.78 3.72 1.23 6 1.23s4.39-.45 6-1.23V17zm0-4.55c-1.3.95-3.58 1.55-6 1.55s-4.7-.6-6-1.55V9.64c1.47.83 3.61 1.36 6 1.36s4.53-.53 6-1.36v2.81zM12 9C8.13 9 6 7.5 6 7s2.13-2 6-2 6 1.5 6 2-2.13 2-6 2z",
  trend: "M16 6l2.29 2.29-4.88 4.88-4-4L2 16.59 3.41 18l6-6 4 4 6.3-6.29L22 12V6z",
  at: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10h5v-2h-5c-4.34 0-8-3.66-8-8s3.66-8 8-8 8 3.66 8 8v1.43c0 .79-.71 1.57-1.5 1.57s-1.5-.78-1.5-1.57V12c0-2.76-2.24-5-5-5s-5 2.24-5 5 2.24 5 5 5c1.38 0 2.64-.56 3.54-1.47.65.89 1.77 1.47 2.96 1.47 1.97 0 3.5-1.6 3.5-3.57V12c0-5.52-4.48-10-10-10zm0 13c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3z",
  pen: "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
  arrow: "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z",
  home: "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
  fire: "M19.48 12.35c-1.57-4.08-7.16-4.3-5.81-10.23.1-.44-.37-.78-.75-.55C9.29 3.71 6.68 8 8.87 13.62c.18.46-.36.89-.75.59-1.81-1.37-2-3.34-1.84-4.75.06-.52-.62-.77-.91-.34C4.69 10.16 4 11.84 4 14.37c.38 5.6 5.11 7.32 6.81 7.54 2.43.31 5.06-.14 6.95-1.87 2.08-1.93 2.84-5.01 1.72-7.69z",
  eye: "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z",
  block: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12c0-4.42 3.58-8 8-8 1.85 0 3.55.63 4.9 1.69L5.69 16.9C4.63 15.55 4 13.85 4 12zm8 8c-1.85 0-3.55-.63-4.9-1.69L18.31 7.1C19.37 8.45 20 10.15 20 12c0 4.42-3.58 8-8 8z",
  bolt: "M7 2v11h3v9l7-12h-4l4-8z",
  more: "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
  minus: "M19 13H5v-2h14v2z",
  plus: "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
  flip: "M16 4H8C6.9 4 6 4.9 6 6v12c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-4 13c-1.66 0-3-1.34-3-3h2l-3-3-3 3h2c0 2.76 2.24 5 5 5v-2zm5-5c0-2.76-2.24-5-5-5v2c1.66 0 3 1.34 3 3h-2l3 3 3-3h-2z",
  calendar: "M19 4h-1V2h-2v2H8V2H6v2H5c-1.11 0-1.99.9-1.99 2L3 20c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 16H5V10h14v10zm0-12H5V6h14v2z",
  gavel: "M1 21h12v2H1v-2zM5.24 8.07l2.83-2.83 14.14 14.14-2.83 2.83L5.24 8.07zM12.32 1l5.66 5.66-2.83 2.83-5.66-5.66L12.32 1zM3.83 9.48l5.66 5.66-2.83 2.83L1 12.31l2.83-2.83z",
};

const icon = (name, cls = "icon", size) =>
  `<svg class="${cls}" viewBox="0 0 24 24"${size ? ` width="${size}" height="${size}"` : ""}><path d="${ICON[name]}"/></svg>`;

let uid = 0;
const nextId = (p) => `${p}${++uid}`;

const LOGO_ARCH = "M137 380 V262 A58 58 0 0 1 253 262 V380 M253 262 A58 58 0 0 1 369 262 V380";

function logoSvg(id = nextId("logo"), extra = "") {
  return `<svg ${extra} viewBox="100 100 330 320" xmlns="http://www.w3.org/2000/svg">
    <defs><linearGradient id="${id}g" gradientUnits="userSpaceOnUse" x1="110" y1="190" x2="400" y2="380">
      <stop offset="0" stop-color="#FCC45F"/><stop offset=".5" stop-color="#F58A63"/><stop offset="1" stop-color="#EE4C80"/></linearGradient></defs>
    <path class="arch" d="${LOGO_ARCH}" stroke="url(#${id}g)" stroke-width="54" stroke-linejoin="round" fill="none"/>
    <circle class="dot" cx="384" cy="146" r="27" fill="#EE5A7C"/></svg>`;
}

const SKIES = [
  { air: ["#617D91", "#E1B998", "#45665D"], sun: "#FFDAB0", sx: .73, sy: .28, sr: .10, far: "#627977", mid: "#355B55", near: "#193E37", trail: "#B1AA81", trees: "#102D29", n: 13 },
  { air: ["#8170B4", "#EEBB9B", "#334C4E"], sun: "#FFE2C4", sx: .26, sy: .23, sr: .09, far: "#6E6E8E", mid: "#4A4A6B", near: "#2A2B45", trail: "#C9B9A4", trees: "#1A1B2E", n: 9 },
  { air: ["#BCCBD6", "#A6B9C6", "#6B8794"], sun: "#F4F8FA", sx: .55, sy: .17, sr: .07, far: "#8FA3B0", mid: "#6A8291", near: "#465C6B", trail: "#CFD6DA", trees: "#32444F", n: 17 },
  { air: ["#F0B27A", "#E08D5B", "#9C5B3C"], sun: "#FFF0C9", sx: .84, sy: .35, sr: .12, far: "#C98A63", mid: "#9E6144", near: "#6E3F2C", trail: "#E8C79A", trees: "#4A2A1E", n: 6 },
  { air: ["#141A33", "#243356", "#16213A"], sun: "#DCE4F5", sx: .19, sy: .18, sr: .06, far: "#2C3A5C", mid: "#1E2A45", near: "#121A2C", trail: "#6C7796", trees: "#0A1020", n: 11 },
];

function landscape(variant = 0, w = 300, h = 375) {
  const v = ((variant % 5) + 5) % 5;
  const s = SKIES[v];
  const lift = (v - 2) * .03;
  const id = nextId("sky");
  const ridge = (color, pts) =>
    `<path fill="${color}" d="M0 ${h} ${pts.map(([x, y]) => `L${(x * w).toFixed(1)} ${((y + lift) * h).toFixed(1)}`).join(" ")} L${w} ${h}Z"/>`;
  let trees = "";
  for (let i = 0; i <= s.n; i++) {
    const x = w * (i / s.n), y = h * (.83 + (i % 3) * .04);
    trees += `<path fill="${s.trees}" d="M${x.toFixed(1)} ${(y - h * .12).toFixed(1)} L${(x - w * .035).toFixed(1)} ${y.toFixed(1)} L${(x + w * .035).toFixed(1)} ${y.toFixed(1)}Z"/>`;
  }
  return `<svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="xMidYMid slice" xmlns="http://www.w3.org/2000/svg">
    <defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="${s.air[0]}"/><stop offset=".5" stop-color="${s.air[1]}"/><stop offset="1" stop-color="${s.air[2]}"/></linearGradient></defs>
    <rect width="${w}" height="${h}" fill="url(#${id})"/>
    <circle cx="${w * s.sx}" cy="${h * s.sy}" r="${w * s.sr}" fill="${s.sun}"/>
    ${ridge(s.far, [[0, .55], [.16, .39], [.28, .48], [.54, .30], [.7, .46], [1, .37]])}
    ${ridge(s.mid, [[0, .62], [.25, .48], [.5, .69], [.75, .53], [1, .66]])}
    ${ridge(s.near, [[0, .73], [.2, .8], [.46, .67], [.78, .78], [1, .63]])}
    <path d="M${w * .45} ${h} C${w * .2} ${h * .84} ${w * .85} ${h * .81} ${w * .59} ${h * .70}" stroke="${s.trail}" stroke-width="${w * .04}" stroke-linecap="round" fill="none"/>
    ${trees}</svg>`;
}

const FACES = [
  { air: ["#ABA4D4", "#5A6582"], ground: "#253B39", skin: "#DAA383", neck: "#BE8668", hair: "#252524" },
  { air: ["#E7B9A0", "#8A6A72"], ground: "#3A2A2E", skin: "#8D5A3C", neck: "#75492F", hair: "#1B1412" },
  { air: ["#C6D8E2", "#6E8796"], ground: "#3F5560", skin: "#F0CBA8", neck: "#D4AC8B", hair: "#6B4A2A" },
  { air: ["#F3CE9E", "#B07A55"], ground: "#5A3826", skin: "#5E3A24", neck: "#4B2E1C", hair: "#14100D" },
  { air: ["#2A3554", "#151C2F"], ground: "#1A2236", skin: "#C2906B", neck: "#A37554", hair: "#241E2C" },
];

function portrait(variant = 0, w = 120, h = 160) {
  const f = FACES[((variant % 5) + 5) % 5];
  const id = nextId("face");
  const pt = (cx, cy, rx, ry, deg) => [cx + rx * Math.cos(deg * Math.PI / 180), cy + ry * Math.sin(deg * Math.PI / 180)];
  const hc = [w * .495, h * .295], hr = [w * .295, h * .175];
  const a = pt(...hc, ...hr, 170), b = pt(...hc, ...hr, 380);
  const mc = [w * .5, h * .475], mr = [w * .07, h * .035];
  const m0 = pt(...mc, ...mr, 0), m1 = pt(...mc, ...mr, 160);
  const sw = w * .012;
  return `<svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="xMidYMid slice" xmlns="http://www.w3.org/2000/svg">
    <defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${f.air[0]}"/><stop offset="1" stop-color="${f.air[1]}"/></linearGradient></defs>
    <rect width="${w}" height="${h}" fill="url(#${id})"/>
    <ellipse cx="${w * .5}" cy="${h * .92}" rx="${w * .6}" ry="${h * .3}" fill="${f.ground}"/>
    <rect x="${w * .4}" y="${h * .5}" width="${w * .2}" height="${h * .22}" rx="${w * .03}" fill="${f.neck}"/>
    <ellipse cx="${w * .505}" cy="${h * .40}" rx="${w * .265}" ry="${h * .2}" fill="${f.skin}"/>
    <path d="M${hc[0]} ${hc[1]} L${a[0]} ${a[1]} A${hr[0]} ${hr[1]} 0 1 1 ${b[0]} ${b[1]} Z" fill="${f.hair}"/>
    <line x1="${w * .34}" y1="${h * .37}" x2="${w * .42}" y2="${h * .37}" stroke="#0D0A08" stroke-width="${sw}" stroke-linecap="round"/>
    <line x1="${w * .58}" y1="${h * .37}" x2="${w * .66}" y2="${h * .37}" stroke="#0D0A08" stroke-width="${sw}" stroke-linecap="round"/>
    <path d="M${m0[0]} ${m0[1]} A${mr[0]} ${mr[1]} 0 0 1 ${m1[0]} ${m1[1]}" stroke="#805846" stroke-width="${sw * .8}" fill="none" stroke-linecap="round"/></svg>`;
}

function avatar(name, size = 42) {
  return `<div class="avatar" style="width:${size}px;height:${size}px"><div style="font-size:${Math.round(size * .38)}px">${name[0]}</div></div>`;
}

function momentCard({ name, meta, caption, variant, likes, tag = "Verified signature", photoH = 405 }) {
  return `<div class="mcard">
    <div class="mhead">${avatar(name)}<div style="flex:1"><div class="mname">${name}</div><div class="mmeta">${meta}</div></div>
      <svg viewBox="0 0 24 24" width="22" height="22" fill="#BCAE9F"><path d="${ICON.more}"/></svg></div>
    <div class="photo" style="height:${photoH}px">${landscape(variant, 324, photoH)}
      <div class="selfie">${portrait(variant)}</div><div class="ptag">${tag}</div></div>
    <div class="mcap">${caption}</div>
    <div class="mreact">${icon("heart")}<span>${likes}</span></div></div>`;
}

function skrCoin(size = 340, id = nextId("coin")) {
  return `<svg viewBox="0 0 200 200" width="${size}" height="${size}" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="${id}a" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#F4C56F"/><stop offset=".35" stop-color="#EE9959"/><stop offset=".68" stop-color="#EA7666"/><stop offset="1" stop-color="#E94F75"/></linearGradient>
      <radialGradient id="${id}b" cx=".35" cy=".3" r=".8"><stop offset="0" stop-color="#2B231D"/><stop offset="1" stop-color="#120E0B"/></radialGradient>
    </defs>
    <circle cx="100" cy="100" r="96" fill="url(#${id}a)"/>
    <circle cx="100" cy="100" r="84" fill="url(#${id}b)"/>
    <circle cx="100" cy="100" r="74" fill="none" stroke="url(#${id}a)" stroke-width="1.5" stroke-dasharray="2 5" opacity=".7"/>
    <text x="100" y="117" text-anchor="middle" font-family="Inter Display, Inter" font-weight="800" font-size="50" letter-spacing="-1" fill="url(#${id}a)">SKR</text>
  </svg>`;
}

function miniCoin(size = 30) {
  return `<div style="width:${size}px;height:${size}px;border-radius:50%;background:var(--grad);box-shadow:0 0 18px rgba(240,161,94,.6);display:flex;align-items:center;justify-content:center;font:800 ${size * .32}px/1 var(--display);color:#170D08">SKR</div>`;
}
