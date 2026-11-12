#!/usr/bin/env python3
"""Instrument only the temporary research source clone, never the release tree."""
from pathlib import Path
import sys

if len(sys.argv) != 2: raise SystemExit('usage: instrument_tls_parallel_store.py ServerTrustStore.cs')
p = Path(sys.argv[1])
s = p.read_text()
if 'CI_TLS_STORE_IMPORT' in s or 'CI_TLS_STORE_VALIDATION' in s:
    raise SystemExit('STORE_ALREADY_INSTRUMENTED')
old = '        private readonly object _sync = new();'
new = '''        // CI-only: correlate imports and validation without trusting logcat TID.
        private static long _ciNextStore;
        private readonly long _ciStoreId = System.Threading.Interlocked.Increment(ref _ciNextStore);
        private readonly object _sync = new();'''
if s.count(old) != 1: raise SystemExit('UNEXPECTED_STORE_LOCK')
s = s.replace(old, new)
old = '                    _imports.Add(id, certificates);'
new = old + '''
                    Logger.Info?.Print(LogClass.ServiceSsl, $"CI_TLS_STORE_IMPORT store={_ciStoreId} sha256={Convert.ToHexString(SHA256.HashData(certificates[0])).ToLowerInvariant()}");'''
if s.count(old) != 1: raise SystemExit('UNEXPECTED_STORE_IMPORT')
s = s.replace(old, new)
old = '                bool valid = chain.Build(leaf);'
new = old + '''
                Logger.Info?.Print(LogClass.ServiceSsl, $"CI_TLS_STORE_VALIDATION store={_ciStoreId} valid={valid}");'''
if s.count(old) != 1: raise SystemExit('UNEXPECTED_STORE_VALIDATION')
s = s.replace(old, new)
p.write_text(s)
