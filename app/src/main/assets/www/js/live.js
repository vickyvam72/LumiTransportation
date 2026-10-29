/*
 * Lumi Transportation — posisi bus langsung (Transjakarta, Mikrotrans, Royaltrans).
 *
 * Sumber: feed MQTT publik armada Transjakarta, anonim lewat WebSocket:
 *   wss://mqtt.tj.co.id:8084/mqtt   topik /mobile_armada/{koridor}/{kode_rute}/{trip}/{nomor_bus}
 * Isi pesan (JSON): latitude/longitude (+ snap_*), bearing, route_code, trip_headsign, bus_body_no,
 * passengers_status, stops[{name, eta, ...}], timestamp, dll.
 *
 * Klien MQTT 3.1.1 minimal (CONNECT, SUBSCRIBE/UNSUBSCRIBE QoS 0, PUBLISH masuk, PINGREQ).
 * Di Android koneksinya native (LiveSocket.java) dan pesan rute yang tidak diminta dibuang di sana.
 *
 * Langganan: mula-mula per rute (hemat kuota). Bila server memutus koneksi setelah langganan per rute,
 * Lumi beralih ke topik umum "/mobile_armada/#" (seperti klien publik lain) dan menyaring rutenya sendiri.
 */
(function (root) {
  "use strict";
  var URL = "wss://mqtt.tj.co.id:8084/mqtt";
  var ALL = "/mobile_armada/#";
  var enc = new TextEncoder(), dec = new TextDecoder();

  function str(s) { var b = enc.encode(s); return [b.length >> 8, b.length & 255].concat(Array.prototype.slice.call(b)); }
  function remLen(n) { var out = []; do { var d = n % 128; n = Math.floor(n / 128); if (n > 0) d |= 128; out.push(d); } while (n > 0); return out; }
  function packet(type, body) { return new Uint8Array([type].concat(remLen(body.length), body)); }
  function b64(u8) { var s = ""; for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]); return btoa(s); }
  function unb64(t) { var s = atob(t), u = new Uint8Array(s.length); for (var i = 0; i < s.length; i++) u[i] = s.charCodeAt(i); return u; }
  function norm(c) { return String(c || "").toUpperCase().replace(/[.\s-]/g, ""); }
  var NATIVE = function () { return root.LumiNative && root.LumiNative.liveOpen ? root.LumiNative : null; };

  var ws = null, connected = false, buf = new Uint8Array(0), pid = 1, pingT = null, retryT = null, retryMs = 2000;
  var watchers = [];        // { want: {KODE:1}, codes: [...], fn }
  var active = {};          // topik yang sedang dilanggan di koneksi ini
  var statusFns = [];
  var state = "idle", lastMsgAt = 0, lastError = "", phase = "", bytesWeb = 0, gotData = false;
  var allMode = false;
  try { allMode = localStorage.getItem("lumi:liveAll") === "1"; } catch (e) { }

  function setState(s) { state = s; statusFns.forEach(function (f) { try { f(s); } catch (e) { } }); }
  function send(u8) {
    if (ws === "native") { var n = NATIVE(); if (n) n.liveSend(b64(u8)); return; }
    if (ws && ws.readyState === 1) ws.send(u8);
  }
  function subscribeTopic(t) { var id = (pid++ & 0xffff) || 1; send(packet(0x82, [id >> 8, id & 255].concat(str(t), [0]))); }
  function unsubscribeTopic(t) { var id = (pid++ & 0xffff) || 1; send(packet(0xA2, [id >> 8, id & 255].concat(str(t)))); }

  /* Topik untuk satu kode rute; Mikrotrans bisa ditulis "JAK36" atau "JAK.36" di feed. */
  function topicsFor(code) {
    var c = String(code), m = c.match(/^JAK\.?(\d+\w*)$/i);
    return m ? ["/mobile_armada/+/JAK" + m[1] + "/#", "/mobile_armada/+/JAK." + m[1] + "/#"] : ["/mobile_armada/+/" + c + "/#"];
  }
  function wantedTopics() {
    var t = {};
    if (!watchers.length) return t;
    if (allMode) { t[ALL] = 1; return t; }
    watchers.forEach(function (w) { w.codes.forEach(function (c) { topicsFor(c).forEach(function (x) { t[x] = 1; }); }); });
    return t;
  }
  function wantedCodes() { var o = {}; watchers.forEach(function (w) { Object.keys(w.want).forEach(function (k) { o[k] = 1; }); }); return Object.keys(o); }
  /* Samakan langganan dengan kebutuhan pemantau + perbarui saringan native. */
  function syncSubs() {
    var n = NATIVE();
    if (n && n.liveFilter) n.liveFilter(JSON.stringify(wantedCodes()));
    if (!connected) return;
    var want = wantedTopics();
    Object.keys(active).forEach(function (t) { if (!want[t]) { unsubscribeTopic(t); delete active[t]; } });
    Object.keys(want).forEach(function (t) { if (!active[t]) { subscribeTopic(t); active[t] = 1; } });
    if (Object.keys(want).length) phase = "sub";
  }

  function onBytes(u8) {
    var nb = new Uint8Array(buf.length + u8.length); nb.set(buf); nb.set(u8, buf.length); buf = nb;
    for (;;) {
      if (buf.length < 2) return;
      var mult = 1, len = 0, i = 1, d;
      do { if (i >= buf.length) return; d = buf[i++]; len += (d & 127) * mult; mult *= 128; } while (d & 128);
      if (buf.length < i + len) return;
      var type = buf[0] >> 4, flags = buf[0] & 15, body = buf.subarray(i, i + len);
      buf = buf.slice(i + len);
      if (type === 2) {                        // CONNACK
        if (body[1] === 0) { connected = true; phase = "connack"; retryMs = 2000; active = {}; setState("connected"); syncSubs(); }
        else { refused = body[1]; if (body[1] === 2 || body[1] === 4 || body[1] === 5) denied = true; lastError = "login ditolak server: " + (CONNACK_TXT[body[1]] || "kode " + body[1]); setState("error"); }
      } else if (type === 9) {                 // SUBACK
        for (var k = 2; k < body.length; k++) if (body[k] === 0x80) lastError = "langganan topik ditolak server";
      } else if (type === 3) {                 // PUBLISH
        var tl = (body[0] << 8) | body[1], topic = dec.decode(body.subarray(2, 2 + tl)), p = 2 + tl;
        var qos = (flags >> 1) & 3;
        if (qos > 0) { var pi = [body[p], body[p + 1]]; p += 2; send(new Uint8Array([0x40, 2, pi[0], pi[1]])); }
        var msg;
        try { msg = JSON.parse(dec.decode(body.subarray(p))); } catch (e) { continue; }
        lastMsgAt = Date.now(); gotData = true; phase = "data";
        var rc = norm(msg.route_code || topic.split("/")[3]);
        watchers.forEach(function (w) { if (w.want[rc]) { try { w.fn(msg, topic); } catch (e) { } } });
      }
    }
  }

  function onOpen() {
    phase = "ws";
    var cid = "lumi_" + (NATIVE() ? "android_" : "web_") + Math.random().toString(16).slice(2, 10);
    var vh = str("MQTT").concat([4, 0x02, 0, 60]);
    send(packet(0x10, vh.concat(str(cid))));
    clearInterval(pingT);
    pingT = setInterval(function () { send(new Uint8Array([0xC0, 0])); }, 45000);
  }
  var CONNACK_TXT = { 1: "versi MQTT tidak didukung (1)", 2: "ID klien ditolak (2)", 3: "layanan sedang tidak tersedia (3)", 4: "nama/kata sandi salah (4)", 5: "tidak diizinkan (5)" };
  var refused = 0, denied = false;   // denied: server hanya melayani aplikasi resminya → jangan terus mencoba
  var PHASE_TXT = { "": "sebelum tersambung", ws: "sebelum login MQTT", connack: "setelah login", sub: "setelah berlangganan rute", data: "saat menerima data" };
  function onClosed(reason) {
    var ph = phase;
    ws = null; connected = false; buf = new Uint8Array(0); clearInterval(pingT); active = {};
    lastError = refused ? lastError + " · " + (reason || "koneksi terputus") : (reason || "koneksi terputus") + " · " + (PHASE_TXT[ph] || ph);
    refused = 0;
    // Server memutus setelah langganan per rute (belum ada data) → pakai topik umum seperti klien publik lain.
    if (ph === "sub" && !gotData && !allMode) {
      allMode = true;
      try { localStorage.setItem("lumi:liveAll", "1"); } catch (e) { }
      retryMs = 500;
    }
    if (denied) { clearTimeout(retryT); retryMs = 15 * 60000; if (watchers.length) { setState("denied"); retryT = setTimeout(function () { denied = false; connect(); }, retryMs); } else setState("idle"); return; }
    if (watchers.length) { setState("reconnecting"); scheduleRetry(); } else setState("idle");
  }
  function connect() {
    if (ws || !watchers.length) return;
    if (denied) { setState("denied"); return; }
    setState("connecting"); phase = "";
    var n = NATIVE();
    if (n) { ws = "native"; if (n.liveFilter) n.liveFilter(JSON.stringify(wantedCodes())); n.liveOpen(Live.url); return; }
    try { ws = new WebSocket(Live.url, ["mqtt"]); } catch (e) { ws = null; lastError = String(e.message || e); scheduleRetry(); return; }
    ws.binaryType = "arraybuffer";
    ws.onopen = onOpen;
    ws.onmessage = function (ev) { bytesWeb += ev.data.byteLength || 0; onBytes(new Uint8Array(ev.data)); };
    ws.onerror = function () { };
    ws.onclose = function (ev) { onClosed("WebSocket ditutup (kode " + ev.code + ")"); };
  }
  function scheduleRetry() { clearTimeout(retryT); retryT = setTimeout(connect, retryMs); retryMs = Math.min(30000, retryMs * 1.7); }
  function disconnect() {
    clearTimeout(retryT);
    if (ws) {
      try { send(new Uint8Array([0xE0, 0])); } catch (e) { }
      if (ws === "native") { var n = NATIVE(); if (n) n.liveClose(); } else { try { ws.close(); } catch (e) { } }
    }
    ws = null; connected = false; active = {}; clearInterval(pingT); setState("idle");
  }

  var Live = {
    url: URL,
    norm: norm,
    state: function () { return state; },
    lastError: function () { return lastError; },
    allMode: function () { return allMode; },
    denied: function () { return denied; },
    bytes: function () { var n = NATIVE(); try { return n && n.liveBytes ? +n.liveBytes() : bytesWeb; } catch (e) { return bytesWeb; } },
    // dipanggil dari MainActivity (koneksi native)
    __nativeOpen: function () { if (ws === "native") onOpen(); },
    __nativeData: function (arr) { if (ws !== "native") return; for (var i = 0; i < arr.length; i++) onBytes(unb64(arr[i])); },
    __nativeClose: function (reason) { if (ws === "native") onClosed(reason || "koneksi terputus"); },
    lastMsgAt: function () { return lastMsgAt; },
    onStatus: function (f) { statusFns.push(f); return function () { statusFns = statusFns.filter(function (x) { return x !== f; }); }; },
    /* Pantau beberapa kode rute; fn(msg, topic) dipanggil untuk tiap pesan rute itu. Mengembalikan fungsi berhenti. */
    watch: function (codes, fn) {
      var w = { want: {}, codes: codes.slice(), fn: fn };
      codes.forEach(function (c) { w.want[norm(c)] = 1; });
      watchers.push(w);
      syncSubs();
      connect();
      var stopped = false;
      return function stop() {
        if (stopped) return; stopped = true;
        watchers = watchers.filter(function (x) { return x !== w; });
        if (!watchers.length) disconnect(); else syncSubs();
      };
    }
  };
  root.LumiLive = Live;
})(typeof window !== "undefined" ? window : this);
