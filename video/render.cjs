// Render the composition frame by frame with headless Chromium.
//   node render.cjs stills <out-dir> <t1,t2,...>   render selected times (seconds) to PNG
//   node render.cjs frames <out-dir> [workers]     render every frame at 30 fps to JPEG
//   node render.cjs sfx <out.json>                 export the sound-effect cue list
const path = require("path");
const fs = require("fs");
const { execSync } = require("child_process");

function loadPlaywright() {
  try { return require("playwright"); } catch {}
  const root = execSync("npm root -g").toString().trim();
  return require(path.join(root, "playwright"));
}
const { chromium } = loadPlaywright();
const FPS = 30;
const PAGE = "file://" + path.join(__dirname, "composition", "index.html");

async function open(browser) {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
  await page.goto(PAGE);
  await page.evaluate(() => document.fonts.ready);
  if (errors.length) throw new Error(errors.join("\n"));
  return page;
}

async function main() {
  const [mode, out, arg] = process.argv.slice(2);
  const browser = await chromium.launch({ args: ["--disable-gpu", "--font-render-hinting=none", "--force-color-profile=srgb"] });
  try {
    if (mode === "sfx") {
      const page = await open(browser);
      const data = await page.evaluate(() => ({ duration: window.DURATION, sfx: window.SFX }));
      fs.writeFileSync(out, JSON.stringify(data, null, 1));
      return;
    }
    fs.mkdirSync(out, { recursive: true });
    if (mode === "stills") {
      const page = await open(browser);
      for (const t of arg.split(",").map(Number)) {
        await page.evaluate((x) => window.render(x), t);
        await page.screenshot({ path: path.join(out, `t${t.toFixed(2).padStart(7, "0")}.png`) });
      }
      return;
    }
    if (mode === "frames") {
      const workers = Number(arg || 3);
      const page0 = await open(browser);
      const total = Math.min(Number(process.env.FRAME_TO || Infinity), Math.ceil((await page0.evaluate(() => window.DURATION)) * FPS));
      const first = Number(process.env.FRAME_FROM || 0);
      await page0.close();
      let done = first;
      const started = Date.now();
      await Promise.all(Array.from({ length: workers }, async (_, k) => {
        const page = await open(browser);
        for (let i = first + k; i < total; i += workers) {
          const file = path.join(out, `f${String(i).padStart(5, "0")}.jpg`);
          if (fs.existsSync(file)) { done++; continue; }
          await page.evaluate((x) => window.render(x), i / FPS);
          await page.screenshot({ path: file + ".tmp", type: "jpeg", quality: 94 });
          fs.renameSync(file + ".tmp", file);
          if (++done % 150 === 0) {
            const rate = done / ((Date.now() - started) / 1000);
            console.log(`${done}/${total} frames · ${rate.toFixed(1)} fps · eta ${((total - done) / rate / 60).toFixed(1)} min`);
          }
        }
      }));
      console.log(`rendered ${total} frames`);
      return;
    }
    throw new Error("usage: render.cjs stills|frames|sfx ...");
  } finally {
    await browser.close();
  }
}

main().catch((e) => { console.error(e); process.exit(1); });
