#!/usr/bin/env python3
"""CI-only endpoint: accept both connections before concurrent TLS handlers."""
from concurrent.futures import ThreadPoolExecutor
import socket
import ssl
import sys

cert, key = sys.argv[1:3]
port = int(sys.argv[3]) if len(sys.argv) > 3 else 19445
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(cert, key)
def handle(client, index):
    try:
        with client:
            client.settimeout(20)
            with ctx.wrap_socket(client, server_side=True) as tls:
                print(f'PARALLEL_SERVER_HANDSHAKE index={index} version={tls.version()}', flush=True)
                data = tls.recv(32)
                if data == b'guest-ping':
                    tls.sendall(b'guest-pong')
                    print(f'PARALLEL_SERVER_EXCHANGE index={index}', flush=True)
                    return True
                if data:
                    print(f'PARALLEL_SERVER_UNEXPECTED_PAYLOAD index={index}', flush=True)
    except (OSError, ssl.SSLError) as exc:
        print(f'PARALLEL_SERVER_CLOSED index={index} error={type(exc).__name__}', flush=True)
    return False
with socket.socket() as listener:
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(('127.0.0.1', port)); listener.listen(4); listener.settimeout(60)
    print(f'PARALLEL_SERVER_READY port={listener.getsockname()[1]}', flush=True)
    clients = []
    try:
        for _ in range(2):
            client, _ = listener.accept(); clients.append(client)
        print('PARALLEL_SERVER_BOTH_ACCEPTED', flush=True)
        with ThreadPoolExecutor(max_workers=2) as pool:
            result = list(pool.map(lambda pair: handle(*pair), [(clients[0], 0), (clients[1], 1)]))
        print(f'PARALLEL_SERVER_DONE exchanges={sum(result)}', flush=True)
    finally:
        for client in clients: client.close()
