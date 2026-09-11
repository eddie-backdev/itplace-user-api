"""Small local protocol/accounting check; never sends traffic to the service."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json, os, subprocess, tempfile, threading, time

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    peers = set()
    def log_message(self, *args): pass
    def handle(self):
        try: super().handle()
        except ConnectionResetError: pass
    def do_GET(self):
        self.peers.add(self.client_address)
        try:
            if self.path == '/slow-headers': time.sleep(2)
            self.send_response(503 if self.path == '/error' else 200)
            self.send_header('Content-Length', '8')
            self.end_headers()
            self.wfile.flush()
            if self.path == '/slow-body': time.sleep(2)
            self.wfile.write(b'12345678')
        except (BrokenPipeError, ConnectionResetError): pass

server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
try:
    with tempfile.TemporaryDirectory(prefix='map-runner-check-') as directory:
        root = Path(directory)
        corpus = root / 'corpus.tsv'
        corpus.write_text('ok\t/ok\nerror\t/error\nslow-body\t/slow-body\nslow-headers\t/slow-headers\nok\t/ok2\n')
        output = root / 'result.json'
        subprocess.run([java, str(Path(__file__).with_name('MapLatencyRunner.java')), '--base-url',
                        f'http://127.0.0.1:{server.server_port}', '--corpus', str(corpus), '--concurrency', '2',
                        '--duration', '4', '--warmup', '1', '--timeout', '1', '--seed', '7', '--output', str(output)],
                       check=True, timeout=15)
        result = json.loads(output.read_text())
        measurement = result['measurement']
        assert measurement['issued'] == measurement['completed']
        assert sum(group['completed'] for group in measurement['groups'].values()) == measurement['completed']
        assert measurement['distinct_corpus_indices_issued'] == 5
        assert measurement['distinct_request_paths_issued'] == 5
        assert measurement['groups']['error']['outcomes']['HTTP_503'] > 0
        assert measurement['groups']['slow-body']['errors'] > 0
        assert measurement['groups']['slow-headers']['errors'] > 0
        assert measurement['groups']['ok']['errors'] == 0
        assert measurement['groups']['ok']['body_bytes_completed'] == 8 * measurement['groups']['ok']['completed']
        assert len(Handler.peers) < measurement['completed'] + result['warmup']['completed']
        assert measurement['wall_seconds_including_drain'] < 6
        print('Local HTTP accounting/deadline/keep-alive check passed:', measurement['completed'], 'requests')
finally:
    server.shutdown()
    server.server_close()
