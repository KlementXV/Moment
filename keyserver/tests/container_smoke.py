#!/usr/bin/env python3
"""Exercise the built image with real PostgreSQL TLS + native ONNX, no Solana/R2 access.

python3 keyserver/tests/container_smoke.py --image moment-keyserver:cnpg-test
Optional DOCKER_SMOKE_COMMAND='docker --config /path -H unix:///path/docker.sock'.
Requires Docker, Python 3 and openssl on the host. Pulls postgres:17-bookworm and
python:3.12-slim if absent. Only uniquely named ephemeral containers are removed.
The RFC8032 key below is public TEST material, never a production key.
"""
import argparse
import base64
import hashlib
import http.server
import json
import os
from pathlib import Path
import secrets
import shlex
import subprocess
import sys
import tempfile
import time
import urllib.request

SEED = bytes.fromhex('9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60')
PUBLIC = bytes.fromhex('d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a')
PROGRAM = bytes([9]) * 32


def b58(raw):
    alphabet = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'
    number = int.from_bytes(raw, 'big')
    result = ''
    while number:
        number, rest = divmod(number, 58)
        result = alphabet[rest] + result
    return '1' * (len(raw) - len(raw.lstrip(b'\0'))) + result


def config_pda():
    prime = 2 ** 255 - 19
    d = -121665 * pow(121666, prime - 2, prime) % prime
    for bump in range(255, -1, -1):
        digest = hashlib.sha256(b'config' + bytes([bump]) + PROGRAM + b'ProgramDerivedAddress').digest()
        y = int.from_bytes(digest, 'little') & ((1 << 255) - 1)
        x2 = (y*y - 1) * pow(d*y*y + 1, prime - 2, prime) % prime
        if pow(x2, (prime - 1)//2, prime) == prime - 1:
            return b58(digest), bump
    raise AssertionError('no PDA')


def mock_server():
    address, bump = config_pda()
    config = bytearray(184)
    config[:8] = hashlib.sha256(b'account:Config').digest()[:8]
    config[40:72] = PUBLIC
    config[144:152] = (10).to_bytes(8, 'little')
    config[178:180] = (2500).to_bytes(2, 'little')
    config[180], config[182] = 30, bump

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            if request['method'] == 'getGenesisHash':
                result = 'EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG'
            elif request['method'] == 'getAccountInfo' and request['params'][0] == address:
                result = {'context': {'slot': 1}, 'value': {
                    'owner': b58(PROGRAM), 'executable': False,
                    'data': [base64.b64encode(config).decode(), 'base64']}}
            else:
                self.send_error(400)
                return
            body = json.dumps({'jsonrpc': '2.0', 'id': request['id'], 'result': result}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    http.server.ThreadingHTTPServer(('127.0.0.1', 8899), Handler).serve_forever()


def probe():
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            for path, expected in [('healthz', 'ok'), ('readyz', 'ready')]:
                with urllib.request.urlopen('http://127.0.0.1:8080/' + path, timeout=3) as response:
                    assert response.status == 200
                    assert json.load(response)['status'] == expected
            print('PASS: /healthz and /readyz respond 200 after real ONNX startup and PostgreSQL verify-full TLS')
            return
        except (OSError, AssertionError, ValueError):
            time.sleep(1)
    raise RuntimeError('backend did not become healthy within 90 seconds')


def smoke(image, postgres_image, python_image):
    docker = shlex.split(os.environ.get('DOCKER_SMOKE_COMMAND', 'docker'))
    prefix = 'moment-smoke-' + secrets.token_hex(5)
    names = []

    def run(*args, check=True, **kwargs):
        return subprocess.run(docker + list(args), text=True, check=check, **kwargs)

    def start(suffix, *args):
        name = prefix + '-' + suffix
        names.append(name)
        run('run', '-d', '--name', name, *args, stdout=subprocess.DEVNULL)
        return name

    with tempfile.TemporaryDirectory(prefix='moment-container-smoke-') as temporary:
        root = Path(temporary)
        root.chmod(0o755)
        root.joinpath('authority.json').write_text(json.dumps(list(SEED + PUBLIC)))
        root.joinpath('container_smoke.py').write_text(Path(__file__).read_text())
        def openssl(*args):
            subprocess.run(['openssl', *args], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                '-subj', '/CN=Moment smoke CA only', '-addext', 'basicConstraints=critical,CA:TRUE',
                '-keyout', str(root/'ca.key'), '-out', str(root/'ca.crt'))
        openssl('req', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=localhost',
                '-keyout', str(root/'server.key'), '-out', str(root/'server.csr'))
        root.joinpath('server.ext').write_text(
            'basicConstraints=critical,CA:FALSE\nsubjectAltName=IP:127.0.0.1,DNS:localhost\n'
            'keyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
        openssl('x509', '-req', '-in', str(root/'server.csr'), '-CA', str(root/'ca.crt'),
                '-CAkey', str(root/'ca.key'), '-CAcreateserial', '-days', '1',
                '-extfile', str(root/'server.ext'), '-out', str(root/'server.crt'))
        root.joinpath('ca.key').unlink()
        for file in root.iterdir():
            file.chmod(0o444)
        env = {
            'NETWORK': 'devnet', 'RPC_URL': 'http://127.0.0.1:8899',
            'AUTH_ORIGIN': 'http://127.0.0.1:8080', 'PROGRAM_ID': b58(PROGRAM),
            'PUBLICATION_AUTHORITY_KEYPAIR': '/fixture/authority.json',
            'KEY_ENCRYPTION_KEY': base64.b64encode(bytes([42])*32).decode(),
            'BLOB_STORE': 'local', 'BLOB_DIR': '/blobs',
            'ALLOW_UNCALIBRATED_MODERATION': 'true',
            'PGHOST': '127.0.0.1', 'PGPORT': '5432', 'PGUSER': 'moment',
            'PGPASSWORD': 'moment-smoke-test-only', 'PGDATABASE': 'moment',
            'PGSSLMODE': 'verify-full', 'PGSSLROOTCERT': '/fixture/ca.crt',
        }
        root.joinpath('backend.env').write_text('\n'.join(f'{key}={value}' for key, value in env.items()) + '\n')
        root.joinpath('backend.env').chmod(0o600)
        mount = f'{root}:/fixture:ro'
        backend = None
        try:
            pg = start('postgres', '--tmpfs', '/var/lib/postgresql/data', '-v', mount,
                       '-e', 'POSTGRES_USER=moment', '-e', 'POSTGRES_PASSWORD=moment-smoke-test-only',
                       '-e', 'POSTGRES_DB=moment', postgres_image, 'bash', '-ec',
                       'cp /fixture/server.key /tmp/server.key; cp /fixture/server.crt /tmp/server.crt; '
                       'chmod 600 /tmp/server.key; chown postgres:postgres /tmp/server.key /tmp/server.crt; '
                       'exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tmp/server.crt -c ssl_key_file=/tmp/server.key')
            for attempt in range(60):
                if run('exec', pg, 'pg_isready', '-h', '127.0.0.1', '-U', 'moment', '-d', 'moment',
                       check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError('PostgreSQL not ready')
            mock = start('rpc', '--network', 'container:' + pg, '--read-only', '-v', mount,
                         python_image, 'python', '/fixture/container_smoke.py', '--mock')
            run('exec', mock, 'python', '-c',
                'import socket,time\n'
                'for attempt in range(60):\n'
                ' try:\n'
                '  connection=socket.create_connection(("127.0.0.1",8899),timeout=1); connection.close(); break\n'
                ' except OSError: time.sleep(0.25)\n'
                'else: raise RuntimeError("mock RPC did not start")')
            backend = start('backend', '--network', 'container:' + pg, '--read-only',
                            '--cap-drop=ALL', '--security-opt=no-new-privileges',
                            '--tmpfs', '/tmp:rw,nosuid,noexec,size=16m,uid=10001,gid=10001',
                            '--tmpfs', '/blobs:rw,nosuid,noexec,size=16m,uid=10001,gid=10001',
                            '-v', mount, '--env-file', str(root/'backend.env'), image)
            info = json.loads(run('inspect', backend, capture_output=True).stdout)[0]
            assert info['Config']['User'] == '10001:10001', 'image must default to nonroot'
            assert info['HostConfig']['ReadonlyRootfs'], 'root filesystem must be read only'
            run('exec', mock, 'python', '/fixture/container_smoke.py', '--probe')
            result = run('exec', pg, 'psql', '-U', 'moment', '-d', 'moment', '-Atc',
                         "SELECT count(*) FROM pg_stat_ssl s JOIN pg_stat_activity a USING(pid) "
                         "WHERE a.application_name='moment-keyserver' AND s.ssl", capture_output=True)
            assert int(result.stdout.strip()) > 0, 'backend must use real TLS connections'
            run('stop', '--time', '15', backend, stdout=subprocess.DEVNULL)
            info = json.loads(run('inspect', backend, capture_output=True).stdout)[0]
            assert info['State']['ExitCode'] == 0, 'SIGTERM must exit cleanly'
            print('PASS: UID 10001, read-only root, PostgreSQL TLS, native ONNX and graceful SIGTERM')
        except Exception:
            for name in names:
                print(f'--- smoke diagnostic {name} ---', file=sys.stderr)
                run('logs', '--tail', '40', name, check=False)
            raise
        finally:
            for name in reversed(names):
                run('rm', '-f', '-v', name, check=False, stdout=subprocess.DEVNULL)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='moment-keyserver:cnpg-test')
    parser.add_argument('--postgres-image', default='postgres:17-bookworm')
    parser.add_argument('--python-image', default='python:3.12-slim')
    parser.add_argument('--mock', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--probe', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.mock:
        mock_server()
    elif args.probe:
        probe()
    else:
        smoke(args.image, args.postgres_image, args.python_image)
