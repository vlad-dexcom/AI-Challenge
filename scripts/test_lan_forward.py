#!/usr/bin/python3
"""Stdlib tests for lan_forward.py: python3 scripts/test_lan_forward.py"""
import os, socket, subprocess, sys, threading, time, unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
closed = threading.Event()


class Up(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path == "/stream":
            self.send_response(200)
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            try:
                for i in range(200):
                    d = b"part%d\n" % i
                    self.wfile.write(b"%x\r\n%s\r\n" % (len(d), d))
                    self.wfile.flush()
                    time.sleep(0.05)
            except (BrokenPipeError, ConnectionResetError):
                closed.set()
            return
        body = b"hello"
        self.send_response(200)
        self.send_header("Content-Length", "5")
        self.end_headers()
        self.wfile.write(body)


def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


class ForwardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        ThreadingHTTPServer.daemon_threads = True
        cls.up = ThreadingHTTPServer(("127.0.0.1", 0), Up)
        threading.Thread(target=cls.up.serve_forever, daemon=True).start()
        cls.port = free_port()
        cls.proc = subprocess.Popen([sys.executable, os.path.join(HERE, "lan_forward.py"), str(cls.port),
                                     str(cls.up.server_address[1]), "--bind", "127.0.0.1"], stdout=subprocess.PIPE)
        cls.proc.stdout.readline()

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(5)
        cls.up.shutdown()

    def conn(self):
        return socket.create_connection(("127.0.0.1", self.port), timeout=5)

    def test_plain_request(self):
        s = self.conn()
        s.sendall(b"GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        data = b""
        while chunk := s.recv(4096):
            data += chunk
        self.assertTrue(data.startswith(b"HTTP/1.1 200") and data.endswith(b"hello"))

    def test_streaming_arrives_in_pieces_and_client_abort_closes_upstream(self):
        closed.clear()
        s = self.conn()
        s.sendall(b"GET /stream HTTP/1.1\r\nHost: x\r\n\r\n")
        t0 = time.time(); got = b""
        while b"part2\n" not in got:
            got += s.recv(4096)
        self.assertLess(time.time() - t0, 2)  # first parts arrive long before the 10 s stream ends
        s.close()
        self.assertTrue(closed.wait(5), "upstream connection must close when the client aborts")

    def test_refused_target_closes_client(self):
        p = free_port(); t = free_port()
        proc = subprocess.Popen([sys.executable, os.path.join(HERE, "lan_forward.py"), str(p), str(t), "--bind", "127.0.0.1"], stdout=subprocess.PIPE)
        proc.stdout.readline()
        s = socket.create_connection(("127.0.0.1", p), timeout=5)
        self.assertEqual(b"", s.recv(10))
        proc.terminate(); self.assertEqual(0, proc.wait(5))


if __name__ == "__main__":
    unittest.main()
