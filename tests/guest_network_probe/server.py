#!/usr/bin/env python3
"""Disposable loopback-only plain TCP endpoint, independent of TLS."""
import socket
import sys

port = int(sys.argv[1]) if len(sys.argv) > 1 else 19444
with socket.socket() as listener:
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(('127.0.0.1', port))
    listener.listen(1)
    listener.settimeout(60)
    print(f'LOCAL_NET_READY port={listener.getsockname()[1]}', flush=True)
    try:
        client, address = listener.accept()
        print('LOCAL_NET_ACCEPTED', flush=True)
        with client:
            client.settimeout(10)
            received = b''
            while len(received) < 8:
                chunk = client.recv(8 - len(received))
                if not chunk:
                    raise ConnectionError('premature EOF')
                received += chunk
            if received != b'net-ping':
                raise ValueError('unexpected payload')
            client.sendall(b'net-pong')
            print('LOCAL_NET_EXCHANGE_OK', flush=True)
    except (OSError, ValueError) as err:
        print('LOCAL_NET_SERVER_ERROR', type(err).__name__, str(err), flush=True)
        raise
