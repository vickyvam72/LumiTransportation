/*
 * Lumi Transportation — posisi bus langsung (Transjakarta, Mikrotrans, Royaltrans).
 *
 * Sumber: feed MQTT publik armada Transjakarta (dipakai aplikasi TJ), anonim lewat WebSocket:
 *   wss://mqtt.tj.co.id:8084/mqtt   topik /mobile_armada/{koridor}/{kode_rute}/{trip}/{nomor_bus}
 * Isi pesan (JSON): latitude/longitude (+ snap_*), bearing, route_code, trip_headsign, bus_body_no,
 * passengers_status, stops[{name, eta, stop_sequence, ...}], timestamp, dll.
 *
 * Klien MQTT 3.1.1 minimal (CONNECT, SUBSCRIBE/UNSUBSCRIBE QoS 0, PUBLISH masuk, PINGREQ),
 * hanya berlangganan rute yang sedang dilihat agar hemat kuota.
 */
(function (root) {
  "use strict";
  var URL = "wss://mqtt.tj.co.id:8084/mqtt";
  var enc = new TextEncoder(), dec = new TextDecoder();

  function str(s) { var b = enc.encode(s); return [b.length >> 8, b.length & 255].concat(Array.prototype.slice.call(b)); }
  function remLen(n) { var out = []; do { var d = n % 128; n = Math.floor(n / 128); if (n > 0) d |= 128; out.push(d); } while (n > 0); return out; }
  function packet(type, body) { return new Uint8Array([type].concat(remLen(body.length), body)); }

  var ws = null, connected = false, buf = new Uint8Array(0), pid = 1, pingT = null, retryT = null, retryMs = 2000;
  var subs = {};          // topik -> jumlah pemakai
  var listeners = [];     // fungsi(msg, topic)
  var statusFns = [];
  var state = "idle", lastMsgAt = 0, lastError = "";
  var NATIVE = function () { return root.LumiNative && root.LumiNative.liveOpen ? root.LumiNative : null; };
  function b64(u8) { var s = ""; for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]); return btoa(s); }
  function unb64(t) { var s = atob(t), u = new Uint8Array(s.length); for (var i = 0; i < s.length; i++) u[i] = s.charCodeAt(i); return u; }

  function setState(s) { state = s; statusFns.forEach(function (f) { try { f(s); } catch (e) { } }); }
  function send(u8) {
    if (ws === "native") { var n = NATIVE(); if (n) n.liveSend(b64(u8)); return; }
    if (ws && ws.readyState === 1) ws.send(u8);
  }
  function subscribeTopic(t) { var id = pid++ & 0xffff || 1; send(packet(0x82, [id >> 8, id & 255].concat(str(t), [0]))); }
  function unsubscribeTopic(t) { var id = pid++ & 0xffff || 1; send(packet(0xA2, [id >> 8, id & 255].concat(str(t)))); }

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
        if (body[1] === 0) {
          connected = true; retryMs = 2000; setState("connected");
          Object.keys(subs).forEach(subscribeTopic);
        } else { lastError = "ditolak server (kode " + body[1] + ")"; setState("error"); }
      } else if (type === 3) {                 // PUBLISH
        var tl = (body[0] << 8) | body[1], topic = dec.decode(body.subarray(2, 2 + tl)), p = 2 + tl;
        var qos = (flags >> 1) & 3;
        if (qos > 0) { var pi = [body[p], body[p + 1]]; p += 2; send(new Uint8Array([0x40, 2, pi[0], pi[1]])); }
        var txt = dec.decode(body.subarray(p)), msg;
        try { msg = JSON.parse(txt); } catch (e) { continue; }
        lastMsgAt = Date.now();
        listeners.forEach(function (f) { try { f(msg, topic); } catch (e) { } });
      }
    }
  }

  function onOpen() {
    var cid = "lumi_" + (NATIVE() ? "android_" : "web_") + Math.random().toString(16).slice(2, 10);
    var vh = str("MQTT").concat([4, 0x02, 0, 60]);
    send(packet(0x10, vh.concat(str(cid))));
    clearInterval(pingT);
    pingT = setInterval(function () { send(new Uint8Array([0xC0, 0])); }, 45000);
  }
  function onClosed(reason) {
    ws = null; connected = false; buf = new Uint8Array(0); clearInterval(pingT);
    if (reason) lastError = reason;
    if (Object.keys(subs).length) { setState("reconnecting"); scheduleRetry(); } else setState("idle");
  }
  function connect() {
    if (ws || !Object.keys(subs).length) return;
    setState("connecting");
    var n = NATIVE();
    if (n) { ws = "native"; n.liveOpen(Live.url); return; }   // koneksi native Android (lebih andal)
    try { ws = new WebSocket(Live.url, ["mqtt"]); } catch (e) { ws = null; lastError = String(e.message || e); scheduleRetry(); return; }
    ws.binaryType = "arraybuffer";
    ws.onopen = onOpen;
    ws.onmessage = function (ev) { onBytes(new Uint8Array(ev.data)); };
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
    ws = null; connected = false; clearInterval(pingT); setState("idle");
  }

  function norm(c) { return String(c || "").toUpperCase().replace(/[.\s-]/g, ""); }
  /* Topik untuk satu kode rute; Mikrotrans bisa ditulis "JAK36" atau "JAK.36" di feed. */
  function topicsFor(code) {
    var c = String(code), out = ["/mobile_armada/+/" + c + "/#"];
    var m = c.match(/^JAK\.?(\d+\w*)$/i);
    if (m) { out = ["/mobile_armada/+/JAK" + m[1] + "/#", "/mobile_armada/+/JAK." + m[1] + "/#"]; }
    return out;
  }

  var Live = {
    url: URL,
    norm: norm,
    state: function () { return state; },
    lastError: function () { return lastError; },
    // dipanggil dari MainActivity (koneksi native)
    __nativeOpen: function () { if (ws === "native") onOpen(); },
    __nativeData: function (arr) { if (ws !== "native") return; for (var i = 0; i < arr.length; i++) onBytes(unb64(arr[i])); },
    __nativeClose: function (reason) { if (ws === "native") onClosed(reason || "koneksi terputus"); },
    lastMsgAt: function () { return lastMsgAt; },
    onStatus: function (f) { statusFns.push(f); return function () { statusFns = statusFns.filter(function (x) { return x !== f; }); }; },
    /* Pantau beberapa kode rute; fn(msg, topic) dipanggil untuk tiap pesan rute itu. Mengembalikan fungsi berhenti. */
    watch: function (codes, fn) {
      var want = {}, topics = [];
      codes.forEach(function (c) { want[norm(c)] = 1; topicsFor(c).forEach(function (t) { topics.push(t); }); });
      var lis = function (msg, topic) {
        var rc = norm(msg.route_code || topic.split("/")[3]);
        if (want[rc]) fn(msg, topic);
      };
      listeners.push(lis);
      topics.forEach(function (t) { subs[t] = (subs[t] || 0) + 1; if (subs[t] === 1 && connected) subscribeTopic(t); });
      connect();
      var stopped = false;
      return function stop() {
        if (stopped) return; stopped = true;
        listeners = listeners.filter(function (x) { return x !== lis; });
        topics.forEach(function (t) { subs[t]--; if (subs[t] <= 0) { delete subs[t]; if (connected) unsubscribeTopic(t); } });
        if (!Object.keys(subs).length) disconnect();
      };
    }
  };
  root.LumiLive = Live;
})(typeof window !== "undefined" ? window : this);
