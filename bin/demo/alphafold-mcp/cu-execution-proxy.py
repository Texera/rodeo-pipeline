#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""
Makes a Kubernetes deployment's synchronous execution endpoint reachable from a
laptop, so an MCP client can run a workflow rather than only build one.

Why this is needed: on Kubernetes each computing unit is its own pod, and
`POST /api/execution/{wid}/{cuid}/run` is served by that pod on port 8085. The
gateway routes the shared services but not the per-unit pods, so an MCP client
outside the cluster has nowhere to send the request. `TEXERA_EXECUTION_URL`
exists for exactly this, but a single fixed URL cannot name a pod whose id is
only known once the unit has been created.

This proxy reads the cuid out of the request path and port-forwards to that
pod on demand, caching one forward per unit. Point the MCP server at it:

    TEXERA_EXECUTION_URL=http://127.0.0.1:8085

Run with the same kubeconfig context the deployment is in:

    python3 cu-execution-proxy.py [--namespace NS] [--port 8085]
"""

import argparse
import http.server
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

RUN_PATH = re.compile(r"^/api/execution/\d+/(\d+)/run/?$")

_forwards: dict = {}
_lock = threading.Lock()


def _free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def _port_forward(namespace: str, cuid: int) -> int:
    """A local port that reaches computing-unit-<cuid>:8085, started once per unit."""
    with _lock:
        existing = _forwards.get(cuid)
        if existing and existing[0].poll() is None:
            return existing[1]

        local = _free_port()
        pod = f"computing-unit-{cuid}"
        process = subprocess.Popen(
            ["kubectl", "port-forward", "-n", namespace, f"pod/{pod}", f"{local}:8085"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )

        # kubectl exits immediately if the pod does not exist, so wait for the
        # port to accept a connection rather than assuming it came up.
        deadline = time.time() + 30
        while time.time() < deadline:
            if process.poll() is not None:
                detail = (process.stderr.read() or b"").decode().strip()
                raise RuntimeError(f"could not port-forward to {pod}: {detail}")
            try:
                with socket.create_connection(("127.0.0.1", local), timeout=0.5):
                    _forwards[cuid] = (process, local)
                    print(f"[proxy] forwarding {pod}:8085 -> 127.0.0.1:{local}", flush=True)
                    return local
            except OSError:
                time.sleep(0.25)

        process.terminate()
        raise RuntimeError(f"timed out waiting for a port-forward to {pod}")


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    namespace = "texera-workflow-computing-unit-pool"

    def log_message(self, fmt, *args):
        print(f"[proxy] {fmt % args}", flush=True)

    def do_POST(self):
        match = RUN_PATH.match(self.path.split("?")[0])
        if not match:
            self.send_error(404, "only /api/execution/{wid}/{cuid}/run is proxied")
            return

        cuid = int(match.group(1))
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))

        try:
            local = _port_forward(self.namespace, cuid)
        except Exception as error:  # noqa: BLE001 - surfaced to the caller as a 502
            self.send_error(502, str(error))
            return

        request = urllib.request.Request(
            f"http://127.0.0.1:{local}{self.path}", data=body, method="POST"
        )
        for header in ("Content-Type", "Authorization"):
            if self.headers.get(header):
                request.add_header(header, self.headers[header])

        try:
            # A run is synchronous and can take minutes; the timeout has to be
            # generous or the proxy becomes the thing that fails the execution.
            with urllib.request.urlopen(request, timeout=900) as response:
                payload, status = response.read(), response.status
        except urllib.error.HTTPError as error:
            payload, status = error.read(), error.code
        except Exception as error:  # noqa: BLE001
            self.send_error(502, f"forwarding to computing unit {cuid} failed: {error}")
            return

        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8085)
    parser.add_argument("--namespace", default="texera-workflow-computing-unit-pool")
    args = parser.parse_args()

    Handler.namespace = args.namespace
    server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"[proxy] listening on http://127.0.0.1:{args.port}", flush=True)
    print(f"[proxy] set TEXERA_EXECUTION_URL=http://127.0.0.1:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        for process, _ in _forwards.values():
            process.terminate()
    return 0


if __name__ == "__main__":
    sys.exit(main())
