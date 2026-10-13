/**
 * Upstream egress shim for Our Free Model.
 *
 * Two independent defects on this network, both of which kill a turn outright:
 *
 * 1. **IPv6 blackhole.** `opencode.ai` publishes AAAA records, the v6 route is
 *    dead, and Node's happy-eyeballs spends the full connect timeout on it.
 *    Pinning the lookup family to 4 removes the dead half.
 *
 * 2. **Partially-blackholed A records.** Of the four IPv4 addresses the name
 *    resolves to, only one completes a TLS handshake here; the other three
 *    accept TCP and then never answer the ClientHello. `fetch()` does not retry
 *    a different address once a socket is chosen, so a turn fails or succeeds
 *    purely on which address the resolver happened to hand back. This is why
 *    the same request alternates between working and timing out.
 *
 * The fix for (2) is a probe-and-pin: resolve the hostname, open a TLS socket to
 * each candidate with a short budget, keep the addresses that complete the
 * handshake, and serve only those to the connector for a while. Results are
 * cached per host so the cost is one round of parallel probes per TTL, not per
 * request.
 *
 * Load with `node --import ./ipv4-first.mjs` (or `NODE_OPTIONS=--import=...`).
 * Runtime: Node 22+.
 */
import dns from 'node:dns'
import net from 'node:net'
import tls from 'node:tls'

const LOOPBACK = new Set(['localhost', '127.0.0.1', '::1', '0.0.0.0'])
// A blackholed address does not refuse the connection — it accepts TCP and then
// never answers the ClientHello, so a probe can only ever end in a timeout. The
// budget therefore has to be short enough to be worth paying on every cold
// host: a healthy peer here answers in well under a second, and anything still
// silent at this mark is not going to become useful.
const PROBE_TIMEOUT_MS = Number(process.env.OFM_PROBE_TIMEOUT_MS ?? 2500)
const PROBE_TTL_MS = Number(process.env.OFM_PROBE_TTL_MS ?? 300_000)
const PROBE_PORTS = [443]
const originalLookup = dns.lookup
const originalPromisesLookup = dns.promises.lookup

dns.setDefaultResultOrder('ipv4first')

/** host -> { at, addresses: string[] } */
const healthyCache = new Map()
/** host -> Promise<string[]> (in-flight probe de-duplication) */
const inFlight = new Map()

/**
 * Complete a TLS handshake against one address, on behalf of `servername`.
 *
 * SNI is mandatory here: these upstreams sit behind a shared edge that stalls
 * the handshake outright when the ClientHello carries no server name, which
 * would make every address look blackholed. `rejectUnauthorized` is off because
 * this probe is a reachability check, not a trust decision — the real request
 * that follows does its own verification against the same certificate.
 */
function probeTls(address, port, servername) {
  return new Promise(resolve => {
    let settled = false
    const done = ok => {
      if (settled) return
      settled = true
      try { socket.destroy() } catch { /* already gone */ }
      resolve(ok)
    }
    const socket = tls.connect(
      { host: address, port, servername, rejectUnauthorized: false, timeout: PROBE_TIMEOUT_MS },
      () => done(true),
    )
    socket.setTimeout(PROBE_TIMEOUT_MS, () => done(false))
    socket.on('error', () => done(false))
    socket.on('close', () => done(false))
  })
}

/**
 * Resolve `hostname` and return only the addresses that complete a TLS
 * handshake. Falls back to the unfiltered list when nothing passes, so a
 * genuinely-down upstream still produces a normal connection error instead of
 * an empty-address one.
 */
async function healthyAddresses(hostname) {
  const cached = healthyCache.get(hostname)
  if (cached && Date.now() - cached.at < PROBE_TTL_MS) return cached.addresses

  const pending = inFlight.get(hostname)
  if (pending) return pending

  const run = (async () => {
    let candidates
    try {
      const records = await originalPromisesLookup.call(dns.promises, hostname, { all: true, family: 4 })
      candidates = [...new Set(records.map(r => r.address))]
    } catch {
      return []
    }
    if (candidates.length <= 1) {
      healthyCache.set(hostname, { at: Date.now(), addresses: candidates })
      return candidates
    }

    const verdicts = await Promise.all(
      candidates.map(async address => {
        for (const port of PROBE_PORTS) {
          if (await probeTls(address, port, hostname)) return { address, ok: true }
        }
        return { address, ok: false }
      }),
    )
    const healthy = verdicts.filter(v => v.ok).map(v => v.address)
    const chosen = healthy.length > 0 ? healthy : candidates
    if (healthy.length > 0 && healthy.length < candidates.length) {
      process.emitWarning?.(
        `our-free-model egress: ${hostname} — ${candidates.length - healthy.length} of ` +
        `${candidates.length} address(es) failed the TLS probe and were dropped ` +
        `(kept: ${healthy.join(', ')})`,
      )
    }
    healthyCache.set(hostname, { at: Date.now(), addresses: chosen })
    return chosen
  })()

  inFlight.set(hostname, run)
  try {
    return await run
  } finally {
    inFlight.delete(hostname)
  }
}

function shouldIntercept(hostname, opts) {
  if (net.isIP(hostname) !== 0) return false
  if (LOOPBACK.has(String(hostname).toLowerCase())) return false
  if (opts.family === 6) return false
  return true
}

dns.lookup = function lookupWithHealthyAddresses(hostname, options, callback) {
  const cb = typeof options === 'function' ? options : callback
  const opts = typeof options === 'function' ? {} : { ...(options ?? {}) }

  if (!shouldIntercept(hostname, opts)) {
    return originalLookup.call(dns, hostname, options, callback)
  }

  const wantsAll = opts.all === true
  healthyAddresses(hostname).then(addresses => {
    if (addresses.length === 0) {
      return originalLookup.call(dns, hostname, { ...opts, family: 4 }, cb)
    }
    if (wantsAll) {
      cb(null, addresses.map(address => ({ address, family: 4 })))
    } else {
      cb(null, addresses[0], 4)
    }
  }).catch(() => {
    originalLookup.call(dns, hostname, { ...opts, family: 4 }, cb)
  })
}

dns.promises.lookup = async function lookupPromiseWithHealthyAddresses(hostname, options) {
  const opts = typeof options === 'number' ? { family: options } : { ...(options ?? {}) }
  if (!shouldIntercept(hostname, opts)) {
    return originalPromisesLookup.call(dns.promises, hostname, options)
  }
  const addresses = await healthyAddresses(hostname)
  if (addresses.length === 0) {
    return originalPromisesLookup.call(dns.promises, hostname, { ...opts, family: 4 })
  }
  if (opts.all === true) return addresses.map(address => ({ address, family: 4 }))
  return { address: addresses[0], family: 4 }
}

export { healthyAddresses }
