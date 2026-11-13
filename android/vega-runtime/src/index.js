// Globals the Vega providers expect (fetch, axios, cheerio, URL, atob, ...) on top of the Kotlin host functions:
// __host_http(method, url, headersJson, body, follow, timeoutMs) -> Promise<json {status, url, headers:[[k,v]], body}>
// -> or {error} on failure; __host_sleep(ms, id) -> Promise, __host_clear(id) ends that sleep early, __host_log(level, text)
import "core-js/actual/url";
import "core-js/actual/url-search-params";
import * as cheerio from "cheerio";

const g = globalThis;

// quickjs-kt (alpha13) treats any promise rejected while it has no handler as fatal: it cancels every pending host
// call, which then rejects with null ("cannot read property 'message' of null"). Browsers just log these, and the
// providers rely on that (their shared urls.json fetch ends in a .finally() nobody awaits). So every promise gets a
// silent handler before it can reject; real consumers still see the rejection.
// Native async functions' promises bypass this, so the build lowers async/await (package.json) onto this Promise.
const NativePromise = Promise, nativeThen = Promise.prototype.then, noop = () => {};
Promise.prototype.then = function (onOk, onErr) {
  const p = nativeThen.call(this, onOk, onErr);
  nativeThen.call(p, undefined, noop);
  return p;
};
g.Promise = class Promise extends NativePromise {
  constructor(executor) {
    let resolve, reject;
    super((a, b) => { resolve = a; reject = b; });
    nativeThen.call(this, undefined, noop);
    try { executor(resolve, reject); } catch (e) { reject(e); }
  }
  static get [Symbol.species]() { return NativePromise; }
  static [Symbol.hasInstance](x) { return x instanceof NativePromise; }
};

const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
g.btoa = (s) => {
  s = String(s);
  let out = "";
  for (let i = 0; i < s.length; i += 3) {
    const a = s.charCodeAt(i), b = s.charCodeAt(i + 1), c = s.charCodeAt(i + 2);
    if (a > 255 || b > 255 || c > 255) throw new Error("btoa: invalid character");
    const n = (a << 16) | ((b || 0) << 8) | (c || 0);
    out += B64[n >> 18] + B64[(n >> 12) & 63] + (i + 1 < s.length ? B64[(n >> 6) & 63] : "=") + (i + 2 < s.length ? B64[n & 63] : "=");
  }
  return out;
};
g.atob = (s) => {
  s = String(s).replace(/[\s=]/g, "").replace(/-/g, "+").replace(/_/g, "/");
  let out = "", bits = 0, acc = 0;
  for (const ch of s) {
    const v = B64.indexOf(ch);
    if (v < 0) throw new Error("atob: invalid character");
    acc = (acc << 6) | v;
    bits += 6;
    if (bits >= 8) { bits -= 8; out += String.fromCharCode((acc >> bits) & 255); }
  }
  return out;
};

g.TextEncoder = class { encode(s = "") { return new Uint8Array(unescape(encodeURIComponent(s)).split("").map((c) => c.charCodeAt(0))); } };
g.TextDecoder = class {
  decode(b) {
    if (!b) return "";
    const u = b instanceof Uint8Array ? b : new Uint8Array(b.buffer || b);
    let s = "";
    for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
    try { return decodeURIComponent(escape(s)); } catch (e) { return s; }
  }
};

let timerId = 0;
const cleared = new Set();
g.setTimeout = (fn, ms, ...args) => {
  const id = ++timerId;
  __host_sleep(Math.max(0, +ms || 0), id).then(() => { if (!cleared.delete(id)) fn(...args); });
  return id;
};
g.clearTimeout = (id) => { if (id) { cleared.add(id); __host_clear(id); } };

const log = (level) => (...a) => __host_log(level, a.map((x) => (typeof x === "string" ? x : x instanceof Error ? String(x) + (x.stack ? "\n" + x.stack : "") : safeJson(x))).join(" "));
function safeJson(x) { try { return JSON.stringify(x); } catch (e) { return String(x); } }
g.console = { log: log("d"), info: log("i"), debug: log("d"), warn: log("w"), error: log("e") };

class AbortSignal {
  constructor() { this.aborted = false; this.reason = undefined; this._l = []; }
  addEventListener(t, f) { this._l.push(f); }
  removeEventListener(t, f) { this._l = this._l.filter((x) => x !== f); }
  throwIfAborted() { if (this.aborted) throw this.reason; }
}
g.AbortSignal = AbortSignal;
g.AbortController = class {
  constructor() { this.signal = new AbortSignal(); }
  abort(reason) {
    const s = this.signal;
    if (s.aborted) return;
    s.aborted = true;
    s.reason = reason || new Error("aborted");
    s._l.forEach((f) => f());
  }
};

class Headers {
  constructor(init) {
    this._h = [];
    if (init instanceof Headers) init = init._h;
    if (Array.isArray(init)) init.forEach(([k, v]) => this.append(k, v));
    else if (init) Object.keys(init).forEach((k) => init[k] != null && this.append(k, init[k]));
  }
  append(k, v) { this._h.push([String(k).toLowerCase(), String(v)]); }
  set(k, v) { this.delete(k); this.append(k, v); }
  delete(k) { k = String(k).toLowerCase(); this._h = this._h.filter((e) => e[0] !== k); }
  get(k) { k = String(k).toLowerCase(); const v = this._h.filter((e) => e[0] === k).map((e) => e[1]); return v.length ? v.join(", ") : null; }
  getSetCookie() { return this._h.filter((e) => e[0] === "set-cookie").map((e) => e[1]); }
  has(k) { return this.get(k) !== null; }
  forEach(f) { this._h.forEach(([k, v]) => f(v, k, this)); }
  entries() { return this._h[Symbol.iterator](); }
  keys() { return this._h.map((e) => e[0])[Symbol.iterator](); }
  values() { return this._h.map((e) => e[1])[Symbol.iterator](); }
  [Symbol.iterator]() { return this.entries(); }
}
g.Headers = Headers;

g.FormData = class {
  constructor() { this._f = []; }
  append(k, v) { this._f.push([String(k), String(v)]); }
  set(k, v) { this._f = this._f.filter((e) => e[0] !== k); this.append(k, v); }
  get(k) { const e = this._f.find((e) => e[0] === k); return e ? e[1] : null; }
};

class Response {
  constructor(body, init = {}) {
    this._body = body == null ? "" : String(body);
    this.status = init.status || 200;
    this.statusText = init.statusText || "";
    this.headers = new Headers(init.headers);
    this.url = init.url || "";
    this.redirected = !!init.redirected;
    this.ok = this.status >= 200 && this.status < 300;
    this.bodyUsed = false;
  }
  async text() { this.bodyUsed = true; return this._body; }
  async json() { return JSON.parse(await this.text()); }
  async arrayBuffer() { return new TextEncoder().encode(await this.text()).buffer; }
  clone() { return new Response(this._body, this); }
}
g.Response = Response;

// Body → [string, content-type or null]
function encodeBody(data) {
  if (data == null) return [null, null];
  if (typeof data === "string") return [data, null];
  if (data instanceof URLSearchParams) return [data.toString(), "application/x-www-form-urlencoded;charset=UTF-8"];
  if (data instanceof g.FormData) {
    const b = "----vega" + Math.random().toString(16).slice(2);
    const body = data._f.map(([k, v]) => `--${b}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`).join("") + `--${b}--\r\n`;
    return [body, "multipart/form-data; boundary=" + b];
  }
  return [JSON.stringify(data), "application/json"];
}

async function request(method, url, headers, data, follow, timeout, signal) {
  if (signal && signal.aborted) throw signal.reason || new Error("aborted");
  const h = new Headers(headers);
  const [body, type] = encodeBody(data);
  if (type && !h.has("content-type")) h.set("content-type", type);
  const obj = {};
  h.forEach((v, k) => { obj[k] = v; });
  const raw = JSON.parse(await __host_http(method.toUpperCase(), String(url), JSON.stringify(obj), body, follow, timeout || 0));
  if (raw.error) throw new Error(raw.error);
  return new Response(raw.body, { status: raw.status, headers: raw.headers, url: raw.url, redirected: raw.url !== String(url) });
}

g.fetch = async (input, init = {}) => {
  const url = typeof input === "string" ? input : input.url || String(input);
  return request(init.method || "GET", url, init.headers, init.body, init.redirect !== "manual" && init.redirect !== "error", 0, init.signal);
};

function axiosError(message, config, response, code) {
  const e = new Error(message);
  e.isAxiosError = true;
  e.config = config;
  e.code = code;
  if (response) { e.response = response; e.status = response.status; }
  return e;
}

async function axiosRequest(config) {
  config = Object.assign({ method: "get", headers: {} }, axios.defaults, config);
  let url = config.baseURL && !/^https?:/i.test(config.url) ? config.baseURL.replace(/\/$/, "") + "/" + config.url.replace(/^\//, "") : config.url;
  if (config.params) {
    const qs = new URLSearchParams();
    Object.keys(config.params).forEach((k) => config.params[k] != null && qs.append(k, config.params[k]));
    const s = qs.toString();
    if (s) url += (url.includes("?") ? "&" : "?") + s;
  }
  let res;
  try {
    res = await request(config.method, url, config.headers, config.data, config.maxRedirects !== 0, config.timeout, config.signal);
  } catch (e) {
    throw axiosError(e.message || String(e), config, null, /reset/i.test(e.message) ? "ECONNRESET" : "ERR_NETWORK");
  }
  const headers = {};
  res.headers.forEach((v, k) => {
    if (k === "set-cookie") (headers[k] = headers[k] || []).push(v);
    else headers[k] = headers[k] ? headers[k] + ", " + v : v;
  });
  let data = res._body;
  if (config.responseType === "json" || (!config.responseType && !config.transformResponse && data)) { // iqst's identity transformResponse wants the raw text
    try { data = JSON.parse(data); } catch (e) { if (config.responseType === "json") data = res._body; }
  }
  const response = { data, status: res.status, statusText: res.statusText, headers, config, request: { responseURL: res.url, res: { responseUrl: res.url } } };
  const validate = config.validateStatus === undefined ? (s) => s >= 200 && s < 300 : config.validateStatus;
  if (validate && !validate(res.status)) {
    throw axiosError("Request failed with status code " + res.status, config, response, res.status >= 500 ? "ERR_BAD_RESPONSE" : "ERR_BAD_REQUEST");
  }
  return response;
}

function axios(urlOrConfig, config) {
  return typeof urlOrConfig === "string" ? axiosRequest(Object.assign({}, config, { url: urlOrConfig })) : axiosRequest(urlOrConfig);
}
axios.defaults = {};
axios.request = axiosRequest;
["get", "head", "delete", "options"].forEach((m) => { axios[m] = (url, config) => axiosRequest(Object.assign({}, config, { url, method: m })); });
["post", "put", "patch"].forEach((m) => { axios[m] = (url, data, config) => axiosRequest(Object.assign({}, config, { url, data, method: m })); });
axios.isAxiosError = (e) => !!(e && e.isAxiosError);

const store = new Map();
const URLS = "https://raw.githubusercontent.com/Zenda-Cross/vega-providers/refs/heads/main/urls.json";
let urls = null;
g.axios = axios;
g.cheerio = cheerio;
g.__vegaContext = (userAgent) => ({
  axios,
  cheerio,
  commonHeaders: { "User-Agent": userAgent },
  // the Vega app's context has it too (dramacool asks it before its own urls.json read); the host shares one urls.json fetch
  getBaseUrl: async (v) => {
    urls = urls || fetch(URLS).then((r) => r.json()).catch((e) => { urls = null; throw e; });
    const u = (await urls)[v];
    return (u && u.url) || "";
  },
  kvStore: {
    get: async (k) => (store.has(k) ? store.get(k) : undefined),
    set: async (k, v) => { store.set(k, v); },
    delete: async (k) => store.delete(k),
    keys: async () => [...store.keys()],
    clear: async () => { store.clear(); },
  },
  openWebView: async () => { throw new Error("unsupported"); },
});
