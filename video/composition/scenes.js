// Scene timing and animation. Cue times come from timeline.js, generated from
// the narration, so the visuals stay in sync when the voice-over changes.
(function () {
  const TL = window.TIMELINE;
  const SC = Object.fromEntries(TL.scenes.map((s) => [s.id, s]));
  const cue = (sid, id, f = 0) => { const c = SC[sid].cues[id]; return c.t + c.d * f; };
  const SFX = [];
  window.SFX = SFX;
  const fx = (sid, t, type, gain = 1, dur) => SFX.push({ t: +(SC[sid].start + t).toFixed(3), type, gain, ...(dur ? { dur } : {}) });

  // ---------------------------------------------------------------- build
  const stage = document.getElementById("stage");
  stage.innerHTML = stage.innerHTML.replace(/\{(\w+)\}/g, (m, n) => (ICON[n] ? icon(n) : m));

  const $ = (id) => document.getElementById(id);
  $("i-mark").innerHTML = logoSvg("ilogo", 'width="260" height="260"');
  $("chrome-mark").innerHTML = logoSvg("clogo", 'width="40" height="40"');
  $("o-logo").innerHTML = logoSvg("ologo", 'width="240" height="240"');
  $("r-s1logo").innerHTML = logoSvg("rlogo", 'width="64" height="64"');
  for (const svg of document.querySelectorAll("#i-mark svg, #o-logo svg")) {
    const dot = svg.querySelector(".dot");
    dot.style.transformBox = "fill-box";
    dot.style.transformOrigin = "center";
  }

  // problem: endless generic feed
  {
    const r = rng(7);
    let html = "";
    for (let c = 0; c < 3; c++) {
      html += `<div class="pcol" id="p-c${c}" style="left:${c * 370}px">`;
      for (let i = 0; i < 8; i++) {
        const w1 = 90 + r() * 110, w2 = 60 + r() * 80;
        html += `<div class="ghost"><div class="gh"><div class="ga"></div><div style="flex:1"><div class="gl" style="width:${w1}px"></div><div class="gl" style="width:${w2}px;margin-top:8px;opacity:.6"></div></div></div><div class="gi" style="opacity:${.6 + r() * .4}"></div><div class="gr"><i></i><i></i><i></i></div></div>`;
      }
      html += "</div>";
    }
    $("p-wall").innerHTML = html;
    $("p-land").innerHTML = landscape(3, 560, 700);
    $("p-port").innerHTML = portrait(2, 176, 235);
  }

  // ritual
  const STEPS = [
    ["Connect your wallet", "Mobile Wallet Adapter · keys stay in your wallet"],
    ["Stake 500+ SKR", "Locked in the program vault, still yours"],
    ["Capture your Moment", "The scene first, then a selfie"],
    ["Review, sign, publish", "Your wallet signs the Moment"],
    ["Unlock today's feed", "See everyone's Moments, grow your streak"],
  ];
  $("r-steps").insertAdjacentHTML("beforeend", STEPS.map(([a, b], i) =>
    `<div class="step" id="r-st${i}" style="top:${i * 142}px"><div class="snum"><div class="sf" id="r-sf${i}"></div><div class="sd" id="r-sd${i}">${icon("check")}</div><div class="sn" id="r-sn${i}">${i + 1}</div></div><div><div class="stitle">${a}</div><div class="ssub">${b}</div></div></div>`).join(""));
  $("r-land").innerHTML = landscape(0, 396, 844);
  $("r-port").innerHTML = portrait(0, 396, 844);
  $("r-thumb").innerHTML = landscape(0, 66, 88);
  $("r-pair").innerHTML = landscape(0, 352, 430) + `<div class="selfie">${portrait(0)}</div><div class="ptag">On this device</div>`;
  $("r-feed").innerHTML = [
    { name: "Léa", meta: "2 h ago · 41 day streak", caption: "Up before the alarm, for once.", variant: 1, likes: 24 },
    { name: "Youssef", meta: "3 h ago · 17 day streak", caption: "Twenty minutes of walking before the city wakes up.", variant: 2, likes: 12 },
    { name: "Mara", meta: "5 h ago · 63 day streak", caption: "Last light on the rooftops.", variant: 4, likes: 31 },
  ].map((m) => momentCard({ ...m, photoH: 380 })).join("");
  $("r-coins").innerHTML = Array.from({ length: 6 }, (_, i) => `<div class="rc" id="r-coin${i}">${miniCoin(34)}</div>`).join("");

  // decay
  {
    let ticks = "";
    for (let h = 0; h < 24; h++) {
      const a = -Math.PI / 2 + h / 24 * Math.PI * 2, major = h % 6 === 0;
      const r0 = major ? 248 : 251, r1 = major ? 262 : 257;
      ticks += `<line x1="${290 + r0 * Math.cos(a)}" y1="${290 + r0 * Math.sin(a)}" x2="${290 + r1 * Math.cos(a)}" y2="${290 + r1 * Math.sin(a)}" stroke="${major ? "#80726A" : "#3a302a"}" stroke-width="${major ? 3 : 2}"/>`;
      if (major) ticks += `<text x="${290 + (284 + 26 * Math.abs(Math.cos(a))) * Math.cos(a)}" y="${290 + 284 * Math.sin(a) + 7}" text-anchor="middle" font-family="Inter" font-weight="600" font-size="20" fill="#80726A">${String(h).padStart(2, "0")}:00</text>`;
    }
    $("d-ticks").innerHTML = ticks;
    let html = `<div class="axis"></div>`;
    for (let i = 0; i <= 30; i++) {
      const bg = i === 0 ? "linear-gradient(180deg,#F4C56F,#EE9959)" : "linear-gradient(180deg,#FF8F7A,#A8324B)";
      html += `<div class="bar" id="d-b${i}" style="background:${bg}"></div>`;
    }
    ["1,000", "900", "810"].forEach((v, i) => { html += `<div class="barlab tnum" id="d-bl${i}" style="width:140px">${v}</div><div class="daylab" id="d-dl${i}" style="width:140px">${["Staked", "Missed 1", "Missed 2"][i]}</div>`; });
    ["Day 0", "Day 10", "Day 20", "Day 30"].forEach((v, i) => { html += `<div class="daylab" id="d-ax${i}" style="width:100px">${v}</div>`; });
    const pts = Array.from({ length: 31 }, (_, i) => `${i ? "L" : "M"}${(i * 28 + 10 + 28 * .66 / 2).toFixed(1)} ${(360 - 300 * Math.pow(.9, i)).toFixed(1)}`).join(" ");
    html += `<svg class="abs" viewBox="0 0 880 420" width="880" height="420" style="left:0;top:0;overflow:visible"><path id="d-curve" d="${pts}" fill="none" stroke="#FFD8CB" stroke-width="3" stroke-dasharray="1" stroke-linecap="round"/></svg>`;
    $("d-chart").innerHTML = html;
  }

  // pool
  const MISSED = [["Noor", 50], ["Théo", 40], ["Sacha", 30]];
  const PUBS = [["Léa", 1000, 40], ["Youssef", 500, 20], ["You", 1500, 60]];
  const ROWY = [440, 600, 760];
  $("pl-missed").innerHTML = MISSED.map(([n, v], i) =>
    `<div class="prow" id="pl-m${i}" style="left:180px;top:${ROWY[i]}px"><div class="pav grey"><div>${n[0]}</div></div><div><div class="pn" style="color:var(--muted)">${n}</div><div class="pv" style="color:var(--danger)">No Moment · −${v} SKR</div></div></div>`).join("");
  $("pl-pubs").innerHTML = PUBS.map(([n, v, s], i) =>
    `<div class="prow" id="pl-p${i}" style="left:1330px;top:${ROWY[i]}px">${i === 2 ? '<div class="youbg" id="pl-you"></div>' : ""}<div class="pav"><div>${n[0]}</div></div><div style="width:250px"><div class="pn">${n}</div><div class="pv"><span id="pl-v${i}" class="tnum">${fmt(v)}</span> SKR staked</div><div class="pbar" id="pl-bar${i}" style="width:${v / 1500 * 230}px"></div></div><div class="share" id="pl-s${i}">+${s}</div></div>`).join("");
  $("pl-parts").innerHTML = Array.from({ length: 36 }, (_, j) => `<div class="part" id="pl-q${j}">${miniCoin(26)}</div>`).join("");
  for (const id of ["pl-clock1", "pl-clock2"]) Object.assign($(id).style, { left: "0", right: "0", top: "350px", margin: "0 auto", width: "fit-content" });

  // fair: flow of penalties inside tile 1
  {
    let html = "";
    const grey = ["N", "T", "S"], warm = ["L", "Y", "M"];
    for (let i = 0; i < 3; i++) {
      html += `<div class="fav" style="left:70px;top:${44 + i * 70}px;background:#3a302a"><div style="background:#2B231D;color:#80726A">${grey[i]}</div></div>`;
      html += `<div class="fav" style="left:660px;top:${44 + i * 70}px;background:var(--grad)"><div style="background:#2B231D">${warm[i]}</div></div>`;
    }
    html += `<div class="abs" style="left:322px;top:62px;width:140px;height:140px">${skrCoin(140)}</div>`;
    for (let j = 0; j < 12; j++) html += `<div class="part" id="f-q${j}">${miniCoin(20)}</div>`;
    $("f-flow").innerHTML = html;
  }

  $("k-coin").innerHTML = skrCoin(360);

  // tech
  $("t-code").innerHTML = [
    "<b>network</b> devnet       <b>day</b>    20734",
    "<b>wallet</b>  7xKX…9fQa    <b>nonce</b>  c41f…8e02",
    "<b>rear</b>    <em>9f2c…41a1</em>    <b>selfie</b> <em>41be…07d3</em>",
  ].map((l, i) => `<div id="t-cl${i}">${l}</div>`).join("");
  $("t-checks").innerHTML = ["Wallet signature", "Photo hashes", "Server moderation", "On-chain eligibility", "Exact check_in tx"]
    .map((c, i) => `<div id="t-ck${i}">${icon("check")}${c}</div>`).join("");
  {
    $("sc-tech").style.display = "block";
    const pill = $("t-hash"), r = pill.getBoundingClientRect();
    $("sc-tech").style.display = "none";
    const fly = pill.cloneNode(true);
    fly.id = "t-hashfly";
    Object.assign(fly.style, { position: "absolute", left: r.left + "px", top: r.top + "px", margin: "0" });
    $("sc-tech").insertBefore(fly, $("t-dim"));
    fly._dx = 1590 - (r.left + r.width / 2);
    fly._dy = 396 - (r.top + r.height / 2);
  }

  // program
  const ACCOUNTS = [
    ["Config", '["config"]', [["min_stake", "500 SKR"], ["decay_bps", "1000 · 10%"], ["pool_close", "+6 h"], ["withdraw_delay", "48 h"], ["vault", "PDA"]]],
    ["Profile", '["profile", owner]', [["staked", "u64"], ["streak", "u32"], ["settled_day", "i64"], ["pending_claims", "[2]"], ["exit_unlock_at", "i64"]]],
    ["DayPool", '["day_pool", day]', [["penalties", "u64"], ["total_stake", "u64"], ["winners_count", "u32"], ["day", "i64"]]],
    ["CheckIn", '["checkin", owner, day]', [["commitment", "[u8; 32]"], ["blob_ref", "[u8; 32]"], ["slot", "u64"], ["streak", "u32"]]],
  ];
  $("g-cards").innerHTML = ACCOUNTS.map(([n, seeds, fields], i) =>
    `<div class="acc card" id="g-a${i}" style="left:${160 + i * 408}px"><div class="abar" id="g-ab${i}"></div><div class="an">${n}</div><div class="as" style="margin:8px 0 16px">${seeds}</div>${fields.map(([k, v]) => `<div class="af"><span>${k}</span><b>${v}</b></div>`).join("")}</div>`).join("");
  const PILLS = [
    ["reap", "Settles missed days", "Applies the decay and routes each penalty to its pool."],
    ["roll_over_day_pool", "Rolls empty pools forward", "No publisher? Penalties join today's pool."],
    ["close_check_in", "Refunds rent", "Old check-ins close after D+2; rent returns to the owner."],
  ];
  $("g-pills").innerHTML = PILLS.map(([ix, a, b], i) =>
    `<div class="gconn" id="g-cn${i}" style="left:${568 + i * 408 + 187}px"></div><div class="gpill card" id="g-pl${i}" style="left:${568 + i * 408}px"><div class="gi">${ix}</div><div class="gt">${a}</div><div class="gs">${b}</div></div>`).join("");

  // outro
  const SHIPPED = [
    ["Android app", "Kotlin · Jetpack Compose · CameraX"],
    ["Wallet flows", "Mobile Wallet Adapter: auth, messages, transactions"],
    ["Anchor program", "On devnet · LiteSVM test suite"],
    ["Rust keyserver", "Encryption, moderation, cosigning"],
    ["CI & releases", "Signed devnet APK on every tag"],
    ["Mainnet variant", "Build ready · deployment next", true],
  ];
  $("o-grid").innerHTML = SHIPPED.map(([a, b, soon], i) =>
    `<div class="oitem card" id="o-it${i}" style="left:${160 + (i % 3) * 545}px;top:${440 + Math.floor(i / 3) * 210}px"><div class="oc${soon ? " soon" : ""}">${icon(soon ? "clock" : "check")}</div><div><div class="ot">${a}</div><div class="os">${b}</div></div></div>`).join("");

  for (const id of ["i-tag", "p-h1", "p-h2", "p-l1", "p-l2", "p-h3", "d-h", "pl-h", "f-h", "k-h", "t-h", "g-h", "o-h", "o-tag"]) splitWords($(id));
  for (const id of ["i-word", "o-word"]) splitChars($(id));

  // film grain
  {
    const c = document.createElement("canvas");
    c.width = c.height = 512;
    const g = c.getContext("2d"), img = g.createImageData(512, 512), r = rng(42);
    for (let i = 0; i < img.data.length; i += 4) {
      const v = r() * 255;
      img.data[i] = img.data[i + 1] = img.data[i + 2] = v;
      img.data[i + 3] = 255;
    }
    g.putImageData(img, 0, 0);
    $("grain").style.backgroundImage = `url(${c.toDataURL()})`;
  }

  const flash = (t, a) => (t < a ? 0 : t < a + .06 ? (t - a) / .06 * .92 : .92 * (1 - P(t, a + .06, .5, "out")));
  const bump = (t, a, d) => Math.sin(Math.PI * clamp((t - a) / d));

  // ---------------------------------------------------------------- 1 intro
  const I = {
    ring0: .25, ring1: 2.35, draw0: .5, draw1: 2.05, dot: 1.9, move: 2.4,
    word: cue("intro", "this") - .1, tag: cue("intro", "tag"), sub: cue("intro", "tag", .3), chips: cue("intro", "tag", .62),
  };
  fx("intro", .05, "swell", .8, 2.6);
  fx("intro", I.dot, "pop", .9);
  fx("intro", I.word, "whoosh", .45);
  fx("intro", I.tag, "shimmer", .5);
  [0, 1, 2].forEach((i) => fx("intro", I.chips + i * .12, "pop", .25));
  const introArch = $("i-mark").querySelector(".arch"), introDot = $("i-mark").querySelector(".dot");

  function intro(t) {
    const rp = P(t, I.ring0, I.ring1 - I.ring0, "inOut");
    const C = 2 * Math.PI * 190, arc = el("i-arc");
    arc.style.strokeDasharray = `${C} ${C}`;
    arc.style.strokeDashoffset = (C * (1 - rp)).toFixed(2);
    const a = -Math.PI / 2 + rp * 2 * Math.PI;
    for (const id of ["i-head", "i-headbg"]) {
      const h = el(id);
      h.setAttribute("cx", (200 + 190 * Math.cos(a)).toFixed(2));
      h.setAttribute("cy", (200 + 190 * Math.sin(a)).toFixed(2));
      h.style.opacity = rp > .001 ? 1 : 0;
    }
    show("i-ring", t, I.ring0 - .2, { from: { o: 0, s: .9 }, d: .6, out: I.move - .1, od: .5, to: { o: 0, s: 1.12 } });
    dash(introArch, P(t, I.draw0, I.draw1 - I.draw0, "inOut"));
    apply(introDot, { s: Math.max(0, P(t, I.dot, .6, "outBack")), y: (1 - P(t, I.dot, .6)) * 40, o: t >= I.dot ? 1 : 0 });
    const rp2 = P(t, I.dot + .12, 1.1);
    apply("i-ripple", { s: 1 + rp2 * 3.2, o: t >= I.dot + .12 ? (1 - rp2) * .8 : 0 });
    const mp = P(t, I.move, 1.0, "inOut");
    apply("i-logo", { y: -220 * mp, s: 1 - .42 * mp });
    words("i-word", t, I.word, { st: .045, d: .9 });
    words("i-tag", t, I.tag, { st: .09 });
    show("i-sub", t, I.sub, { from: { o: 0, y: 20 } });
    [0, 1, 2].forEach((i) => show("i-chip" + i, t, I.chips + i * .12, { from: { o: 0, y: 24, s: .96 } }));
  }

  // ---------------------------------------------------------------- 2 problem
  const PR = (() => {
    const id = "problem";
    return {
      h1: cue(id, "scroll"), stop: cue(id, "rarer") - .3, h1out: cue(id, "rarer") - .45, h2: cue(id, "rarer") + .05, h2out: cue(id, "dual") - .5,
      photo: cue(id, "dual") - .2, l1: cue(id, "dual", .16), snap: cue(id, "dual", .42), l2: cue(id, "dual", .56), selfie: cue(id, "dual", .62), l3: cue(id, "dual", .8),
      lout: cue(id, "line") - .45, h3: cue(id, "line"), stake: cue(id, "line", .6),
    };
  })();
  fx("problem", .05, "scroll", .45, PR.stop + .9);
  fx("problem", PR.h2, "whoosh", .35);
  fx("problem", PR.photo, "whoosh", .25);
  fx("problem", PR.snap, "shutter", .9);
  fx("problem", PR.selfie, "shutter", .7);
  fx("problem", PR.stake + .25, "coin", .8);

  function problem(t) {
    const u = clamp(t - PR.stop, 0, 1);
    const pos = 1150 * (Math.min(t, PR.stop) + (u - u * u / 2));
    [1, 1.3, .85].forEach((m, c) => { el("p-c" + c).style.transform = `translateY(${(-((pos * m + c * 610) % 1824)).toFixed(1)}px)`; });
    let wo = .75 * P(t, 0, .6);
    wo = lerp(wo, .14, P(t, PR.h1out, 1, "inOut"));
    wo = lerp(wo, 0, P(t, PR.h2out, .5, "inOut"));
    apply("p-wall", { o: wo });
    words("p-h1", t, PR.h1, { st: .06, out: PR.h1out, od: .4 });
    words("p-h2", t, PR.h2, { st: .07, out: PR.h2out, od: .45 });
    show("p-photo", t, PR.photo, { from: { o: 0, s: .9, y: 40 }, d: .9 });
    const sp = P(t, PR.snap, .25);
    apply("p-scrim", { o: .45 * (1 - sp) });
    apply("p-vf", { o: 1 - sp, s: 1 + .04 * sp });
    apply("p-flash", { o: flash(t, PR.snap) });
    show("p-selfie", t, PR.selfie, { from: { o: 0, s: .5 }, d: .6, ease: "outBack" });
    apply("p-sflash", { o: flash(t, PR.selfie + .05) });
    words("p-l1", t, PR.l1, { out: PR.lout });
    words("p-l2", t, PR.l2, { out: PR.lout });
    show("p-l3", t, PR.l3, { from: { o: 0, y: 20 }, out: PR.lout });
    words("p-h3", t, PR.h3, { st: .07 });
    show("p-stake", t, PR.stake, { from: { o: 0, y: -80, s: .7 }, d: .7, ease: "outBack" });
  }

  // ---------------------------------------------------------------- 3 ritual
  const R = (() => {
    const id = "ritual";
    const w = cue(id, "wallet"), s = cue(id, "stake"), c = cue(id, "capture"), p = cue(id, "publish"), f = cue(id, "feed");
    return {
      w, s, c, p, f,
      tap1: w + .95, sh1: w + 1.15, tap2: w + 2.1, sh1out: w + 2.35, toast1: w + 2.5,
      s2: s - .3, countA: s + .25, countB: s + 1.45, tapStake: s + 2.0, sh2: s + 2.2, tapAp: s + 3.15, sh2out: s + 3.4, ok2: s + 3.55, coins: s + 3.6,
      s3: c - .4, shut1: c + .8, s4: c + 1.2, shut2: c + 2.15,
      s5: p - .6, anl2: p + .2, tapPub: p + .6, sh3: p + .8, tapAp3: p + 1.55, sh3out: p + 1.75, toast3: p + 1.85,
      s6: f - .2, unlock: f + .85, scroll: f + 1.9, tagD: f + 1.2, tagE: f + 1.9,
    };
  })();
  const RSTEP = [R.w, R.s, R.c, R.p, R.f];
  const SCREENS = [["r-s1", 0], ["r-s2", R.s2], ["r-s3", R.s3], ["r-s4", R.s4], ["r-s5", R.s5], ["r-s6", R.s6]];
  const TAPS = [[R.tap1, 198, 729], [R.tap2, 289, 792], [R.tapStake, 198, 755], [R.tapAp, 289, 792], [R.tapPub, 198, 781], [R.tapAp3, 289, 792]];
  TAPS.forEach(([a]) => fx("ritual", a, "tap", .55));
  [R.sh1, R.sh2, R.sh3].forEach((a) => fx("ritual", a, "sheet", .3));
  fx("ritual", R.toast1, "chime", .35);
  fx("ritual", R.ok2, "chime", .55);
  fx("ritual", R.toast3, "chime", .55);
  fx("ritual", R.unlock, "unlock", .6);
  for (let i = 0; i < 6; i++) fx("ritual", R.coins + i * .1 + .7, "coin", .3);
  for (let k = 1; k <= 10; k++) fx("ritual", R.countA + (R.countB - R.countA) * (k - .5) / 10, "tick", .18);
  fx("ritual", R.shut1, "shutter", .85);
  fx("ritual", R.shut2, "shutter", .75);
  [R.w + .5, R.s + .5, R.c + .3, R.p + .1, R.p + 1.6, R.tagD, R.tagE].forEach((a) => fx("ritual", a, "pop", .22));
  [R.s2, R.s3, R.s5, R.s6].forEach((a) => fx("ritual", a, "swish", .18));

  function sheet(id, t, a, b) {
    const p = P(t, a, .45) * (1 - P(t, b, .35, "in"));
    apply(id, { y: (1 - p) * 440, o: p > .001 ? 1 : 0 });
    return p;
  }

  function ritual(t) {
    show("r-kicker", t, .1, { from: { o: 0, x: -30 } });
    show("r-phone", t, .05, { from: { o: 0, y: 90, s: .96 }, d: 1.0 });
    let rail = 0;
    RSTEP.forEach((a, i) => {
      const b = RSTEP[i + 1] ?? 1e9;
      const enter = P(t, .2 + i * .09, .6);
      const on = P(t, a - .15, .45, "inOut"), off = P(t, b - .15, .45, "inOut");
      apply("r-st" + i, { o: lerp(lerp(.3, 1, on), .5, off) * enter, x: (1 - enter) * -30 });
      apply("r-sf" + i, { o: on * (1 - off) });
      apply("r-sd" + i, { o: off, s: lerp(.6, 1, off) });
      apply("r-sn" + i, { o: 1 - off });
      el("r-sn" + i).style.color = on > .5 && off < .5 ? "#170D08" : "#F7F0E9";
      if (i > 0) rail += P(t, a - .15, .6, "inOut");
    });
    apply("r-railfill", { sy: rail / 4, o: P(t, .2, .6) });

    SCREENS.forEach(([id, a], i) => {
      const next = SCREENS[i + 1]?.[1] ?? 1e9;
      if (id === "r-s4") { show(id, t, a, { from: { o: 0, s: 1.06 }, d: .35, out: next, od: .5, to: { o: 0, x: -140 } }); return; }
      const pin = i === 0 ? 1 : P(t, a, .5, "inOut");
      const pout = id === "r-s3" ? 0 : P(t, next, .5, "inOut");
      const visible = (i === 0 || t >= a) && t < next + (id === "r-s3" ? .4 : .5);
      apply(id, { x: (1 - pin) * 396 - pout * 140, o: visible ? 1 - pout * .9 : 0 });
    });

    // taps
    let tap = null;
    for (const tp of TAPS) if (t >= tp[0] && t < tp[0] + .5) tap = tp;
    const rip = el("r-rip");
    if (tap) {
      const p = P(t, tap[0], .5);
      rip.style.left = tap[1] + "px";
      rip.style.top = tap[2] + "px";
      apply(rip, { s: .3 + p * 2.6, o: .55 * (1 - p) });
    } else apply(rip, { o: 0 });

    const sc = Math.max(sheet("r-sh1", t, R.sh1, R.sh1out), sheet("r-sh2", t, R.sh2, R.sh2out), sheet("r-sh3", t, R.sh3, R.sh3out));
    apply("r-scrim", { o: sc });
    show("r-toast1", t, R.toast1, { from: { o: 0, y: -20 } });

    const cp = P(t, R.countA, R.countB - R.countA, "inOut");
    el("r-amt").textContent = Math.round(cp * 10) * 50;
    apply("r-plus", { s: t > R.countA && t < R.countB ? 1 - .12 * bump(t, R.countA + Math.floor((t - R.countA) / .12) * .12, .12) : 1 });
    show("r-ok2", t, R.ok2, { from: { o: 0, s: .96 }, d: .5 });
    show("r-vault", t, R.s + .5, { from: { o: 0, x: 40 }, out: R.c - .5, to: { o: 0, x: 40 } });
    el("r-vamt").textContent = fmt(500 * P(t, R.coins + .55, .8, "inOut"));
    for (let i = 0; i < 6; i++) {
      const a = R.coins + i * .1, p = P(t, a, .75, "inOut");
      const [x, y] = arcPoint(1270, 700, 1610, 480, p, 170);
      apply("r-coin" + i, { x: x - 17, y: y - 17, o: t >= a && p < 1 ? 1 : 0, s: lerp(.7, 1.1, Math.sin(Math.PI * p)) });
    }

    apply("r-flash1", { o: flash(t, R.shut1) });
    apply("r-flash2", { o: flash(t, R.shut2) });
    apply("r-shut1", { s: 1 - .14 * bump(t, R.shut1 - .1, .3) });
    apply("r-shut2", { s: 1 - .14 * bump(t, R.shut2 - .1, .3) });

    const a1 = P(t, R.s5 + .35, .3), a2 = P(t, R.anl2, .3);
    apply("r-anl1", { o: a1 * (1 - a2) });
    apply("r-anl2", { o: a2 });
    el("r-spin").style.transform = `rotate(${(t * 420).toFixed(1)}deg)`;
    show("r-toast3", t, R.toast3, { from: { o: 0, y: -20 } });

    const up = P(t, R.unlock, .9, "inOut");
    el("r-feed").style.filter = up < 1 ? `blur(${(14 * (1 - up)).toFixed(2)}px)` : "none";
    apply("r-lock", { o: 1 - P(t, R.unlock, .5) });
    apply("r-feed", { y: -560 * P(t, R.scroll, SC.ritual.dur - R.scroll - .3, "inOut") });

    const side = { from: { o: 0, x: 40 }, to: { o: 0, x: 40 } };
    show("r-tagA", t, R.w + .5, { ...side, out: R.s - .3 });
    show("r-tagB", t, R.c + .3, { ...side, out: R.p - .5 });
    show("r-tagC", t, R.p + .1, { ...side, out: R.f - .1 });
    show("r-tagC2", t, R.p + 1.6, { ...side, out: R.f - .1 });
    show("r-tagD", t, R.tagD, { from: { o: 0, x: 40 } });
    show("r-tagE", t, R.tagE, { from: { o: 0, x: 40, s: .85 }, ease: "outBack" });
  }

  // ---------------------------------------------------------------- 4 decay
  const D = (() => {
    const id = "decay", m = cue(id, "miss"), s = cue(id, "seq"), mo = cue(id, "month");
    return {
      kicker: .15, h: cue(id, "round", .33), ring: .45, sweep0: .9, sweep1: m - .25,
      red0: m + .1, red1: m + 1.2, panel: m + .25, pct: cue(id, "miss", .5), deduct: cue(id, "miss", .55),
      bar0: s + .05, bar1: cue(id, "seq", .4), bar2: cue(id, "seq", .72),
      compress: mo + .15, rest: mo + .55, curve: mo + 1.6, note: cue(id, "month", .68),
    };
  })();
  fx("decay", .05, "swell", .45, 1.6);
  fx("decay", D.red0, "error", .55);
  fx("decay", D.pct, "pop", .4);
  fx("decay", D.deduct, "drop", .6);
  [D.bar0, D.bar1, D.bar2].forEach((a) => fx("decay", a, "pop", .3));
  for (let i = 3; i <= 30; i += 3) fx("decay", D.rest + (i - 3) * .075, "tick", .14);
  fx("decay", D.note, "pop", .35);

  function decay(t) {
    show("d-kicker", t, D.kicker, { from: { o: 0, x: -30 } });
    words("d-h", t, D.h, { st: .06 });
    show("d-ringwrap", t, D.ring, { from: { o: 0, s: .9 }, d: .8 });
    const C = 2 * Math.PI * 230;
    const p1 = P(t, D.sweep0, D.sweep1 - D.sweep0, "inOut"), p2 = P(t, D.red0, D.red1 - D.red0, "inOut");
    const arc1 = el("d-arc"), arc2 = el("d-arc2");
    arc1.style.strokeDasharray = arc2.style.strokeDasharray = `${C} ${C}`;
    arc1.style.strokeDashoffset = (C * (1 - p1)).toFixed(2);
    arc2.style.strokeDashoffset = (C * (1 - p2)).toFixed(2);
    arc1.style.opacity = 1 - P(t, D.red0 - .1, .3);
    arc2.style.opacity = t >= D.red0 ? 1 : 0;
    const red = t >= D.red0, hp = red ? p2 : p1, ang = -Math.PI / 2 + hp * 2 * Math.PI;
    for (const id of ["d-head", "d-headbg"]) {
      const h = el(id);
      h.setAttribute("cx", (290 + 230 * Math.cos(ang)).toFixed(2));
      h.setAttribute("cy", (290 + 230 * Math.sin(ang)).toFixed(2));
      h.style.opacity = hp > .001 && (red || t < D.red0 - .1) ? 1 : 0;
    }
    el("d-head").setAttribute("fill", red ? "#FF7A7A" : "#F4C56F");
    show("d-c1", t, D.ring + .3, { from: { o: 0, s: .9 }, out: D.red0, od: .3, to: { o: 0, s: .9 } });
    show("d-c2", t, D.red0 + .25, { from: { o: 0, s: .7 }, ease: "outBack", d: .6 });
    apply("d-ringwrap", { o: P(t, D.ring, .8) * lerp(1, .55, P(t, D.compress, .6)), s: lerp(.9, 1, P(t, D.ring, .8)) * (1 + .03 * bump(t, D.red1, .4)) });
    show("d-pct", t, D.pct, { from: { o: 0, s: .5 }, ease: "outBack", d: .6 });
    show("d-panel", t, D.panel, { from: { o: 0, y: 30 } });

    let v = lerp(1000, 900, P(t, D.deduct, .7, "inOut"));
    v = lerp(v, 810, P(t, D.bar2, .6, "inOut"));
    if (t > D.rest) v = 1000 * Math.pow(.9, 2 + 28 * P(t, D.rest, 2.2, "inOut"));
    el("d-num").textContent = fmt(v);
    const hot = Math.max(bump(t, D.deduct, .9), bump(t, D.bar2, .8));
    el("d-num").style.color = hot > .02 ? `rgb(${lerp(247, 255, hot)},${lerp(240, 122, hot)},${lerp(233, 122, hot)})` : "";
    show("d-minus", t, D.deduct, { from: { o: 0, x: -40 }, d: .5, out: D.deduct + 1.6, od: .7, to: { o: 0, x: 180 } });

    show("d-chart", t, D.bar0 - .25, { from: { o: 0 }, d: .4 });
    const pitch = lerp(200, 28, P(t, D.compress, 1.0, "inOut")), bw = pitch * .66;
    const labO = 1 - P(t, D.compress, .4);
    for (let i = 0; i <= 30; i++) {
      const a = i === 0 ? D.bar0 : i === 1 ? D.bar1 : i === 2 ? D.bar2 : D.rest + (i - 3) * .075;
      const g = P(t, a, .5);
      const h = 300 * Math.pow(.9, i) * g, b = el("d-b" + i);
      b.style.left = (i * pitch + 10).toFixed(1) + "px";
      b.style.width = bw.toFixed(1) + "px";
      b.style.height = h.toFixed(1) + "px";
      b.style.opacity = g > 0 ? 1 : 0;
      if (i <= 2) {
        const cx = i * pitch + 10 + bw / 2;
        Object.assign(el("d-bl" + i).style, { left: (cx - 70).toFixed(1) + "px", top: (360 - h - 46).toFixed(1) + "px", opacity: (g * labO).toFixed(3) });
        Object.assign(el("d-dl" + i).style, { left: (cx - 70).toFixed(1) + "px", opacity: (g * labO).toFixed(3) });
      }
    }
    [0, 10, 20, 30].forEach((i, k) => Object.assign(el("d-ax" + k).style, { left: (i * 28 + 10 + 9.24 - 50).toFixed(1) + "px", opacity: P(t, D.compress + .6, .5).toFixed(3) }));
    dash("d-curve", P(t, D.curve, 1.4, "inOut"));
    show("d-note", t, D.note, { from: { o: 0, y: 20, s: .95 } });
  }

  // ---------------------------------------------------------------- 5 pool
  const PL = (() => {
    const id = "pool";
    return {
      kicker: .1, h: cue(id, "fund") - .05, mh: .5, missed: .6, orb: .7, flow0: 1.0, flow1: 2.6,
      clock: cue(id, "close") - .05, ring0: cue(id, "close", .04), ring1: cue(id, "close", .34), lock: cue(id, "close", .36),
      ph: cue(id, "close", .43), pubs: cue(id, "close", .46), bars: cue(id, "close", .6), formula: cue(id, "close", .78),
      you: cue(id, "example") + .05, total: cue(id, "example", .28), split0: cue(id, "example", .48), f2: cue(id, "example", .5), compound: cue(id, "example", .75),
    };
  })();
  PL.shares = PL.split0 + .8;
  const OUT_ROW = [0, 2, 0, 1, 2, 2, 0, 2, 1, 0, 2, 2, 0, 1, 2, 0, 2, 2];
  for (let j = 0; j < 18; j += 3) fx("pool", PL.flow0 + j * .09 + .8, "coin", .3);
  fx("pool", PL.clock, "tick", .4);
  fx("pool", PL.lock, "lock", .6);
  [0, 1, 2].forEach((i) => fx("pool", PL.pubs + i * .15, "pop", .22));
  for (let j = 0; j < 18; j += 3) fx("pool", PL.split0 + j * .06 + .7, "coin", .32);
  fx("pool", PL.compound, "chime", .6);

  function pool(t) {
    show("pl-kicker", t, PL.kicker, { from: { o: 0, x: -30 } });
    words("pl-h", t, PL.h, { st: .06 });
    show("pl-mh", t, PL.mh, { from: { o: 0, y: 14 } });
    MISSED.forEach((_, i) => show("pl-m" + i, t, PL.missed + i * .12, { from: { o: 0, x: -30 } }));
    show("pl-orb", t, PL.orb, { from: { o: 0, s: .85 }, d: .9, base: { s: 1 + .015 * Math.sin(t * 3) } });
    const r = rng(11);
    for (let j = 0; j < 18; j++) {
      const a = PL.flow0 + j * .09, p = P(t, a, .8, "inOut"), row = j % 3;
      const [x, y] = arcPoint(216, ROWY[row] + 36, 960 + (r() - .5) * 60, 630 + (r() - .5) * 60, p, (row - 1) * -60 + 40);
      apply("pl-q" + j, { x: x - 13, y: y - 13, o: t >= a && p < 1 ? 1 : 0, s: lerp(.8, 1.15, Math.sin(Math.PI * p)) });
    }
    for (let j = 0; j < 18; j++) {
      const a = PL.split0 + j * .06, p = P(t, a, .75, "inOut"), row = OUT_ROW[j];
      const [x, y] = arcPoint(960, 630, 1330 + 36, ROWY[row] + 36, p, 50 + row * 10);
      apply("pl-q" + (18 + j), { x: x - 13, y: y - 13, o: t >= a && p < 1 ? 1 : 0, s: lerp(.8, 1.15, Math.sin(Math.PI * p)) });
    }
    const amt = 120 * P(t, PL.flow0 + .6, PL.flow1 - PL.flow0 + .4, "inOut") * (1 - P(t, PL.split0 + .2, 1.3, "inOut"));
    el("pl-amt").textContent = fmt(amt);
    show("pl-clock1", t, PL.clock, { from: { o: 0, y: 14 }, out: PL.lock, od: .3, to: { o: 0, y: -14 } });
    show("pl-clock2", t, PL.lock + .15, { from: { o: 0, y: 14 } });
    dash("pl-closering", P(t, PL.ring0, PL.ring1 - PL.ring0, "inOut"));
    el("pl-closering").style.opacity = t >= PL.ring0 ? 1 : 0;
    show("pl-lock", t, PL.lock, { from: { o: 0, s: .4 }, ease: "outBack", d: .6 });
    show("pl-ph", t, PL.ph, { from: { o: 0, y: 14 } });
    apply("pl-total", { s: 1 + .12 * bump(t, PL.total, .5) });
    el("pl-total").style.display = "inline-block";
    PUBS.forEach(([, v, s], i) => {
      show("pl-p" + i, t, PL.pubs + i * .15, { from: { o: 0, x: 30 } });
      apply("pl-bar" + i, { sx: P(t, PL.bars + i * .12, .7, "inOut") });
      show("pl-s" + i, t, PL.shares + i * .12, { from: { o: 0, x: -10, s: .8 }, ease: "outBack", d: .5 });
      const g = P(t, PL.compound, .8, "inOut");
      el("pl-v" + i).textContent = fmt(v + s * g);
      el("pl-v" + i).style.color = bump(t, PL.compound, 1.2) > .05 ? "#3ECFB0" : "";
    });
    show("pl-you", t, PL.you, { from: { o: 0, s: .96 } });
    show("pl-formula", t, PL.formula, { from: { o: 0, y: 30 } });
    apply("pl-f1", { o: 1 - P(t, PL.f2, .4) });
    show("pl-f2", t, PL.f2, { from: { o: 0, y: 16 } });
  }

  // ---------------------------------------------------------------- 6 fair
  const F = (() => {
    const id = "fair";
    return {
      kicker: .1, h: .25,
      t: [cue(id, "mint") - .2, cue(id, "forfeit") - .2, cue(id, "rollover") - .2, cue(id, "exit") - .2],
      z1: cue(id, "mint", .12), z2: cue(id, "mint", .62), flow: cue(id, "forfeit") + .2, roll: cue(id, "rollover", .5),
      req: cue(id, "exit", .28), w0: cue(id, "exit", .42), home: cue(id, "exit", .8),
    };
  })();
  F.w1 = F.w0 + 1.3;
  F.t.forEach((a) => fx("fair", a, "pop", .3));
  fx("fair", F.z1, "thud", .45);
  fx("fair", F.z2, "thud", .45);
  for (let k = 0; k < 4; k++) fx("fair", F.flow + .5 + k * .5, "coin", .2);
  fx("fair", F.roll, "swish", .45);
  fx("fair", F.req, "tap", .45);
  for (let k = 0; k < 6; k++) fx("fair", F.w0 + k * .22, "tick", .16);
  fx("fair", F.home, "chime", .55);

  function fair(t) {
    show("f-kicker", t, F.kicker, { from: { o: 0, x: -30 } });
    words("f-h", t, F.h, { st: .05 });
    F.t.forEach((a, i) => {
      const next = F.t[i + 1] ?? 1e9;
      const p = P(t, a, .8);
      apply("f-t" + i, { o: p * lerp(1, .62, P(t, next, .5)), y: (1 - p) * 40, s: lerp(.97, 1, p) });
      apply("f-g" + i, { o: P(t, a, .4) * (1 - P(t, next, .4)) });
    });
    show("f-z1", t, F.z1, { from: { o: 0, s: .6 }, ease: "outBack", d: .6 });
    show("f-z2", t, F.z2, { from: { o: 0, s: .6 }, ease: "outBack", d: .6 });
    for (let j = 0; j < 12; j++) {
      const ph = t > F.flow ? ((t - F.flow) * .5 + j / 12) % 1 : -1;
      const fade = P(t, F.flow, .6);
      if (ph < 0) { apply("f-q" + j, { o: 0 }); continue; }
      let x, y;
      if (ph < .5) [x, y] = arcPoint(97, 71 + (j % 3) * 70, 392, 132, ph * 2, 20);
      else [x, y] = arcPoint(392, 132, 687, 71 + ((j + 1) % 3) * 70, ph * 2 - 1, 20);
      apply("f-q" + j, { x: x - 10, y: y - 10, o: fade * Math.min(1, Math.sin(Math.PI * ph) * 3) });
    }
    const rp = P(t, F.roll, .9, "inOut");
    apply("f-rchip", { x: 440 * rp, y: -40 * Math.sin(Math.PI * rp), o: P(t, F.t[2] + .4, .4) });
    apply("f-rbar", { sx: P(t, F.roll - .1, .9, "inOut") });
    el("f-ra").textContent = rp > .5 ? "0 SKR" : "120 SKR";
    el("f-rb").textContent = rp >= 1 ? "+120 SKR" : "+0 SKR";
    const wp = P(t, F.w0, F.w1 - F.w0, "inOut");
    apply("f-wfill", { sx: wp });
    el("f-wcount").textContent = Math.round(48 * wp) + " h";
    el("f-n0").style.background = t >= F.req ? "linear-gradient(120deg,#F4C56F,#E94F75)" : "";
    el("f-n0").style.color = t >= F.req ? "#170D08" : "";
    el("f-n1").style.background = wp >= 1 ? "#0F3A32" : "";
    el("f-n1").style.color = wp >= 1 ? "#8FE8D2" : "";
    show("f-home", t, F.home, { from: { o: 0, x: -20 } });
  }

  // ---------------------------------------------------------------- 7 skr
  const K = (() => {
    const id = "skr";
    return { kicker: .1, h: cue(id, "utility"), coin: .25, c: [cue(id, "list") + .05, cue(id, "list", .36), cue(id, "list", .7)] };
  })();
  fx("skr", K.coin, "whoosh", .4);
  fx("skr", K.coin + 1.3, "coin", .7);
  K.c.forEach((a) => fx("skr", a, "pop", .32));

  function skr(t) {
    show("k-kicker", t, K.kicker, { from: { o: 0, x: -30 } });
    words("k-h", t, K.h, { st: .07 });
    const sp = P(t, K.coin, 1.6);
    const ang = (1 - sp) * 4 * Math.PI;
    apply("k-coin", { sx: Math.max(.04, Math.abs(Math.cos(ang))), s: lerp(.6, 1, sp), o: P(t, K.coin, .3) });
    apply("k-coinwrap", { y: 10 * Math.sin(t * 1.6) });
    show("k-orbit", t, K.coin + .4, { from: { o: 0, s: .85 }, base: { r: t * 14 }, d: 1 });
    K.c.forEach((a, i) => {
      dash("k-l" + i, P(t, a - .15, .6, "inOut"));
      show("k-c" + i, t, a, { from: { o: 0, x: i === 0 ? -40 : 40 } });
    });
  }

  // ---------------------------------------------------------------- 8 tech
  const T = (() => {
    const id = "tech";
    return {
      kicker: .1, h: .25, app: cue(id, "app") + .05, tags: cue(id, "app", .5),
      wallet: cue(id, "manifest") + .05, man: cue(id, "manifest") + .2, pk1: cue(id, "manifest", .3), signed: cue(id, "manifest", .46),
      sol: cue(id, "manifest", .52), d2: cue(id, "manifest", .58), fly: cue(id, "manifest", .8),
      dOut: cue(id, "encrypt") - .35, d3: cue(id, "encrypt") - .1, x0: cue(id, "encrypt") + .1, x1: cue(id, "encrypt", .52), ks: cue(id, "encrypt", .6), x2: cue(id, "encrypt", .78), pk2: cue(id, "encrypt", .68), r2: cue(id, "encrypt", .85),
      d3Out: cue(id, "moderation") - .3, dev: cue(id, "moderation") + .1, d4: cue(id, "moderation", .36), pg: cue(id, "moderation", .42), checks: cue(id, "moderation", .45), cosign: cue(id, "moderation", .84), bks: cue(id, "moderation", .92),
      trust: cue(id, "trust") - .3, tr1: cue(id, "trust") - .05, tr2: cue(id, "trust", .45),
    };
  })();
  [T.app, T.wallet, T.sol, T.ks, T.r2, T.pg].forEach((a) => fx("tech", a, "pop", .3));
  [0, 1, 2].forEach((i) => fx("tech", T.man + .2 + i * .25, "tick", .2));
  fx("tech", T.pk1, "swish", .25);
  fx("tech", T.signed, "stamp", .55);
  fx("tech", T.fly, "whoosh", .35);
  fx("tech", T.fly + .9, "pop", .3);
  fx("tech", T.x1, "lock", .5);
  fx("tech", T.pk2, "swish", .25);
  fx("tech", T.dev, "chime", .3);
  for (let i = 0; i < 5; i++) fx("tech", T.checks + i * .42, "tick", .3);
  fx("tech", T.cosign, "swish", .3);
  fx("tech", T.cosign + .9, "chime", .5);
  fx("tech", T.trust, "swell", .35, 1.2);
  fx("tech", T.tr1, "thud", .5);
  fx("tech", T.tr2, "lock", .6);

  function along(id, path, p) {
    const e = el(path);
    if (!e._len) e._len = e.getTotalLength();
    const pt = e.getPointAtLength(e._len * clamp(p));
    apply(id, { x: pt.x, y: pt.y, o: p > 0 && p < 1 ? 1 : 0 });
  }

  function tech(t) {
    show("t-kicker", t, T.kicker, { from: { o: 0, x: -30 } });
    words("t-h", t, T.h, { st: .06 });
    const node = { from: { o: 0, y: 24, s: .95 }, d: .6 };
    show("t-app", t, T.app, node);
    show("t-wallet", t, T.wallet, node);
    show("t-sol", t, T.sol, node);
    show("t-ks", t, T.ks, node);
    show("t-r2", t, T.r2, node);
    show("t-pg", t, T.pg, node);
    [0, 1, 2].forEach((i) => show("t-tag" + i, t, T.tags + i * .1, { from: { o: 0, y: 10 }, out: T.dev - .2, od: .3, to: { o: 0, y: -6 } }));
    const edge = (id, a) => { dash(id, P(t, a, .6, "inOut")); el(id).style.opacity = t >= a ? 1 : 0; };
    edge("t-e1", T.wallet + .2);
    edge("t-e2", T.ks + .1);
    edge("t-e3", T.cosign - .2);
    edge("t-e4", T.pg + .1);
    edge("t-e5", T.r2 - .2);
    along("t-pk1", "t-e1", P(t, T.pk1, .8, "inOut"));
    const p2 = P(t, T.pk2, 1.5, "inOut");
    if (p2 < .5) along("t-pk2", "t-e2", p2 * 2); else along("t-pk2", "t-e5", p2 * 2 - 1);
    if (p2 >= 1 || p2 <= 0) apply("t-pk2", { o: 0 });
    along("t-pk3", "t-e3", P(t, T.cosign + .1, .8, "inOut"));

    const badge = { from: { o: 0, y: 10, s: .9 }, d: .5, ease: "outBack" };
    show("t-bsign", t, T.signed, badge);
    show("t-bdev", t, T.dev, badge);
    show("t-bsol", t, T.fly + .9, { ...badge, out: T.cosign + .5, od: .3, to: { o: 0, y: -8 } });
    show("t-bsol2", t, T.cosign + .9, badge);
    show("t-br2", t, T.r2 + .6, badge);
    show("t-bks", t, T.bks, badge);

    show("t-d1", t, T.man, { from: { o: 0, y: 30 }, out: T.dOut, od: .35 });
    [0, 1, 2].forEach((i) => show("t-cl" + i, t, T.man + .2 + i * .25, { from: { o: 0, x: -14 }, d: .4 }));
    show("t-d2", t, T.d2, { from: { o: 0, y: 30 }, out: T.dOut, od: .35 });
    const hf = el("t-hashfly"), fp = P(t, T.fly, .9, "inOut");
    apply(hf, { x: hf._dx * fp, y: hf._dy * fp - Math.sin(Math.PI * fp) * 140, s: lerp(1, .55, fp), o: t >= T.fly && fp < 1 ? 1 : 0 });
    show("t-d3", t, T.d3, { from: { o: 0, y: 30 }, out: T.d3Out, od: .35 });
    show("t-x0", t, T.x0, { from: { o: 0, x: -16 } });
    show("t-xa0", t, T.x0 + .35, { from: { o: 0, x: -10 } });
    show("t-x1", t, T.x1, { from: { o: 0, x: -16 } });
    show("t-xa1", t, T.x1 + .35, { from: { o: 0, x: -10 } });
    show("t-x2", t, T.x2, { from: { o: 0, x: -16 } });
    show("t-d4", t, T.d4, { from: { o: 0, y: 30 } });
    for (let i = 0; i < 5; i++) show("t-ck" + i, t, T.checks + i * .42, { from: { o: .25, x: -10 }, d: .35 });
    show("t-dim", t, T.trust, { from: { o: 0 }, d: .5 });
    show("t-trust1", t, T.tr1, { from: { o: 0, y: 30 } });
    show("t-trust2", t, T.tr2, { from: { o: 0, y: 30 } });
  }

  // ---------------------------------------------------------------- 9 program
  const G = (() => {
    const id = "program";
    return {
      kicker: .1, h: .25, pid: .9, ghost: 1.3,
      a: [cue(id, "accounts", .62), cue(id, "accounts", .72), cue(id, "accounts", .81), cue(id, "accounts", .91)],
      crank: cue(id, "crank") - .1, p: [cue(id, "crank", .27), cue(id, "crank", .51), cue(id, "crank", .72)],
    };
  })();
  G.a.forEach((a) => fx("program", a, "pop", .35));
  fx("program", G.crank, "whoosh", .25);
  G.p.forEach((a) => fx("program", a, "tick", .35));

  function program(t) {
    show("g-kicker", t, G.kicker, { from: { o: 0, x: -30 } });
    words("g-h", t, G.h, { st: .05 });
    show("g-pid", t, G.pid, { from: { o: 0, y: 14 } });
    G.a.forEach((a, i) => {
      const g = P(t, G.ghost + i * .1, .6), f = P(t, a, .5);
      apply("g-a" + i, { o: g * lerp(.3, 1, f), y: (1 - g) * 30, s: lerp(.97, 1, f) });
      apply("g-ab" + i, { o: f, sx: f });
    });
    show("g-crank", t, G.crank, { from: { o: 0, y: 30 } });
    apply("g-crankic", { r: Math.max(0, t - G.crank) * 140 });
    G.p.forEach((a, i) => {
      show("g-pl" + i, t, a, { from: { o: 0, y: 30 } });
      apply("g-cn" + i, { sy: P(t, a + .15, .4), o: P(t, a + .15, .2) });
    });
  }

  // ---------------------------------------------------------------- 10 outro
  const O = (() => {
    const id = "outro", w = cue(id, "words"), wd = SC.outro.cues.words.d;
    return {
      kicker: .1, h: .25, items: .7, gridOut: w - .5,
      w: [w - .02, w + wd * .28, w + wd * .53, w + wd * .76], wOut: cue(id, "end") - .4,
      logo: cue(id, "end") - .3, word: cue(id, "end") + .05, tag: cue(id, "end", .5), url: cue(id, "end") + 1.6, foot: cue(id, "end") + 2.0,
    };
  })();
  SHIPPED.forEach((_, i) => fx("outro", O.items + i * .42, "pop", .25));
  O.w.forEach((a) => fx("outro", a, "thud", .7));
  fx("outro", O.logo, "swell", .6, 2.5);
  fx("outro", O.logo + .8, "pop", .6);
  const outroArch = $("o-logo").querySelector(".arch"), outroDot = $("o-logo").querySelector(".dot");

  function outro(t) {
    show("o-kicker", t, O.kicker, { from: { o: 0, x: -30 }, out: O.gridOut, od: .4 });
    words("o-h", t, O.h, { st: .05, out: O.gridOut, od: .4 });
    SHIPPED.forEach((_, i) => show("o-it" + i, t, O.items + i * .42, { from: { o: 0, y: 30 }, out: O.gridOut, od: .4 }));
    O.w.forEach((a, i) => show("o-w" + i, t, a, {
      from: { o: 0, s: 1.35, blur: 8 }, d: .28, ease: "outExpo",
      out: i < 3 ? O.w[i + 1] - .02 : O.wOut, od: i < 3 ? .12 : .35, to: { o: 0, s: .92 },
    }));
    show("o-logo", t, O.logo, { from: { o: 0, s: .9 }, d: .6 });
    dash(outroArch, P(t, O.logo, 1.0, "inOut"));
    apply(outroDot, { s: Math.max(0, P(t, O.logo + .8, .6, "outBack")), o: t >= O.logo + .8 ? 1 : 0 });
    words("o-word", t, O.word, { st: .04, d: .9 });
    words("o-tag", t, O.tag, { st: .08 });
    show("o-url", t, O.url, { from: { o: 0, y: 20 } });
    show("o-foot", t, O.foot, { from: { o: 0 } });
  }

  // ---------------------------------------------------------------- frame
  const SCENES = { intro, problem, ritual, decay, pool, fair, skr, tech, program, outro };
  TL.scenes.slice(1).forEach((s) => fx(s.id, -.3, "whoosh", .3));

  function background(T) {
    const g = (id, x, y) => { el(id).style.transform = `translate(${(x - 750).toFixed(1)}px,${(y - 750).toFixed(1)}px)`; };
    g("glow1", 420 + 260 * Math.sin(T * .11), 260 + 160 * Math.cos(T * .09));
    g("glow2", 1560 + 220 * Math.sin(T * .08 + 1.3), 860 + 160 * Math.cos(T * .12 + .4));
    g("glow3", 960 + 420 * Math.sin(T * .05 + 2.1), 560 + 240 * Math.sin(T * .07));
    el("bg").style.opacity = P(T, 0, 1.4).toFixed(3);
    el("fade").style.opacity = Math.max(1 - P(T, 0, .4), P(T, TL.duration - 1.0, 1.0, "inOut")).toFixed(3);
  }

  function chrome(T) {
    const o = P(T, SC.problem.start + .3, .6) * (1 - P(T, SC.outro.start + O.gridOut, .5));
    apply("chrome-logo", { o });
    apply("chrome-tag", { o });
    apply("progress", { o: o * .9 });
    el("progress-fill").style.transform = `scaleX(${(T / TL.duration).toFixed(4)})`;
  }

  window.render = function (T) {
    for (const s of TL.scenes) {
      const sc = el("sc-" + s.id), t = T - s.start;
      const vis = t >= 0 && t < s.dur;
      if (vis !== sc._vis) { sc.style.display = vis ? "block" : "none"; sc._vis = vis; }
      if (!vis) continue;
      SCENES[s.id](t);
      const q = s.id === "outro" ? 0 : P(t, s.dur - .55, .55, "in");
      sc.style.opacity = (1 - q).toFixed(3);
      sc.style.transform = q > 0 ? `translateY(${(-18 * q).toFixed(2)}px) scale(${(1 + .012 * q).toFixed(4)})` : "";
      sc.style.filter = q > .02 ? `blur(${(6 * q).toFixed(2)}px)` : "none";
    }
    background(T);
    chrome(T);
  };
  window.DURATION = TL.duration;
  SFX.sort((a, b) => a.t - b.t);
  window.render(0);
})();
