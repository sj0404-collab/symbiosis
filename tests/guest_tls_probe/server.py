#!/usr/bin/env python3
"""Disposable runner-only TLS endpoint bound exclusively to loopback."""
import socket
import ssl
import sys

cert, key = sys.argv[1:]
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(cert, key)
with socket.socket() as listener:
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(('127.0.0.1', 19443))
    listener.listen(4)
    listener.settimeout(180)
    print('LOCAL_TLS_SERVER_READY', flush=True)
    for i in range(2):
        try:
            client, address = listener.accept()
            client.settimeout(20)
            with client:
                try:
                    with context.wrap_socket(client, server_side=True) as secure:
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
