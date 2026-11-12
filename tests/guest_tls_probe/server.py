#!/usr/bin/env python3
"""Disposable runner-only TLS endpoint bound exclusively to loopback."""
from pathlib import Path
import socket
import ssl
import sys

cert, key = sys.argv[1:3]
port = int(sys.argv[3]) if len(sys.argv) > 3 else 19443
count = int(sys.argv[4]) if len(sys.argv) > 4 else 7
fixture_dir = Path(sys.argv[5]) if len(sys.argv) > 5 else None
start_index = int(sys.argv[6]) if len(sys.argv) > 6 else 0
contexts = {}
def load_context(name, cert_file, key_file):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert_file, key_file)
    contexts[name] = context
load_context('root', cert, key)
if fixture_dir is not None:
    for name in ('server', 'expired', 'client', 'future'):
        load_context(name, fixture_dir / f'{name}.pem', fixture_dir / 'server-key.pem')
    load_context('intermediate_chain', fixture_dir / 'intermediate-chain.pem', fixture_dir / 'server-key.pem')
    load_context('missing_intermediate', fixture_dir / 'intermediate-leaf.pem', fixture_dir / 'server-key.pem')
with socket.socket() as listener:
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(('127.0.0.1', port))
    listener.listen(4)
    listener.settimeout(180)
    print(f'LOCAL_TLS_SERVER_READY port={listener.getsockname()[1]}', flush=True)
    for i in range(start_index, start_index + count):
        try:
            client, address = listener.accept()
            client.settimeout(20)
            with client:
                try:
                    profile = {7: 'server', 8: 'expired', 9: 'client', 10: 'intermediate_chain', 11: 'missing_intermediate', 12: 'future'}.get(i, 'root') if fixture_dir else 'root'
                    print('LOCAL_TLS_CERT_PROFILE', i, profile, flush=True)
                    with contexts[profile].wrap_socket(client, server_side=True) as secure:
                        print('LOCAL_TLS_HANDSHAKE_OK', i, secure.version(), flush=True)
                        msg = secure.recv(32)
                        if msg == b'guest-ping':
                            secure.sendall(b'guest-pong')
                            print('LOCAL_TLS_EXCHANGE_OK', i, flush=True)
                except (ssl.SSLError, OSError) as err:
                    print('LOCAL_TLS_HANDSHAKE_OR_IO_FAILED', i, type(err).__name__, str(err), flush=True)
        except (OSError, TimeoutError) as err:
            print('LOCAL_TLS_ACCEPT_FAILED', type(err).__name__, str(err), flush=True)
            break
