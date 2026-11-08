#!/usr/bin/env bash
# CI-only candidate: supported OpenSSL 3.5 LTS with 16K-compatible ELF loads.
# This produces no production APK and does not replace repository libraries.
set -euo pipefail
: "${ANDROID_HOME:?}" "${STANDALONE_WORK:?}"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
W="$STANDALONE_WORK/tls-candidate"
mkdir -p "$W"
archive="$STANDALONE_WORK/openssl-3.5.9.tar.gz"
curl -fsSL --retry 3 --connect-timeout 30 \
  https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz -o "$archive"
echo "603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a  $archive" | sha256sum -c -
tar -xzf "$archive" -C "$STANDALONE_WORK"
export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/28.2.13676358"
export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
cd "$STANDALONE_WORK/openssl-3.5.9"
perl Configure android-arm64 shared no-tests no-apps -D__ANDROID_API__=27 -Wl,-z,max-page-size=16384
make -s -j2 build_libs
# OpenSSL's android-arm64 target emits unversioned SONAMEs and filenames.
cp libcrypto.so "$W/libcrypto.so"
cp libssl.so "$W/libssl.so"
python3 - "$ROOT" "$W" <<'PY'
import importlib.util, pathlib, sys
spec = importlib.util.spec_from_file_location('guard', pathlib.Path(sys.argv[1]) / 'tools/check_kenji_release_ready.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
errors = guard.check_tls_alignment(pathlib.Path(sys.argv[2]))
if errors:
    raise SystemExit('\n'.join(errors))
print('CANDIDATE_TLS_ELF_16K_OK (does not verify APK zip alignment or device execution)')
PY
llvm-readelf -d "$W/libcrypto.so" | grep -E 'SONAME|NEEDED'
llvm-readelf -d "$W/libssl.so" | grep -E 'SONAME|NEEDED'
sha256sum "$W/libcrypto.so" "$W/libssl.so"
rm -rf "$STANDALONE_WORK/openssl-3.5.9" "$archive"
