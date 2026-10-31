package top.tianyan.app.runtime.service

/** Fixed host program, never supplied by a package. Configuration is read once from a pipe. */
internal object ProotWebAssetProgram {
    val source = """
        import base64, http.server, json, re, sys, threading, zlib
        try:
            line = sys.stdin.buffer.readline(4097)
            if len(line) > 4096 or not line.endswith(b'\n'):
                raise ValueError()
            instance, nonce, payload = line[:-1].decode('ascii').split('\t')
            uuid = r'[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'
            if not re.fullmatch(uuid, instance) or not re.fullmatch(uuid + uuid, nonce):
                raise ValueError()
            decoder = zlib.decompressobj()
            raw = decoder.decompress(base64.b64decode(payload, validate=True), 3 * 1024 * 1024)
            if decoder.unconsumed_tail or not decoder.eof:
                raise ValueError()
            cfg = {'instance': instance, 'prefix': '/' + instance + '/', 'authorization': 'Bearer ' + nonce}
            assets = {name: base64.b64decode(value, validate=True) for name, value in json.loads(raw).items()}
            if sum(map(len, assets.values())) > 2 * 1024 * 1024:
                raise ValueError()
            class Handler(http.server.BaseHTTPRequestHandler):
                protocol_version = 'HTTP/1.1'
                def setup(self):
                    super().setup()
                    self.connection.settimeout(2)
                def do_GET(self):
                    path = self.path
                    data = assets.get(path[len(cfg['prefix']):]) if path.startswith(cfg['prefix']) and self.headers.get('Authorization') == cfg['authorization'] else None
                    self.send_response(200 if data is not None else 404)
                    self.send_header('Content-Type', 'application/octet-stream')
                    self.send_header('Content-Length', str(len(data) if data is not None else 0))
                    self.send_header('Cache-Control', 'no-store')
                    self.send_header('Connection', 'close')
                    self.end_headers()
                    if data is not None:
                        self.wfile.write(data)
                    self.close_connection = True
                def log_message(self, *args):
                    pass
            class Server(http.server.ThreadingHTTPServer):
                slots = threading.BoundedSemaphore(16)
                def process_request(self, request, address):
                    if not self.slots.acquire(False):
                        request.close()
                        return
                    try:
                        super().process_request(request, address)
                    except Exception:
                        self.slots.release()
                        raise
                def process_request_thread(self, request, address):
                    try:
                        super().process_request_thread(request, address)
                    finally:
                        self.slots.release()
                def handle_error(self, request, address):
                    pass
            server = Server(('127.0.0.1', 0), Handler)
            server.daemon_threads = True
            print('TIANYAN_WEB_READY_' + cfg['instance'] + ':' + str(server.server_port) + ':END', flush=True)
            server.serve_forever()
        except Exception:
            print('TIANYAN_WEB_ERROR', file=sys.stderr, flush=True)
            sys.exit(1)
    """.trimIndent()

    val command: String get() = "exec /usr/bin/env -i HOME=/root PATH=/usr/bin:/bin LANG=C.UTF-8 /usr/bin/python3 -I -S -u -c '" +
        source.replace("'", "'\"'\"'") + "'"
}
