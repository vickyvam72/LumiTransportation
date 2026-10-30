// Uji tampilan peta OpenFreeMap (MapLibre di dalam Leaflet) dari halaman file:// seperti di WebView.
const { chromium } = require("playwright");
const path = require("path");
(async () => {
  const b = await chromium.launch({ args: ["--use-gl=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist", "--allow-file-access-from-files"] });
  const pg = await b.newPage({ viewport: { width: 412, height: 880 }, deviceScaleFactor: 2 });
  const log = [];
  pg.on("console", m => { if (m.type() === "error" || m.type() === "warning") log.push("console " + m.type() + ": " + m.text().slice(0, 200)); });
  pg.on("pageerror", e => log.push("pageerror: " + e.message));
  pg.on("requestfailed", r => log.push("requestfailed: " + r.url().slice(0, 120) + " " + (r.failure() || {}).errorText));
  let ok = 0, n = 0;
  pg.on("response", r => { if (/openfreemap/.test(r.url())) { n++; if (r.status() === 200) ok++; } });
  await pg.addInitScript(() => localStorage.setItem("lumi:onboarded", "2"));
  await pg.goto("file://" + path.resolve("app/src/main/assets/www/index.html"));
  await pg.waitForTimeout(1500);
  log.push("GL available: " + await pg.evaluate("!!window.maplibregl"));
  await pg.click('[data-tab="lines"]'); await pg.waitForTimeout(400);
  await pg.fill("#inLine", "7B"); await pg.waitForTimeout(500);
  await pg.click("#lineList .l-item"); await pg.waitForTimeout(9000);
  await pg.screenshot({ path: "check/line_7b.png" });
  // zoom dekat ke Ps. Hek 1
  await pg.evaluate(() => { const m = document.querySelector("#lineMap"); });
  await pg.evaluate(() => __lumiDebug && 0);
  for (const st of ["positron", "osm"]) {
    await pg.evaluate(s => { const c = JSON.parse(localStorage.getItem("lumi:cfg") || "{}"); c.basemap = s; localStorage.setItem("lumi:cfg", JSON.stringify(c)); }, st);
    await pg.reload(); await pg.waitForTimeout(1200);
    await pg.click('[data-tab="lines"]'); await pg.waitForTimeout(300);
    await pg.fill("#inLine", "7B"); await pg.waitForTimeout(400);
    await pg.click("#lineList .l-item"); await pg.waitForTimeout(7000);
    await pg.screenshot({ path: "check/line_7b_" + st + ".png" });
  }
  log.push("openfreemap responses: " + ok + "/" + n);
  console.log(log.join("\n"));
  await b.close();
})();
