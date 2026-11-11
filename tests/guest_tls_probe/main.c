/* Disposable diagnostic NRO: real bsd + ssl service, local test server only.
 * No TLS bypass, commercial content, user keys, or release APK inclusion. */
#include <switch.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static void say(const char* fmt, ...) {
    char buf[256]; va_list a; va_start(a, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, a); va_end(a);
    if (n > 0) svcOutputDebugString(buf, (size_t)n < sizeof(buf) ? n : sizeof(buf)-1);
}
static int check(const char *stage, Result rc) {
    say("GUEST_TLS_STAGE %s rc=0x%08x module=%u description=%u", stage, rc, R_MODULE(rc), R_DESCRIPTION(rc));
    if (R_FAILED(rc)) { say("GUEST_TLS_ERROR stage=%s rc=0x%08x", stage, rc); return 0; }
    return 1;
}

/* Require genuine verify flags and the same local server in both trials. */
static int connection(SslContext* ctx, const char* host, const char* negative) {
    SslConnection conn = {0};
    int fd = -1, transferred = -1, status = 0;
    Result rc;
    if (!check("connection_create", sslContextCreateConnection(ctx, &conn))) return 0;
    rc = sslConnectionSetVerifyOption(&conn, 0);
    if (R_SUCCEEDED(rc)) { say("GUEST_TLS_ERROR stage=verification_disabled"); goto done; }
    say("GUEST_TLS_VERIFY_DISABLE_REJECTED rc=0x%08x", rc);
    if (!check("set_verify", sslConnectionSetVerifyOption(&conn, SslVerifyOption_PeerCa | SslVerifyOption_HostName))) goto done;
    u32 flags = 0;
    if (!check("get_verify", sslConnectionGetVerifyOption(&conn, &flags))) goto done;
    say("GUEST_TLS_VERIFY_FLAGS value=0x%x", flags);
    if ((flags & (SslVerifyOption_PeerCa | SslVerifyOption_HostName)) != (SslVerifyOption_PeerCa | SslVerifyOption_HostName)) {
        say("GUEST_TLS_ERROR stage=verify_flags value=0x%x", flags); goto done;
    }
    if (!check("set_hostname", sslConnectionSetHostName(&conn, host, (u32)strlen(host)+1))) goto done;
    fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (fd < 0) { say("GUEST_TLS_ERROR stage=socket errno=%d", errno); goto done; }
    // Explicit guest loopback bind isolates TLS from the unrelated automatic
    // interface-selection path. It does not fix or test general networking.
    struct sockaddr_in local = {0};
    local.sin_family = AF_INET; local.sin_addr.s_addr = htonl(0x7f000001);
    if (bind(fd, (struct sockaddr*)&local, sizeof(local)) < 0) {
        say("GUEST_TLS_ERROR stage=loopback_bind errno=%d bsd=0x%08x", errno, socketGetLastResult()); goto done;
    }
    say("GUEST_TLS_LOOPBACK_BOUND");
    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET; addr.sin_port = htons(19443); addr.sin_addr.s_addr = htonl(0x7f000001);
    if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        say("GUEST_TLS_ERROR stage=connect errno=%d bsd=0x%08x", errno, socketGetLastResult()); goto done;
    }
    say("GUEST_TLS_SOCKET_CONNECTED host=%s", host);
    errno = 0;
    transferred = socketSslConnectionSetSocketDescriptor(&conn, fd);
    if (transferred < 0 && errno != ENOENT) {
        say("GUEST_TLS_ERROR stage=set_socket errno=%d bsd=0x%08x", errno, socketGetLastResult()); goto done;
    }
    rc = sslConnectionDoHandshake(&conn, NULL, NULL, NULL, 0);
    say("GUEST_TLS_HANDSHAKE host=%s rc=0x%08x module=%u description=%u", host, rc, R_MODULE(rc), R_DESCRIPTION(rc));
    if (negative) {
        if (R_FAILED(rc)) {
            say("GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=%s rc=0x%08x", negative, rc);
            // A rejected handshake must not leave I/O reporting success or
            // kill the guest process. These are not application data tests.
            char dummy[8]; u32 count = 0;
            Result rd = sslConnectionRead(&conn, dummy, sizeof(dummy), &count);
            Result wr = sslConnectionWrite(&conn, "x", 1, &count);
            if (R_SUCCEEDED(rd) || R_SUCCEEDED(wr)) {
                say("GUEST_TLS_ERROR stage=io_after_failed_handshake case=%s rd=0x%08x wr=0x%08x", negative, rd, wr);
                goto done;
            }
            say("GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=%s rd=0x%08x wr=0x%08x", negative, rd, wr);
            status = 1;
        } else say("GUEST_TLS_ERROR stage=negative_accepted case=%s", negative);
        goto done;
    }
    if (!check("handshake", rc)) goto done;
    const char request[] = "guest-ping";
    u32 wrote = 0, read = 0;
    if (!check("write", sslConnectionWrite(&conn, request, sizeof(request)-1, &wrote)) || wrote != sizeof(request)-1) goto done;
    char answer[32] = {0};
    // WouldBlock may be returned before the server has sent its response.
    for (int attempt = 0; attempt < 60; attempt++) {
        rc = sslConnectionRead(&conn, answer, sizeof(answer), &read);
        if (rc != MAKERESULT(123, 204)) break;
        svcSleepThread(50000000LL);
    }
    if (!check("read", rc)) goto done;
    if (read != 10 || memcmp(answer, "guest-pong", 10) != 0) {
        say("GUEST_TLS_ERROR stage=payload size=%u", read); goto done;
    }
    say("GUEST_TLS_VERIFIED_EXCHANGE_OK"); status = 1;
done:
    if (transferred >= 0 && transferred != fd) close(transferred);
    sslConnectionClose(&conn);
    if (fd >= 0) close(fd);
    return status;
}
static int read_cert(const char* path, unsigned char* out, size_t capacity, size_t* length) {
    FILE* f = fopen(path, "rb");
    if (!f) { say("GUEST_TLS_ERROR stage=guest_cert_open path=%s errno=%d", path, errno); return 0; }
    *length = fread(out, 1, capacity, f);
    int failed = ferror(f); fclose(f);
    if (failed || !*length || *length == capacity) { say("GUEST_TLS_ERROR stage=guest_cert_size path=%s", path); return 0; }
    return 1;
}
int main(void) {
    say("GUEST_TLS_BEGIN");
    if (!check("socket_init", socketInitializeDefault())) goto finish;
    if (!check("ssl_init", sslInitialize(1))) { socketExit(); goto finish; }
    SslContext ctx = {0}, other = {0};
    int other_open = 0;
    if (!check("context", sslCreateContext(&ctx, SslVersion_Auto))) goto ssl_done;
    if (!check("other_context", sslCreateContext(&other, SslVersion_Auto))) goto ctx_done;
    other_open = 1;
    unsigned char cert[4096], wrong[4096], leaf[4096], der[4096];
    size_t len, wrong_len, leaf_len, der_len;
    if (!read_cert("sdmc:/ci-test-cert.pem", cert, sizeof(cert), &len) ||
        !read_cert("sdmc:/ci-wrong-ca.pem", wrong, sizeof(wrong), &wrong_len) ||
        !read_cert("sdmc:/ci-leaf.pem", leaf, sizeof(leaf), &leaf_len) ||
        !read_cert("sdmc:/ci-test-cert.der", der, sizeof(der), &der_len)) goto ctx_done;
    u64 id = 0, wrong_id = 0;
    const char invalid_cert[] = "not a certificate";
    Result invalid = sslContextImportServerPki(&ctx, invalid_cert, sizeof(invalid_cert)-1, SslCertificateFormat_Pem, &id);
    if (R_SUCCEEDED(invalid)) { say("GUEST_TLS_ERROR stage=invalid_certificate_accepted"); goto ctx_done; }
    say("GUEST_TLS_INVALID_CERT_REJECTED rc=0x%08x", invalid);
    Result leaf_rc = sslContextImportServerPki(&ctx, leaf, (u32)leaf_len, SslCertificateFormat_Pem, &id);
    if (R_SUCCEEDED(leaf_rc)) { say("GUEST_TLS_ERROR stage=leaf_as_ca_accepted"); goto ctx_done; }
    say("GUEST_TLS_LEAF_AS_CA_REJECTED rc=0x%08x", leaf_rc);
    if (!connection(&ctx, "localhost", "no_import")) goto ctx_done;
    if (!check("import_wrong_ca", sslContextImportServerPki(&ctx, wrong, (u32)wrong_len, SslCertificateFormat_Pem, &wrong_id))) goto ctx_done;
    if (!connection(&ctx, "localhost", "wrong_ca")) goto ctx_done;
    if (!check("remove_wrong_ca", sslContextRemovePki(&ctx, wrong_id))) goto ctx_done;
    if (!check("import_test_ca", sslContextImportServerPki(&ctx, cert, (u32)len, SslCertificateFormat_Pem, &id))) goto ctx_done;
    say("GUEST_TLS_CA_IMPORT_RETURNED bytes=%zu id=%llu", len, (unsigned long long)id);
    if (!connection(&ctx, "localhost", NULL)) goto ctx_done;
    if (!connection(&other, "localhost", "other_context")) goto ctx_done;
    if (!connection(&ctx, "wrong.invalid", "wrong_host")) goto ctx_done;
    if (!check("remove_test_ca", sslContextRemovePki(&ctx, id))) goto ctx_done;
    // libnx's convenience wrapper probes server, client and CRL removal on
    // a missing ID. Test command 6 directly so an unsupported *different*
    // command does not hide whether server removal correctly reports missing.
    Result second_remove = serviceDispatchIn(&ctx.s, 6, id);
    if (R_SUCCEEDED(second_remove)) { say("GUEST_TLS_ERROR stage=removed_id_still_exists"); goto ctx_done; }
    say("GUEST_TLS_REMOVED_ID_REJECTED rc=0x%08x", second_remove);
    if (!connection(&ctx, "localhost", "untrusted_after_remove")) goto ctx_done;
    if (!check("import_der_ca", sslContextImportServerPki(&ctx, der, (u32)der_len, SslCertificateFormat_Der, &id))) goto ctx_done;
    say("GUEST_TLS_DER_CA_IMPORT_RETURNED");
    if (!connection(&ctx, "localhost", NULL)) goto ctx_done;
    say("GUEST_TLS_COMPLETE");
ctx_done:
    if (other_open) sslContextClose(&other);
    sslContextClose(&ctx);
ssl_done:
    sslExit(); socketExit();
finish:
    svcSleepThread(15000000000LL);
    return 0;
}
