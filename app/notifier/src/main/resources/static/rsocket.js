// A small RSocket 1.0 client over WebSocket, for one request-stream with routing (add-notifier design N6).
// One RSocket frame per WebSocket message, so no length prefix. Frames used: SETUP, REQUEST_STREAM, REQUEST_N,
// PAYLOAD, KEEPALIVE, ERROR, CANCEL. Spec: https://rsocket.io/about/protocol
"use strict";

const T = { SETUP: 0x01, KEEPALIVE: 0x03, REQUEST_STREAM: 0x06, REQUEST_N: 0x08, CANCEL: 0x09, PAYLOAD: 0x0A, ERROR: 0x0B };
const F = { METADATA: 0x100, RESPOND: 0x80, COMPLETE: 0x40, NEXT: 0x20 };
const ROUTING = 0x7E;                       // well-known mime id: message/x.rsocket.routing.v0
const COMPOSITE = "message/x.rsocket.composite-metadata.v0";
const utf8 = new TextEncoder();
const fromUtf8 = new TextDecoder();

/** Frame header: stream id (31 bits) + type (6 bits) and flags (10 bits). */
function header(view, streamId, type, flags) {
  view.setUint32(0, streamId & 0x7fffffff);
  view.setUint16(4, (type << 10) | (flags & 0x3ff));
}

function uint24(view, at, n) {
  view.setUint8(at, (n >> 16) & 0xff);
  view.setUint16(at + 1, n & 0xffff);
}

function frame(size) {
  const buf = new ArrayBuffer(size);
  return { buf, view: new DataView(buf), bytes: new Uint8Array(buf) };
}

/** Composite metadata with one routing entry: [0x80 | 0x7E][len 24][len 8 + route]. */
function routeMetadata(route) {
  const r = utf8.encode(route);
  const out = new Uint8Array(1 + 3 + 1 + r.length);
  const v = new DataView(out.buffer);
  out[0] = 0x80 | ROUTING;
  uint24(v, 1, 1 + r.length);
  out[4] = r.length;
  out.set(r, 5);
  return out;
}

function setupFrame(keepaliveMs, lifetimeMs, dataMime) {
  const meta = utf8.encode(COMPOSITE);
  const data = utf8.encode(dataMime);
  const f = frame(6 + 2 + 2 + 4 + 4 + 1 + meta.length + 1 + data.length);
  header(f.view, 0, T.SETUP, 0);
  f.view.setUint16(6, 1);                  // major version
  f.view.setUint16(8, 0);                  // minor version
  f.view.setUint32(10, keepaliveMs);
  f.view.setUint32(14, lifetimeMs);
  let at = 18;
  f.bytes[at++] = meta.length; f.bytes.set(meta, at); at += meta.length;
  f.bytes[at++] = data.length; f.bytes.set(data, at);
  return f.buf;
}

function requestStreamFrame(streamId, n, metadata) {
  const f = frame(6 + 4 + 3 + metadata.length);
  header(f.view, streamId, T.REQUEST_STREAM, F.METADATA);
  f.view.setUint32(6, n);
  uint24(f.view, 10, metadata.length);
  f.bytes.set(metadata, 13);
  return f.buf;
}

function requestNFrame(streamId, n) {
  const f = frame(10);
  header(f.view, streamId, T.REQUEST_N, 0);
  f.view.setUint32(6, n);
  return f.buf;
}

function cancelFrame(streamId) {
  const f = frame(6);
  header(f.view, streamId, T.CANCEL, 0);
  return f.buf;
}

function keepaliveFrame(respond) {
  const f = frame(6 + 8);
  header(f.view, 0, T.KEEPALIVE, respond ? F.RESPOND : 0);
  return f.buf;                             // last received position: 0 (resumption not used)
}

/**
 * Opens one request-stream. Calls onLine(text) for each payload, onState("connected" | "closed", reason).
 * Backpressure: asks for `window` lines, then for half a window more each time half has arrived.
 */
export function openStream(url, route, { onLine, onState, window = 256, keepaliveMs = 20000, lifetimeMs = 90000 }) {
  const ws = new WebSocket(url);
  ws.binaryType = "arraybuffer";
  const streamId = 1;
  let received = 0;
  let keepalive = null;
  let finished = false;

  const finish = (reason) => {
    if (finished) return;
    finished = true;
    clearInterval(keepalive);
    try { ws.close(); } catch (e) { /* already closed */ }
    onState("closed", reason);
  };

  ws.onopen = () => {
    ws.send(setupFrame(keepaliveMs, lifetimeMs, "application/json"));
    ws.send(requestStreamFrame(streamId, window, routeMetadata(route)));
    keepalive = setInterval(() => ws.readyState === 1 && ws.send(keepaliveFrame(true)), keepaliveMs);
    onState("connected");
  };

  ws.onmessage = (msg) => {
    const view = new DataView(msg.data);
    const id = view.getUint32(0) & 0x7fffffff;
    const tf = view.getUint16(4);
    const type = tf >> 10;
    const flags = tf & 0x3ff;
    if (type === T.KEEPALIVE) {
      if (flags & F.RESPOND) ws.send(keepaliveFrame(false));
      return;
    }
    if (type === T.ERROR) {
      const code = view.getUint32(6);
      finish("error 0x" + code.toString(16) + ": " + fromUtf8.decode(new Uint8Array(msg.data, 10)));
      return;
    }
    if (type !== T.PAYLOAD || id !== streamId) return;
    let at = 6;
    if (flags & F.METADATA) {
      at += 3 + ((view.getUint8(6) << 16) | view.getUint16(7));
    }
    if (flags & F.NEXT) {
      onLine(fromUtf8.decode(new Uint8Array(msg.data, at)));
      received++;
      if (received >= window / 2) {         // ask for more: the page decides how fast lines come
        ws.send(requestNFrame(streamId, received));
        received = 0;
      }
    }
    if (flags & F.COMPLETE) finish("completed");
  };

  ws.onclose = () => finish("connection closed");
  ws.onerror = () => finish("connection error");

  return {
    close() {
      if (ws.readyState === 1) ws.send(cancelFrame(streamId));
      finish("closed by page");
    },
  };
}
